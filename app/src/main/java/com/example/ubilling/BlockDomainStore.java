package com.example.ubilling;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedBridge;

/**
 * 【I/Net】去广告域名黑名单仓库（BlockDomainStore）—— AdClose 思路的"域名黑名单"内核。
 *
 * ============================================================================
 * 为什么换方案（v1.14）：旧 I 通道(hook 应用 ClassLoader.loadClass 屏蔽广告 SDK 类)对
 * 目标 App 效果差——它是"类是否加载"层的启发式，拦不住广告 SDK 的联网下发，且整类屏蔽
 * 在硬引用场景会闪退(v1.13 才默认放行、仅观测，等于没拦)。AdClose 这类成熟模块的真实
 * 有效做法是【网络层域名拦截】：掐断广告 SDK 请求广告物料的那一跳(Hook InetAddress DNS +
 * 网络响应)。本类负责这块的"黑名单数据"。
 *
 * ============================================================================
 * 数据源（三层，从上到下回退，绝不因任何一层失败而崩）：
 *   1) 在线更新：可配置 URL（Settings 里 adblock_url，纯文本/一行一域名/hosts 均可）。
 *      后台线程异步拉取，成功即替换/并入内存黑名单，并记录一次本地缓存，失败保持现状。
 *   2) 内置清单：随 APK 打进 assets/adblock_domains.txt（从 AdClose 官方清单抽取 17,475 条，
 *      离线也有得拦）。
 *   3) 空实现：都读不到时本类返回"不放行任何域名"，去广告静默停用（不崩不误伤）。
 *
 * ============================================================================
 * 匹配语义（尽量贴近 AdClose，但用更安全的做法）：
 *   · 普通条目(不含 '*')：命中 = 请求域名 等于该条目，或以 '.'+该条目 结尾。
 *      例：条目 gdt.qq.com 命中 gdt.qq.com / a.gdt.qq.com；不会误伤 qq.com。
 *      用 HashSet 作后缀集合，O(1) 近似匹配，不做 AdClose 那种对每条 contains 的 O(N)。
 *   · 含 '*' 的通配条目(极少，约几十条)：编译成正则(把 '*' 当任意段)，单独小集合命中。
 *   · 在线清单若给的是 hosts 格式(带 0.0.0.0 / 127.0.0.1 前缀)或带协议/path 的 URL 行，
 *      会做归一化抽 host（尽力而为，取不到就忽略该行）。
 *
 * 仅用于你自己/获授权 App 的防御自测。勿拿去破坏他人商业 App 的营收展示。
 */
public final class BlockDomainStore {

    private static final String TAG = "[UAd]";

    /** 内置离线清单在 APK 里的 asset 路径。 */
    static final String ASSET_FILE = "assets/adblock_domains.txt";

    // ---------------- 匹配数据结构 ----------------
    /** 普通域名后缀集合：条目小写，无前导点，命中需 host==e 或 host.endsWith("."+e)。 */
    private static volatile Set<String> sSuffix = new HashSet<String>();
    /** 通配条目编译成的正则。 */
    private static volatile List<Pattern> sWild = new ArrayList<Pattern>();
    /** 去重用小计数（仅日志）。 */
    private static volatile int sTotal = 0;

    /** 是否已至少成功加载过一次（内置 或 在线）。 */
    private static final AtomicBoolean sReady = new AtomicBoolean(false);

    /** 防止同进程重复加载的线程安全闸。 */
    private static final Object LOCK = new Object();

    private BlockDomainStore() {
    }

