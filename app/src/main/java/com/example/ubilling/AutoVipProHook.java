package com.example.ubilling;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 【F】全 VIP/PRO 自动盲扫通道（AutoVipProHook）—— 覆盖“会员态由某个具名业务
 * 方法返回、但不经过 SP/Billing/构造器位”的自有/授权 App。它【不依赖人工配置
 * 类名.方法名】，而是像 UVip 扫 SP key 那样，按一套【方法名强词表】去遍历目标
 * App 里已加载的类，自动找出形如 isVip()/isPro()/isPremium()/getVipLevel() 这类
 * 判定方法，并强制改写其返回值，令“是否 VIP / 是否 PRO / 会员等级”判定恒为开通态。
 *
 * ============================================================================
 * 与既有通道的分工（为何需要它）：
 *   【B】UVip       扫 SharedPreferences 的 getXxx + key 关键词 —— 只覆盖 SP 型。
 *   【C】ProActivator 死锁一个白名单包 + 精确 (Z,Enum,J,Z) 构造器 —— 不通用。
 *   【D】NetLabHook 网络/WebView 观测 —— 服务端/联网型。
 *   【E】MethodRuleHook 人工配置 类.方法->返回值 —— 需逐条逆向后手填，做不到开箱即用。
 *   但真实世界大量“会员判定”就是【一个普通 getter】：MyApp.isVip()、UserInfo.isPro()、
 *   Account.isGold()、getVipLevel() 返回档位 int…… 类型五花八门、类名混淆随机，
 *   人工配置追不上。本通道按【方法名形态】自动发现并注入，不挑类名、不挑混淆。
 *
 * ============================================================================
 * 安全设计（重中之重 —— 吸取 EArc 误伤 / UVip v10 v11 的教训）：
 *   方法级盲扫比 SP key 盲扫危险得多：hook 错一个 isXxx 会改变对象内部判定流程，
 *   可能让正常逻辑“恒真”而损坏功能（例如把 isEmpty()/isNetwork() 也误判成解锁）。
 *   因此本通道设三道门禁，宁漏勿伤：
 *
 *   门禁1【方法名强词】：仅当方法名(全名/去掉 get/is/has 前缀后) 命中 STRONG 强词，
 *       且不命中 FALSE_POSITIVES(误伤子串) 才候选。不会盲抓 isEmpty/isVisible。
 *   门禁2【形态】：只 hook 无参、非 static(除少数)、返回 boolean/int/long/String 的
 *       getter/isXxx/hasXxx；带参、返回 void/Object/集合的一律跳过。
 *   门禁3【分级注入 + 默认只观测】：
 *       - 把“绝对安全的 boolean 解锁位”(isPro/isVip/isPremium 精确名) 归 STRONG_BOOL，
 *         默认即可注入 true（这类名字几乎不可能误伤）；
 *       - 其余(名字含 vip/pro 前缀组合、返回 int 的等级/档位 getter 等) 在注入前需
 *         INJECT 开关打开，否则只打 [UAuto] 日志观测(LOG_ONLY)。
 *       首次对一个新 App 建议保持 LOG_ONLY=true，跑一次看 [UAuto] 命中清单，
 *       确认无误伤后再置 INJECT=true 重载模块做真实注入。
 *
 * 边界：同其它通道，仅用于你自己开发/拥有或明确获授权做安全评估的 App。
 * ============================================================================
 */
public class AutoVipProHook {

    private static final String TAG = "[UAuto]";

    /** ====== 注入总开关 ======
     *  LOG_ONLY=true  -> 只扫类/方法并打 [UAuto] 观测日志，不改任何返回值（推荐先跑一轮）。
     *  LOG_ONLY=false -> 真正强制改写命中方法的返回值（仅在你确认无误伤后开启）。
     */
    private static final boolean LOG_ONLY = true;

    /** 仅对选定的包名执行(调用方也会 gate，这里留双保险)；空/null 表示任意勾选进程。 */
    public static final String TARGET_PKG = null;