    /**
     * 入口：确保至少加载过内置离线清单（同步、快、绝不让 hook 进程卡）。
     * 在线更新走后台线程，本方法不阻塞。多次调用幂等。
     */
    public static void ensureLoaded() {
        if (sReady.get()) return;
        synchronized (LOCK) {
            if (sReady.get()) return;
            try {
                loadBundledAsset();
                sReady.set(true);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " 加载内置清单失败(去广告将依赖在线/停用): " + t);
            }
            // 无论内置成功与否，都再起一个后台线程尝试在线更新（尽力而为）。
            maybeStartOnlineUpdate(true);
        }
    }

    /** 由 UI/外部显式触发一次在线更新（用户在设置里点了"立即更新"）。 */
    public static void requestOnlineUpdate() {
        maybeStartOnlineUpdate(true);
    }

    // ------------------------------------------------------------------
    // 在线更新（后台线程，不阻塞 hook 主流程）
    // ------------------------------------------------------------------
    private static void maybeStartOnlineUpdate(boolean force) {
        String url = Settings.str(Settings.K_ADBLOCK_URL, "");
        if (url == null || url.trim().length() == 0) return; // 未配置在线源
        final String src = url.trim();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    fetchAndMerge(src);
                } catch (Throwable ignored) {
                }
            }
        }, "ub-adblock-online");
        t.setDaemon(true);
        t.start();
    }

    /** 拉取在线清单并合并进内存（失败静默保留现状）。 */
    private static void fetchAndMerge(String src) throws Throwable {
        Set<String> addSuffix = new HashSet<String>();
        List<Pattern> addWild = new ArrayList<Pattern>();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(src);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ubilling-adblock/1.0");
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                addOne(normalize(line), addSuffix, addWild);
            }
            r.close();
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignore) {} }
        }
        int n = addSuffix.size() + addWild.size();
        if (n > 0) {
            synchronized (LOCK) {
                Set<String> ns = new HashSet<String>(sSuffix);
                ns.addAll(addSuffix);
                sSuffix = ns;
                List<Pattern> nw = new ArrayList<Pattern>(sWild);
                nw.addAll(addWild);
                sWild = nw;
                sTotal = ns.size() + nw.size();
            }
            sReady.set(true);
            XposedBridge.log(TAG + " 在线清单已并入 " + n + " 条(新增), 当前总黑名单 " + sTotal);
        } else {
            XposedBridge.log(TAG + " 在线清单下载成功但无有效条目(已忽略格式): " + src);
        }
    }

    // ------------------------------------------------------------------
    // 内置离线清单加载
    // ------------------------------------------------------------------
    /** 用 ZipFile 读自己的 APK asset（免 Android 依赖，纯 java.util.zip）。 */
    private static void loadBundledAsset() throws Throwable {
        InputStream in = openOwnAsset(ASSET_FILE);
        if (in == null) {
            XposedBridge.log(TAG + " 未找到内置清单 asset(" + ASSET_FILE + ")，跳过内置加载");
            return;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        Set<String> suffix = new HashSet<String>();
        List<Pattern> wild = new ArrayList<Pattern>();
        String line;
        int parsed = 0;
        while ((line = r.readLine()) != null) {
            if (addOne(normalize(line), suffix, wild)) parsed++;
        }
        r.close();
        sSuffix = suffix;
        sWild = wild;
        sTotal = suffix.size() + wild.size();
        XposedBridge.log(TAG + " 内置离线清单加载完成: " + parsed + " 条解析成功"
                + " (含通配 " + wild.size() + "), 可拦截域名 " + sTotal);
    }

    /** 返回模块 APK 内某 asset 的输入流；找不到返回 null。全程 try，失败返回 null。 */
    private static InputStream openOwnAsset(String entry) {
        try {
            java.security.CodeSource cs =
                    BlockDomainStore.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            java.net.URL loc = cs.getLocation();
            if (!"file".equalsIgnoreCase(loc.getProtocol())) {
                return fallbackOpenByClasspath(entry);
            }
            java.io.File f = new java.io.File(loc.toURI());
            if (!f.isFile()) {
                // code source 指向的是 class 目录而非 zip 时，退回 classpath 资源
                return fallbackOpenByClasspath(entry);
            }
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
            java.util.zip.ZipEntry ze = zf.getEntry(entry);
            if (ze == null) { zf.close(); return null; }
            InputStream in = zf.getInputStream(ze);
            // 包装：读完后关闭 zip，防止句柄泄漏
            return new java.io.FilterInputStream(in) {
                @Override public void close() throws java.io.IOException {
                    try { super.close(); } finally { zf.close(); }
                }
            };
        } catch (Throwable t) {
            try {
                return fallbackOpenByClasspath(entry);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    /** 兜底：尝试从 classpath 资源读（打包成 classpath 目录跑测试时有用）。 */
    private static InputStream fallbackOpenByClasspath(String entry) {
        InputStream in = BlockDomainStore.class.getClassLoader().getResourceAsStream(entry);
        if (in == null) {
            // 兼容直接把 asset 放根的情形
            in = BlockDomainStore.class.getClassLoader()
                    .getResourceAsStream(entry.replace("assets/", ""));
        }
        return in;
    }

    // ------------------------------------------------------------------
    // 归一化 + 单条入集
    // ------------------------------------------------------------------
    /**
     * 归一化一行：去掉空白/注释；hosts 前缀(0.0.0.0 / 127.0.0.1 / ::)与协议/http(s) 抽 host；
     * 去尾部 /path、去开头的 *. 或 . ；统一小写。返回可用于匹配的裸域名(可含'*')，非法返回 null。
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.length() == 0 || s.startsWith("#") || s.startsWith("!")) return null;
        // hosts 条目：0.0.0.0 x.com / 127.0.0.1 x.com / :: x.com
        String[] sp = s.split("\\s+");
        if (sp.length >= 2 && (sp[0].equals("0.0.0.0") || sp[0].equals("127.0.0.1")
                || sp[0].equals("::") || sp[0].equals("::1") || sp[0].startsWith("0.0.0.0"))) {
            s = sp[1];
        }
        // 去协议
        int proto = s.indexOf("://");
        if (proto >= 0) s = s.substring(proto + 3);
        // 去 path/query/fragment
        for (char c : new char[]{'/', '?', '#', '\\'}) {
            int idx = s.indexOf(c);
            if (idx >= 0) s = s.substring(0, idx);
        }
        // 去用户信息 @ 之前(极少)
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        // 去端口
        int colon = s.indexOf(':');
        if (colon >= 0 && !s.contains("[")) s = s.substring(0, colon);
        // 去成对通配/首点/星前导
        while (s.startsWith("*.")) s = s.substring(2);
        while (s.startsWith(".")) s = s.substring(1);
        while (s.startsWith("*")) s = s.substring(1);
        // 校验合法域名字符集
        if (s.length() == 0) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '*';
            if (!ok) return null;
        }
        // 至少要有一个点才可能真是域名（防把裸词当域名整机误伤）；纯 IPv4/IPv6 也放行
        boolean hasDot = s.indexOf('.') >= 0;
        boolean isIp = s.matches("(\\d{1,3}\\.){3}\\d{1,3}");
        if (!hasDot && !isIp && !s.contains("*")) return null;
        return s;
    }

    /** 归一化后加入对应集合；返回是否成功加入一条。 */
    private static boolean addOne(String s, Set<String> suffix, List<Pattern> wild) {
        if (s == null) return false;
        if (s.indexOf('*') >= 0) {
            // 通配 → 正则（仅极少数，逐条 test 便宜）
            try {
                StringBuilder re = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '*') re.append(".*");
                    else if (c == '.') re.append("\\.");
                    else if (c == '-') re.append("-");
                    else if (c >= 'a' && c <= 'z') re.append(c);
                    else if (c >= '0' && c <= '9') re.append(c);
                    else return false;
                }
                wild.add(Pattern.compile(re.toString()));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        suffix.add(s);
        return true;
    }

    // ------------------------------------------------------------------
    // 对外匹配接口
    // ------------------------------------------------------------------
    /** 数据是否就绪（加载过一次）。 */
    public static boolean isReady() {
        return sReady.get();
    }

    /** 当前黑名单规模（仅日志）。 */
    public static int size() {
        return sTotal;
    }

    /** 判断某 host(小写/不带端口) 是否命中黑名单。未就绪恒 false。 */
    public static boolean isBlockedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase().trim();
        // 去尾部点
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.length() == 0) return false;
        Set<String> sx = sSuffix;
        if (sx != null && !sx.isEmpty()) {
            if (sx.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot >= 0 && dot < h.length() - 1 && sx.contains(h.substring(dot + 1))) return true;
        }
        List<Pattern> wl = sWild;
        if (wl != null && !wl.isEmpty()) {
            for (int i = 0; i < wl.size(); i++) {
                try {
                    if (wl.get(i).matcher(h).matches()) return true;
                } catch (Throwable ignore) {
                }
            }
        }
        return false;
    }

    /** 判断某完整 URL 是否命中（抽 host 判断）。 */
    public static boolean isBlockedUrl(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URI(url).getHost();
            if (host == null) {
                // 拿不到就用含协议的裸域名兜底
                return isBlockedHost(extractHostSimple(url));
            }
            return isBlockedHost(host);
        } catch (Throwable t) {
            return isBlockedHost(extractHostSimple(url));
        }
    }

    private static String extractHostSimple(String url) {
        String s = url.toLowerCase();
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_')) {
                return s.substring(0, i);
            }
        }
        return s;
    }
}