    // ==================================================================
    // 门禁1：方法名强词表（转小写后按“词”匹配，避免 isVipx 误吞）
    // ==================================================================
    /**
     * boolean 解锁位强词（无参 boolean getter 的前缀被剥掉后，剩余 core 若【整词】
     * 命中这里任一 -> 视为解锁位，可注入 true）。只放“几乎不可能被误伤”的词。
     */
    private static final String[] EXACT_BOOL = {
        "vip", "vipmember", "svip", "pro", "promember", "premium",
        "premiummember", "member", "entitled", "entitlement", "active",
        "activated", "gold", "goldvip", "unlocked", "paid", "registered",
        "license", "licensed", "fullversion", "platinum", "diamond", "crown"
    };

    /**
     * core 以这些词【结尾】也算 boolean 解锁位（覆盖 isVipMember -> member、
     * isProUser -> prouser 等组合）。与 EXACT_BOOL 同语义层，故也较安全。
     */
    private static final String[] BOOL_SUFFIX = {
        "vip", "vipmember", "svip", "promember", "pro", "premium", "premiumuser",
        "member", "paiduser", "goldvip", "fullversion", "registered", "licensed"
    };

    /** 命中即“会员等级/档位”语义(返回 int/long/String)的方法 core 需以这些词结尾。 */
    private static final String[] TIER_SUFFIX = {
        "vip", "viptype", "viptier", "viplevel", "viplv", "vipgrade",
        "memberlevel", "membertype", "membergrade", "usertype", "usergrade",
        "userlevel", "level", "grade", "tier", "type", "vipstate", "memberstate"
    };

    /** 这些方法名即使含 pro/vip 也绝不碰(误伤)。 */
    private static final String[] FALSE_POSITIVES = {
        "profile", "progress", "promote", "promotion", "prompt", "property",
        "protection", "protocol", "processor", "program", "product",
        "professional", "proof", "probe", "propagate", "produce", "provider",
        "promo", "protobuf", "projection", "android.provider", "import",
        "export", "proxy", "promise", "provide", "proguard", "isproviders",
        "support", "sports", "sport", "import", "export"
    };

    /** 纯时间/工具布尔 getter 极易与“解锁位”混淆 —— 命中即放行，绝不注入。 */
    private static final String[] HARD_PASS = {
        "isempty", "isnull", "isnetwork", "isconnected", "isvisible", "isvalid",
        "isenabled", "isalive", "isready", "isdone", "isrunning", "isstarted",
        "isshown", "isdisplayed", "issupport", "ischecked", "isselected",
        "isfirst", "islast", "islogin", "islogined", "isinit", "isloaded"
    };

    // ==================================================================
    // 状态
    // ==================================================================
    private static final Set<String> scanned = new HashSet<>();
    private static final Set<String> donePkg = java.util.Collections.synchronizedSet(new HashSet<String>());
    private static final ThreadLocal<Boolean> inScan = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile int scannedCls = 0;
    private static volatile int candBool = 0;
    private static volatile int candTier = 0;
    private static volatile int hookedCnt = 0;
    private static volatile List<String> cachedDexNames = null;

    // ==================================================================
    // 入口
    // ==================================================================
    public static void hook(final ClassLoader cl, final String pkg) {
        if (cl == null || pkg == null) return;
        if (TARGET_PKG != null && !TARGET_PKG.equals(pkg)) return;
        if (!donePkg.add(pkg)) return;

        XposedBridge.log(TAG + " 全VIP/PRO盲扫通道挂载 @ " + pkg
                + (LOG_ONLY ? " (LOG_ONLY=观测, 不改返回值)" : " (注入开启)"));
        try {
            enumerateDex(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dex枚举失败: " + t);
        }
        // loadClass 钩：覆盖晚加载/分包类
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (inScan.get()) return;
                            Object r = param.getResult();
                            if (!(r instanceof Class)) return;
                            Class<?> c = (Class<?>) r;
                            if (!scanned.add(c.getName())) return;
                            if (isSystem(c.getName())) return;
                            inScan.set(Boolean.TRUE);
                            try { scannedCls++; tryScan(c); }
                            catch (Throwable ignore) {}
                            finally { inScan.set(Boolean.FALSE); }
                        }
                    });
        } catch (Throwable ignore) {
        }
        scheduleSummary(pkg, 1500L, 1);
        scheduleSummary(pkg, 4000L, 2);
        scheduleSummary(pkg, 9000L, 3);
    }

    private static void enumerateDex(ClassLoader cl) {
        try {
            List<String> all = dexClassNames(cl);
            for (String nm : all) {
                if (isSystem(nm)) continue;
                if (!scanned.add(nm)) continue;
                try {
                    Class<?> c = Class.forName(nm, false, cl);
                    if (c == null || c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) continue;
                    inScan.set(Boolean.TRUE);
                    try { scannedCls++; tryScan(c); }
                    finally { inScan.set(Boolean.FALSE); }
                } catch (Throwable ignore) {
                }
            }
            XposedBridge.log(TAG + " dex枚举完成: 读 " + all.size() + " 类, 扫 " + scannedCls + " 应用类");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dex枚举异常: " + t);
        }
    }

    private static List<String> dexClassNames(ClassLoader cl) {
        if (cachedDexNames != null) return cachedDexNames;
        synchronized (AutoVipProHook.class) {
            if (cachedDexNames != null) return cachedDexNames;
            List<String> out = new ArrayList<>();
            try {
                Object pathList = XposedHelpers.getObjectField(cl, "pathList");
                Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
                if (dexElements != null) {
                    Class<?> dfc = Class.forName("dalvik.system.DexFile");
                    Method gcl = dfc.getDeclaredMethod("getClassNameList", Object.class);
                    gcl.setAccessible(true);
                    for (Object el : dexElements) {
                        try {
                            Object df = XposedHelpers.getObjectField(el, "dexFile");
                            if (df == null) continue;
                            Object cookie = XposedHelpers.getObjectField(df, "mCookie");
                            if (cookie == null) continue;
                            String[] names = (String[]) gcl.invoke(df, cookie);
                            if (names != null) for (String n : names) out.add(n);
                        } catch (Throwable ignore) {
                        }
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " dex类名枚举异常: " + t);
            }
            cachedDexNames = out;
            return out;
        }
    }

    private static void scheduleSummary(final String pkg, long delay, final int round) {
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    XposedBridge.log(String.format(
                            "%s [%s 第%d轮] 扫 %d 类 | boolean解锁位候选 %d | 档位候选 %d | 实际挂钩 %d",
                            TAG, pkg, round, scannedCls, candBool, candTier, hookedCnt));
                }
            }, delay);
        } catch (Throwable ignore) {
        }
    }

    // ==================================================================
    // 门禁扫描
    // ==================================================================
    private static boolean isSystem(String n) {
        return n.startsWith("android.") || n.startsWith("androidx.")
                || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.") || n.startsWith("kotlinx.")
                || n.startsWith("com.google.") || n.startsWith("com.android.")
                || n.startsWith("dalvik.") || n.startsWith("sun.")
                || n.startsWith("jdk.") || n.startsWith("org.")
                || n.startsWith("okhttp") || n.startsWith("retrofit")
                || n.startsWith("okio.");
    }

    private static void tryScan(Class<?> c) {
        if (c == null || c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) return;
        Method[] ms;
        try { ms = c.getDeclaredMethods(); } catch (Throwable t) { return; }
        for (Method m : ms) {
            try {
                judge(c, m);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 对单个方法做三道门禁判断。 */
    private static void judge(Class<?> c, Method m) {
        int mod = m.getModifiers();
        if (m.getParameterTypes().length != 0) return;        // 门禁2: 只要无参
        if (Modifier.isStatic(mod) && !Modifier.isPublic(mod)) return;
        if (!Modifier.isPublic(mod)) return;                   // 只钩 public（可被外部判定调用）
        if (m.getName().length() < 2) return;

        String name = m.getName();
        String low = name.toLowerCase(Locale.ROOT);
        // 门禁1a: 硬放行工具/时间/状态布尔
        for (String hp : HARD_PASS) if (low.contains(hp)) return;

        Class<?> ret = m.getReturnType();

        // ---- boolean 解锁位 ----
        if (ret == boolean.class) {
            // 剥离 is/has/get 前缀，得到 core（如 isVip->vip, hasPro->pro, getMember->member）
            String core = stripPrefix(low);
            // 误伤子串
            for (String fp : FALSE_POSITIVES) if (core.contains(fp)) return;
            // 精确整词命中(极安全) 或 core 以强词结尾(如 vipmember/prouser)
            boolean exact = inWords(core, EXACT_BOOL);
            boolean suffixStrong = endsWithWord(core, BOOL_SUFFIX);
            if (!exact && !suffixStrong) return;

            candBool++;
            if (LOG_ONLY) {
                XposedBridge.log(TAG + " [观测] boolean解锁位: " + c.getName() + "." + name
                        + "() -> 本可注入true (core=" + core + ")");
                return;
            }
            hookTo(c, m, "true", "boolean解锁位");
            return;
        }

        // ---- int/long/String 档位/等级 getter ----
        if (ret == int.class || ret == Integer.class || ret == long.class
                || ret == Long.class || ret == String.class) {
            String core = stripPrefix(low);
            for (String fp : FALSE_POSITIVES) if (core.contains(fp)) return;
            // 必须本身含 vip/member 词，避免把普通 level/type 全误当会员等级
            boolean hasVipWord = core.contains("vip") || core.contains("member")
                    || core.contains("premium") || core.contains("pro");
            if (!hasVipWord) return;
            if (!endsWithWord(core, TIER_SUFFIX)) return;
            candTier++;
            if (LOG_ONLY) {
                XposedBridge.log(TAG + " [观测] 档位getter: " + c.getName() + "." + name
                        + "() : " + ret.getSimpleName() + " -> 可注入顶级档位 (core=" + core + ")");
                return;
            }
            String want = (ret == String.class) ? "premium"
                    : (ret == long.class || ret == Long.class) ? "4070908800000" : "6";
            hookTo(c, m, want, "档位getter");
            return;
        }
    }

    /** hook 到固定返回值。 */
    private static void hookTo(Class<?> c, final Method m, final String want, String kind) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(coerce(m.getReturnType(), want));
                }
            });
            hookedCnt++;
            XposedBridge.log(String.format("%s 挂钩%s: %s.%s() : %s -> %s  累计=%d",
                    TAG, kind, c.getName(), m.getName(),
                    m.getReturnType().getSimpleName(), want, hookedCnt));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂钩失败 " + c.getName() + "." + m.getName() + ": " + t);
        }
    }

    private static Object coerce(Class<?> ret, String want) {
        if (ret == boolean.class || ret == Boolean.class)
            return want.equalsIgnoreCase("true") || want.equals("1");
        if (ret == int.class) return Integer.valueOf(want);
        if (ret == Integer.class) return Integer.valueOf(want);
        if (ret == long.class) return Long.valueOf(want);
        if (ret == Long.class) return Long.valueOf(want);
        return want;
    }

    // ==================================================================
    // 词匹配工具
    // ==================================================================
    private static String stripPrefix(String low) {
        for (String p : new String[]{"is", "has", "get"}) {
            if (low.startsWith(p) && low.length() > p.length()) {
                char nxt = low.charAt(p.length());
                if (Character.isLowerCase(nxt)) continue; // getter 后应接大写，非则不算前缀
                return low.substring(p.length());
            }
        }
        return low;
    }

    /** core 是否整词命中 words 任一（判解锁位/强词）。 */
    private static boolean inWords(String core, String[] words) {
        for (String w : words) if (core.equals(w)) return true;
        return false;
    }

    /** core 是否以 words 任一整词结尾（覆盖 vipmember/prouser/member 等组合）。 */
    private static boolean endsWithWord(String core, String[] words) {
        for (String w : words) {
            if (core.equals(w)) return true;
            if (core.length() > w.length() && core.endsWith(w)) {
                // 整词边界：结尾词前应是小写字母(组合词，如 proUser 的 user)，
                // 避免 isVisor/project 这类“恰好以某词尾字母撞上”被误判。
                char before = core.charAt(core.length() - w.length() - 1);
                if (Character.isLowerCase(before)) return true;
            }
        }
        return false;
    }
}
