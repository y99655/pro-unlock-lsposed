package com.example.ubilling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 自动 VIP 全兼容拦截器（SharedPreferences 多语义 + 观测学习）—— 对任意 App 通用、混淆无关。
 *
 * ============================================================================
 * 覆盖的“已知 VIP/会员/解锁判定方式”（尽量全兼容）：
 *
 *   现实里绝大多数 App 把“是否已解锁 / 会员等级 / PRO / 去广告 / 到期时间”
 *   持久化到 SharedPreferences（简称 SP），并用一个 getXxx 读回来判断。
 *   SP 是 Android SDK 类，App 自身代码可以混淆，【但 android.app.SharedPreferencesImpl
 *   与它的 getString/getBoolean/getInt/getLong/getFloat/getStringSet 方法永不混淆】。
 *
 *   因此只要拦截这 6 个 get 方法、命中语义化 key 后按返回类型回灌解锁值，就能
 *   覆盖本地判断型的绝大部分 App。我们不做任何包名白名单、不猜 App 混淆字段：
 *
 *   ① 语义分类匹配 key（付费 / 会员 / PRO / 去广告 / 到期时间 …多套关键词表）；
 *   ② 被调哪个 getXxx 就决定了它想要什么返回类型 -> 类型判断天然成立，无需猜声明；
 *   ③ 命中后注入的“解锁值”分两条轨道：
 *        - 布尔/档位键 -> true / premium 等；见 VALUE_*。
 *        - 【到期/有效期键 -> 一律回 2099-01-01】（用户硬性要求，见 FAR_DATE_MS）。
 *
 *   Google Play Billing 通道由 UniversalBillingHook 单独覆盖，本类专注 SP 主战场。
 *
 * ============================================================================
 * 【观测学习】闭环（v5：只观测第一次；用户需求：“搜他VIP记录类别/日期 -> 记录到DATA -> 按记录匹配hook”）
 *
 *   光靠“拍脑袋词表”总会漏掉某 App 特有的 key（如混淆后的 a_b_c、或自造的 vip_token）。
 *   本类在运行时做“一次性”三层自学习（每个 App 只完整跑一次，之后只回灌不重扫）：
 *
 *   L1 磁盘扫描(scanSharedPrefs)：目标 App 第一次读 SP 时（scanGate 触发），
 *       在后台线程扫一次它自己 /data/data/&lt;pkg&gt;/shared_prefs/*.xml，
 *       把所有“名字像会员 / 值像 epoch 时间戳 / 名字含会员词且值像日期”的条目抓出来，
 *       写入该 App 的 /data/data/&lt;pkg&gt;/files/uvip/hkrecords.txt，
 *       并把“确实存在的会员/日期 key”记入规则表 -> 后续读这些 key 即使词表没
 *       覆盖也能命中（真正扩大了匹配面，包括混淆 key）。
 *   L2 运行读取观测(note)：每次命中注入时，把 key/方法/判定形态 记入内存规则；
 *       仅在首次学习窗口内（hkrules.txt 未生成）追加到 hkhits.log。
 *   L3 规则回灌(learnedHit/isDateKey)：读 key 时先查内存规则表；若该 key 已确认为
 *       会员/日期形态则直接命中并按形态注入，不再只依赖词表启发式。
 *
 *   【只观测第一次】(v5 性能收敛)：观测文件固定名为
 *       hkrules.txt / hkrecords.txt / hkhits.log（每个 App 相同）。
 *     扫描前先检查 hkrules.txt 是否已存在：已存在 -> 说明首次学习已完成，
 *       只 loadPersistedRules 把规则读进内存回灌，跳过重扫/重写；
 *       不存在 -> 才执行首次三段式闭环并落盘。这样只有第一次观测有磁盘扫描
 *       开销，后续启动零 XML 扫描、零规则重写，避免性能影响与卡顿。
 *
 *   写入位置诚实说明：
 *     观测发生在“目标 App 自己的进程”，同 uid 下可写自己沙箱，因此统一写到
 *     /data/data/&lt;pkg&gt;/files/uvip/ 而非模块自己的目录（跨 uid 会被拒）。
 *     免 root、无需作用域勾“系统框架”之外的任何配置。
 *
 * ============================================================================
 * 注入值矩阵（命中后按被调 getXxx + key 语义决定回灌内容）：
 *   getBoolean -> true                                  （布尔解锁位）
 *   getLong    -> 2099-01-01 epoch 毫秒 (4070908800000)（任何 Long 到期戳恒未过期）
 *   getInt     -> 日期语义返回“大剩余”天数，否则 1       （多数判 >0 即解锁）
 *   getFloat   -> 1.0
 *   getString  -> key 含日期语义且默认值形如日期 -> "2099-01-01"；
 *                 否则若默认值是"true/1/是/yes/1.0"这类 -> 返回该值(已解锁语义)；
 *                 再否则 -> "premium"（最常见的会员档位字符串）
 *   getStringSet-> {premium, vip, pro}
 *
 * 日期统一规则（用户硬性要求）：凡是表达“到期/有效期/截止/valid_until/剩余时间”
 *   的读取一律回 2099-01-01，令“未过期”判断恒成立。可能用毫秒 Long / 秒 Long /
 *   字符串 "yyyy-MM-dd" 三种形式存到期，分别注入对应 2099 形态。
 *
 * ============================================================================
 * 诚实边界：
 *   ① 只对“付费态存在 SP、且 App 信任本地读取结果”的 App 有效；
 *   ② 服务器 entitlement / 每次启动网络拉取后覆盖 SP 的 App 不保证；
 *   ③ 命中即改写内存返回值，不改磁盘文件 —— 纯运行时注入，重启后原值仍在；
 *   ④ 观测只能看到“当前(未付费)读取/存储”的形态，看不到“付费该返回什么”，
 *      因此具体注入值仍靠类型自适应（getXxx 已决定类型 + 日期统一 2099）。
 *   ⑤ “深层字段/构造器激活位”需按 App 逆向且易误伤业务对象，不做全兼容；
 *      只保留对任意 App 都安全的 SP + Billing 双通道。
 * ============================================================================
 */
public class UniversalVipSweeper {

    private static final String TAG = "[UVip]";

    // ==================================================================
    // ① 语义关键词表（转小写后子串匹配）。可自行增删调参。
    // ==================================================================
    /** 付费/会员/已购：出现即视为“收费门禁”。
     * 注：不带裸 activate/activated —— 真机反馈它过度命中 SDK 基建控制位
     *    (forceActivate / sdk_activate / 网络探测激活), 而真会员键通常自带
     *    vip/member/premium/license/unlock 等强词, 故由这些词承担判定。 */
    public static final String[] PAID_KEYWORDS = {
        "vip", "premium", "paid", "purchase", "purchased",
        "license", "licence", "entitle", "entitlement", "member",
        "subscrib", "subscription", "gold",
        "isbuy", "has_buy", "bought", "owns", "owned", "paid_user",
        "unlock_pro"                                  // 显式组合，防 pro 误伤也覆盖
    };

    /** 升级档位/高级版（PRO 变体带下划线或与 paid 同现）。 */
    public static final String[] TIER_KEYWORDS = {
        "pro_", "_pro", "is_pro", "pro_user", "pro_enabled",
        "plus_", "deluxe", "ultimate", "premium_", "vip_"
    };

    /** 解锁功能/解锁会员。 */
    public static final String[] UNLOCK_KEYWORDS = {
        "unlock", "unlocked", "unlockfeature", "unlock_all",
        "full_version", "fullversion", "registered", "full_access"
    };

    /** 去广告（付费最常见副产物）。 */
    public static final String[] ADS_KEYWORDS = {
        "remove_ads", "removead", "noads", "no_ads", "ad_free", "adfree",
        "ads_removed", "adblock", "ad_block", "disable_ads", "disableads",
        "no_ads_mode", "ad_removed"
    };

    /** 到期/有效期/截止 —— 命中则“未过期”优先，一律回 2099-01-01。 */
    public static final String[] DATE_KEYWORDS = {
        "expire", "expiry", "expiration", "deadline", "valid_until",
        "valid_to", "end_time", "end_date", "endtime", "enddate",
        "expire_date", "expiry_date", "expires_at", "expiretime",
        "purchase_date", "valid_date", "due_date"
    };

    /** 裸 pro 的常见误伤子串（命中且无更强关键词时，不判付费）。 */
    public static final String[] PRO_FALSE_POSITIVES = {
        "profile", "progress", "promote", "promotion", "prompt",
        "property", "protection", "protocol", "processor", "program",
        "product", "professional", "proof", "probe", "propagate",
        "produce", "provider", "promo", "protobuf", "projection",
        "android.provider", "import", "export", "proxy", "promise"
    };

    // ==================================================================
    // ② 注入值
    // ==================================================================
    /** 到期统一日期：2099-01-01 00:00:00 UTC = 4070908800000 ms（秒=4070908800）。 */
    public static final long FAR_DATE_MS = 4070908800000L;

    private static final boolean VALUE_BOOL = true;      // getBoolean
    private static final int      VALUE_INT  = 1;        // getInt（非日期语义，多数判 >0）
    private static final int      VALUE_INT_DAYS = 99999;// getInt + 日期语义：剩余天数≈274年，恒“未过期”
    private static final float    VALUE_FLOAT = 1.0f;    // getFloat
    private static final String   VALUE_STRING_PREMIUM = "premium";  // 档位字符串兜底
    private static final String   FAR_DATE_STR = "2099-01-01";        // 字符串日期兜底
    private static final String   FAR_DATE_STR_MS = "4070908800000";  // 字符串毫秒日期兜底
    private static final String   FAR_DATE_STR_SEC = "4070908800";    // 字符串秒日期兜底

    /** 进程级去重，避免同一 key 每次读都刷屏（日志用）。 */
    private static final Set<String> logged = java.util.Collections.synchronizedSet(new HashSet<String>());

    /** 进程级去重：广告服务控制上下文 key 的 [UAdGuard] 跳过日志，避免刷屏。 */
    private static final Set<String> adLogged = java.util.Collections.synchronizedSet(new HashSet<String>());

    // ==================================================================
    // ③ 观测学习（Learner）：记录到被观测 App 自己的 data 目录 + 规则回灌
    // ==================================================================
    /** 观测开关：false 则完全不写文件（仅内存去重日志），便于关掉写盘副作用。 */
    private static final boolean ENABLE_LEARN = true;

    /** 观测记录统一固定文件名（写进被观测 App 自己的 /files/uvip/ 目录）。
     *  存在性即“是否已完成首次学习”的哨兵：三个文件任一已存在就不重复跑
     *  三段式「观测-学习-回灌」，避免每次启动都重扫 SP 造成性能开销/卡顿。 */
    private static final String FN_RULES   = "hkrules.txt";    // 学习规则 (KEY<TAB>shape) -> 启动热加载回灌
    private static final String FN_RECORDS = "hkrecords.txt";  // 首次磁盘扫描出的会员/日期 key + 值 + 形态
    private static final String FN_HITS    = "hkhits.log";     // 运行命中观测(仅首次学习窗口内追加)

    /** 被观测 App 自己的观测目录。 */
    private static File uvipDir(String pkg) {
        return new File("/data/data/" + pkg + "/files/uvip");
    }

    /** 每个进程只做一次磁盘扫描（key: pkg）。 */
    private static final Set<String> scannedPkg = java.util.Collections.synchronizedSet(new HashSet<String>());

    /** 本进程是否已安排过一次扫描（之后 scanGate 直接短路，免每读反射）。 */
    private static volatile boolean scanScheduled = false;

    /** 学习到的规则表：key = "pkg\u0001key"，value = 判定出的形态字符串。 */
    private static final Map<String, String> learnedRule =
            java.util.Collections.synchronizedMap(new HashMap<String, String>());

    /** 进程内去重，同一 (pkg,key,method) 观测/写入一次即可，避免每次都写盘。 */
    private static final Set<String> notedSig = java.util.Collections.synchronizedSet(new HashSet<String>());

    // 形态标签常量
    private static final String SHAPE_BOOL = "bool";
    private static final String SHAPE_INT  = "int";
    private static final String SHAPE_DATE = "date";   // 日期/到期语义（注入 2099）
    private static final String SHAPE_STR_PREMIUM = "premium";

    /**
     * 获取当前进程的包名（用于日志标注 + 观测落盘目录）。
     * ActivityThread 是 @hide 类，标准 android.jar 不含，故用反射调用，避免编译期
     * 引用隐藏 API；句柄缓存避免每次反射 getMethod。
     */
    private static volatile Method mCurPkg = null;

    private static String currentPkg() {
        try {
            Method m = mCurPkg;
            if (m == null) {
                Class<?> at = Class.forName("android.app.ActivityThread");
                m = at.getMethod("currentPackageName");
                mCurPkg = m;
            }
            Object r = m.invoke(null);
            return r == null ? "?" : r.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // 语义判定
    // ------------------------------------------------------------------
    /** 判定 key 是否命中任一词义（付费/档位/解锁/去广告/到期）。 */
    static boolean hit(String rawKey) {
        if (rawKey == null) return false;
        // v1.12 广告护栏(AdGuard)：key 若属【广告服务控制/激活/展示】上下文(如 banner_enable/
        //   ad_show/splashad/ad_load/cpm_init…)，注入它等于把广告放出来 -> 一律不判为会员、不注入，
        //   打一次 [UAdGuard] 观测(便于用户实测是哪个通道把广告相关 key 判成了会员)。
        String lowk = rawKey.toLowerCase();
        if (AdGuard.isAdControl(lowk)) {
            if (adLogged.add(rawKey)) {
                AdGuard.logSkipped("B/UVip", "SP key", rawKey);
            }
            return false;
        }
        // 业务时间戳护栏：last_app_modify_date 等即使被词表/旧规则命中也整体放行，
        // 绝不对其注入日期或 premium(真机反馈: DCloud 防二次打包字段曾被误注 2099)。
        if (hasAny(lowk, BIZ_TIMESTAMP)) return false;
        // SDK 基础设施护栏：HttpDns/广告/统计/初始化标志等 key 含 expire/activate/cpm 等
        // 易误判词，但本质与用户会员态无关，整体放行(真机反馈: cpm_expire_time/
        // sdk_activate_init/http_dns_refetch_on_expire 曾被误注)。此栏须在词表命中前。
        if (hasAny(rawKey.toLowerCase(), SDK_INFRA)) return false;
        // 先查学习规则：若该 key 已被确认是会员/日期形态，直接命中（无需词表覆盖）
        if (learnedHit(rawKey)) return true;
        String k = rawKey.toLowerCase();
        if (hasAny(k, PAID_KEYWORDS)) return true;
        if (hasAny(k, TIER_KEYWORDS)) return true;
        if (hasAny(k, UNLOCK_KEYWORDS)) return true;
        if (hasAny(k, ADS_KEYWORDS)) return true;
        if (hasAny(k, DATE_KEYWORDS)) return true;
        // 裸 pro（如 "pro"/"ispro"/"provalue"）：仅当不含误伤词才算会员
        if (k.contains("pro")) {
            return !hasAny(k, PRO_FALSE_POSITIVES);
        }
        return false;
    }

    /**
     * 判定 key 是否带“到期/有效期”语义（决定是否走 2099 日期分支）。
     * 护栏顺序：
     *   1) BIZ_TIMESTAMP(业务时间戳特征, 如 last_app_modify_date) -> 直接放行;
     *   2) DATE_KEYWORDS 词命中 -> 是日期键;
     *   3) 学习规则标 date 的 key -> 仅当 key 名也含日期提示词才算(防 adid 等
     *      纯数字 ID 被旧版观测残留误标 date 后仍注入 2099 —— 真机反馈项)。
     */
    static boolean isDateKey(String rawKey) {
        if (rawKey == null) return false;
        String k = rawKey.toLowerCase();
        // v1.12：广告服务控制上下文绝不按"会员到期"注入 2099(会误把广告相关时间戳当到期)
        if (AdGuard.isAdControl(k)) return false;
        if (hasAny(k, BIZ_TIMESTAMP)) return false;
        // SDK 基础设施(key 名带 http_dns/cpm_/sdk_activate 等)绝不当“会员到期”注入
        if (hasAny(k, SDK_INFRA)) return false;
        if (hasAny(k, DATE_KEYWORDS)) return true;
        // 学习规则里该 key 若被判定为 date 形态，也视为日期键（需词佐证）
        String pkg = currentPkg();
        if (pkg != null) {
            String rule = learnedRule.get(pkg + "\u0001" + rawKey);
            if (SHAPE_DATE.equals(rule) && hasAny(k, DATE_HINT_WORDS)) return true;
        }
        return false;
    }

    /** 业务时间戳特征：命中则永不按“到期日期”注入(防误伤 last_app_modify_date 等)。 */
    private static final String[] BIZ_TIMESTAMP = {
        "last_app_modify", "modify_date", "last_modify", "update_date", "updated_at",
        "build_date", "build_time", "version_date", "install_time", "install_date",
        "first_launch", "create_time", "created_at", "created_date", "register_time",
        "launch_time", "app_modify"
    };

    /** SDK 基础设施护栏：命中则整体放行(绝不注入)。这些是【任意 App 都会有的网络/广告/
     *  统计/加速 SDK 的通用缓存 key】，如 HttpDns、移动网络加速、广告 SDK 的 token 刷新、
     *  SDK 初始化标志等。它们名里常含 expire/activate/cpm 等易被词表误判为“会员到期/激活”
     *  的词，但本质是【基础设施状态，与用户会员付费态无关】。命中即放行，
     *  避免 UVip 把注入火力全打在无关 SDK 字段上、掩盖真正的会员 key(真机反馈项:
     *  cpm_expire_time / max_expire_time / use_http_dns_refetch_on_expire /
     *  sdk_activate_init 均被误注过)。 */
    private static final String[] SDK_INFRA = {
        // HttpDns / 移动网络加速(腾讯云 XG/阿里/自建)
        "http_dns", "httpdns", "dns_refetch", "refetch_on_expire", "dnspod",
        // 网络/连接 自身配置(非业务网络状态)
        "cpm_", "cpmexpire", "max_expire_time", "network_optim", "netopt",
        // 各类 SDK 初始化/版本/自检标志(非会员激活)
        "sdk_activate", "sdk_init", "sdk_init_state", "_init_flag", "init_flag",
        "first_init", "sdk_ver", "sdk_version", "framework_",
        // 广告/上报 SDK 内部状态(穿山甲/优量汇/友盟等)
        "advert_init", "ads_sdk", "ad_sdk_state", "report_", "upload_state",
        "push_state", "xg_push", "umeng_", "bugly_", "sentry_",
        // 埋点/分析/多进程/开关类 SDK 基建(非会员)
        "cache_ana", "ana_switch", "expire_switch", "support_multi",
        "multi_process", "process_support",
        // 一般性 SDK token/刷新/心跳(非会员 token)
        "sdk_token", "refresh_token_sdk", "heartbeat", "keepalive", "session_refresh",
        // 真机(v14)新见基建/控制位 —— 与会员付费态无关, 命中即放行:
        //   forceActivate/AfterInstalled(强制激活标记), splashAd 开屏广告加载控制,
        //   tnc_probe 网络探测(version/cmd), 及通用 probe 探测/feature 开关
        "forceactivate", "afterinstall", "splashad", "adloadprocess", "loadprocess",
        "tnc_probe", "probe_version", "probe_cmd", "probe_", "_probe",
        "featuretoggle", "featureflag", "remoteswitch", "bizswitch", "conffetch"
    };

    /** 日期提示词：学习规则把某 key 标为 date 后，仍需这些词佐证才算日期键(防误伤)。 */
    private static final String[] DATE_HINT_WORDS = {
        "expire", "expiry", "expiration", "deadline", "valid_", "validity",
        "end_date", "end_time", "ends_at", "expires", "due_", "until",
        "date", "_time", "timestamp"
    };

    private static boolean hasAny(String k, String[] arr) {
        for (String w : arr) if (k.contains(w)) return true;
        return false;
    }

    /**
     * 命中前先查内存学习规则。只要 key 已经被 L1 磁盘扫描或此前命中确认过，就算它
     * 完全不含词表关键词，也判定为会员字段 -> 真正扩大匹配面。
     * 例外：规则标 date 但 key 名无任何日期提示词的(如旧版残留把 adid 标 date)，
     * 判定为误标 -> 放行(真机反馈: adid::getString 曾被注入 2099 / premium)。
     */
    private static boolean learnedHit(String rawKey) {
        String pkg = currentPkg();
        if (pkg == null || "?".equals(pkg)) return false;
        String v = learnedRule.get(pkg + "\u0001" + rawKey);
        if (v == null) return false;
        if (SHAPE_DATE.equals(v)) {
            String k = rawKey.toLowerCase();
            if (!hasAny(k, DATE_KEYWORDS) && !hasAny(k, DATE_HINT_WORDS)) {
                return false;   // date 规则无词佐证 -> 误标, 放行
            }
        }
        return true;
    }

    // ==================================================================
    // ④ 入口：在目标进程内调用一次（v14 起由 handleLoadPackage 触发，
    //    借 LSPosed 作用域让 SP 拦截只作用于被勾选的 App，不再全系统生效）。
    // ==================================================================
    private static volatile boolean hookedFlag = false;

    public static void hook() {
        // 同进程只挂一次，避免 handleLoadPackage 对同一进程重复触发导致双注入
        if (hookedFlag) return;
        hookedFlag = true;
        try {
            final Class<?> sp = Class.forName("android.app.SharedPreferencesImpl");
            hookBoolean(sp);
            hookInt(sp);
            hookFloat(sp);
            hookLong(sp);
            hookString(sp);
            hookStringSet(sp);
            XposedBridge.log(TAG + " 全兼容自动VIP已挂载(SP多语义 + 到期统一2099 + 观测学习) @ " + currentPkg());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂载失败: " + t);
        }
    }

    // ==================================================================
    // ⑤ 每个 getXxx 的注入策略
    // ==================================================================
    private static void hookBoolean(final Class<?> sp) {
        hookScalar(sp, "getBoolean", new Injector() {
            @Override public Object val(String key) {
                note(key, "getBoolean", SHAPE_BOOL);
                return VALUE_BOOL;
            }
        });
    }

    private static void hookFloat(final Class<?> sp) {
        hookScalar(sp, "getFloat", new Injector() {
            @Override public Object val(String key) {
                note(key, "getFloat", SHAPE_INT); // float 无法表达会员档，归为数值型
                return VALUE_FLOAT;
            }
        });
    }

    private static void hookLong(final Class<?> sp) {
        // Long 命中（付费上下文里几乎必为“到期时间戳”），无论是否日期语义都统一回 2099-01-01
        hookScalar(sp, "getLong", new Injector() {
            @Override public Object val(String key) {
                note(key, "getLong", SHAPE_DATE);
                return FAR_DATE_MS;
            }
        });
    }

    private static void hookInt(final Class<?> sp) {
        hookScalar(sp, "getInt", new Injector() {
            @Override public Object val(String key) {
                boolean date = isDateKey(key);
                note(key, "getInt", date ? SHAPE_DATE : SHAPE_INT);
                return date ? Integer.valueOf(VALUE_INT_DAYS) : Integer.valueOf(VALUE_INT);
            }
        });
    }

    private static void hookString(final Class<?> sp) {
        try {
            Method m = findMethod(sp, "getString");
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    scanGate();
                    String key = (String) param.args[0];
                    if (!hit(key)) return;
                    Object def = param.args[1];
                    String d = def == null ? null : def.toString();
                    Object val = stringValueWithDefault(key, d);
                    note(key, "getString", guessStringShape(key, d));
                    param.setResult(val);
                    logOnce(key, "getString", def, val);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /**
     * getString 的取值策略（默认值 def 也参与判定）。
     */
    private static String stringValueWithDefault(String key, String def) {
        if (isDateKey(key)) {
            if (def != null) {
                String d = def.trim();
                if (isNumeric(d)) {
                    return d.length() >= 12 ? FAR_DATE_STR_MS : FAR_DATE_STR_SEC;
                }
            }
            return FAR_DATE_STR;
        }
        // 非日期键：默认值已是“已解锁/开”语义就保留（不改成 premium 以免适得其反）
        if (def != null) {
            String d = def.trim().toLowerCase();
            if ("true".equals(d) || "1".equals(d) || "yes".equals(d)
                    || "on".equals(d) || "enabled".equals(d) || "是".equals(d)) {
                return def;
            }
        }
        return VALUE_STRING_PREMIUM;
    }

    private static String guessStringShape(String key, String def) {
        if (isDateKey(key)) return SHAPE_DATE;
        if (def != null) {
            String d = def.trim();
            if ("true".equalsIgnoreCase(d) || "1".equals(d)) return SHAPE_BOOL;
            if (isNumeric(d)) return SHAPE_INT;
        }
        return SHAPE_STR_PREMIUM;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 通用标量钩子（boolean/int/long/float）
    // ------------------------------------------------------------------
    private interface Injector {
        Object val(String key);
    }

    private static void hookScalar(final Class<?> sp, final String method, final Injector inj) {
        try {
            Method m = findMethod(sp, method);
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    scanGate();                          // 任何读取都尝试触发一次扫描
                    String key = (String) param.args[0];
                    if (!hit(key)) return;
                    Object def = param.args[1];
                    Object val = inj.val(key);
                    param.setResult(val);
                    logOnce(key, method, def, val);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            for (Method mm : cls.getDeclaredMethods()) {
                if (mm.getName().equals(name)
                        && mm.getParameterTypes().length == 2
                        && mm.getParameterTypes()[0] == String.class) {
                    return mm;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // getStringSet 单独处理（返回 Set<String>）
    private static void hookStringSet(final Class<?> sp) {
        try {
            Method m = null;
            for (Method mm : sp.getDeclaredMethods()) {
                if ("getStringSet".equals(mm.getName())) { m = mm; break; }
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    scanGate();
                    String key = (String) param.args[0];
                    if (!hit(key)) return;
                    note(key, "getStringSet", SHAPE_STR_PREMIUM);
                    Set<String> val = new HashSet<>();
                    val.add("premium");
                    val.add("vip");
                    val.add("pro");
                    param.setResult(val);
                    logOnce(key, "getStringSet", null, val);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** 每个 key 首次命中打一条日志，避免刷屏。 */
    private static void logOnce(String key, String method, Object def, Object val) {
        String sig = key + "::" + method + "  def=" + def + " -> " + val;
        if (logged.add(sig)) {
            XposedBridge.log(TAG + " 命中(自动赋值) @ " + currentPkg() + "  " + sig);
        }
    }

    // ==================================================================
    // ⑥ 观测学习实现
    // ==================================================================

    /**
     * 进程“开始读 SP”时无条件调用（不限命中）：
     * 安排一次后台任务：
     *   1) 总是先把该包已持久化的学习规则(hkrules.txt)热加载进内存 -> 回灌直接用；
     *   2) 只有当 hkrules.txt【不存在】(即还没做过首次观测) 时才扫 shared_prefs 并写记录；
     *      若已存在 -> 跳过重扫/重写，避免每次启动重复扫描 XML 造成性能开销与卡顿。
     * 这正是“只观测第一次”的关键：首次跑完三段式闭环后，后续启动只读规则回灌。
     */
    private static void scanGate() {
        if (!ENABLE_LEARN) return;
        if (scanScheduled) return;                 // 本进程安排过一次即可
        try {
            final String pkg = currentPkg();
            if (pkg == null || "?".equals(pkg)) return;
            scanScheduled = true;                  // 之后所有读取直接短路
            if (scannedPkg.add(pkg)) {             // 每个包只处理一次
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            loadPersistedRules(pkg);          // 有规则就回灌（无条件，很轻）
                            File rf = new File(uvipDir(pkg), FN_RULES);
                            if (rf.isFile()) {
                                // 已完成过首次观测 -> 不再重复三段式闭环
                                XposedBridge.log(TAG + " @ " + pkg + " 观测文件已存在, 跳过重扫(直接规则回灌)");
                                return;
                            }
                            scanSharedPrefs(pkg);             // 首次观测：扫 + 写 hkrecords/hkrules
                        } catch (Throwable ignore) {}
                    }
                }, "uvip-scan");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 从 /data/data/&lt;pkg&gt;/files/uvip/hkrules.txt 热加载上次学习到的规则
     * （格式每行：KEY&lt;TAB&gt;shape）。使规则在进程重启后仍生效，无需再次运行才学会。
     */
    private static void loadPersistedRules(String pkg) {
        try {
            File f = new File(uvipDir(pkg), FN_RULES);
            if (!f.isFile()) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String shape = line.substring(tab + 1).trim();
                if (!key.isEmpty() && !shape.isEmpty()) {
                    learnedRule.put(pkg + "\u0001" + key, shape);
                }
            }
            r.close();
        } catch (Throwable ignore) {
        }
    }

    /**
     * L1：扫描目标 App 自己的 /data/data/&lt;pkg&gt;/shared_prefs/*.xml，
     * 把所有“名字像会员 / 值带日期 / 值像已解锁”的条目抓出来：
     *   - 把确有会员/日期形态的 key 写入 learnedRule（后续即使词表没覆盖也能命中）；
     *   - 生成一份人类可读的 records 文件供查看。
     * 文件写入该 App 的 /data/data/&lt;pkg&gt;/files/uvip/。
     */
    private static void scanSharedPrefs(String pkg) {
        File dir = new File("/data/data/" + pkg + "/shared_prefs");
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# UVip 观测记录 @ ").append(pkg)
          .append("\n# 生成时间: ").append(System.currentTimeMillis())
          .append("\n# 说明: 命中/日期形态的 SP key。可据此人工挑选真正控制 VIP 的字段。\n\n");
        int hitCount = 0;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".xml")) continue;
            // 解析简单 XML: <string name="..">val</string> 等；用行扫即可，不引 DOM 依赖
            String content = readFile(f);
            if (content == null) continue;
            sb.append("## 文件: ").append(name).append("\n");
            for (String line : content.split("\n")) {
                String k = extractAttr(line, "name");
                if (k == null) continue;
                String v = extractValue(line);
                if (v == null) v = "";
                String kk = k.toLowerCase();
                // 命中信号（目的：发现词表没覆盖的 VIP key，同时避免误抓普通字段）：
                //  (a) key 名本身像会员/日期 —— 命中；
                //  (b) key 名含会员/日期提示词 且 值像 epoch / 人类可读日期 —— 才抓。
                //  ☆ 修复(真机反馈): 纯 epoch 值【不再】独立命中 —— 否则 DCloud 的
                //    last_app_modify_date(防二次打包的 App 修改时间戳) 这类业务时间戳
                //    会被误当"到期"注入 2099, 造成误伤。真正的会员到期 key 通常名字里
                //    也带 expire/time/date 词, 或运行时经 getLong 语境命中(见 note)。
                boolean strongName = hit(kk);
                boolean epochVal   = looksLikeEpoch(v);
                boolean nameHint   = hasAny(kk, DATE_KEYWORDS)
                        || hasAny(kk, PAID_KEYWORDS)
                        || hasAny(kk, TIER_KEYWORDS)
                        || hasAny(kk, UNLOCK_KEYWORDS)
                        || hasAny(kk, ADS_KEYWORDS);
                boolean member = strongName || (nameHint && (epochVal || looksLikeDateVal(v)));
                if (member) {
                    // 确认形态，写入规则：若名字像日期或值像时间戳/日期 -> date；布尔型 xml true/false -> bool
                    String shape = shapeFrom(k, v);
                    learnedRule.put(pkg + "\u0001" + k, shape);
                    sb.append("  KEY ").append(k)
                      .append("  VAL ").append(v)
                      .append("  SHAPE ").append(shape).append("\n");
                    hitCount++;
                }
            }
            sb.append("\n");
        }
        // 落盘 hkrecords.txt + 持久化规则 hkrules.txt（下次进程启动 loadPersistedRules 读回；
        // 存在 hkrules.txt 即视为“已首次观测”，之后不再重扫）
        if (hitCount > 0 || ENABLE_LEARN) {
            File outDir = uvipDir(pkg);
            writeAppend(new File(outDir, FN_RECORDS), sb.toString());
            StringBuilder rules = new StringBuilder("# UVip 学习规则 @ ").append(pkg)
                    .append(" (KEY\\tshape; 可手工增删) \n");
            synchronized (learnedRule) {
                for (Map.Entry<String, String> e : learnedRule.entrySet()) {
                    String k2 = e.getKey();
                    if (k2.startsWith(pkg + "\u0001")) {
                        rules.append(k2.substring(pkg.length() + 1)).append('\t')
                             .append(e.getValue()).append('\n');
                    }
                }
            }
            writeAppend(new File(outDir, FN_RULES), rules.toString());
        }
        if (hitCount > 0) {
            XposedBridge.log(TAG + " 磁盘扫描 @ " + pkg + " 发现 " + hitCount
                    + " 个会员/日期 key(已入学习规则, 首次观测完成)");
        }
    }

    /** 判断该 key 是否应记为 date 形态。 */
    private static String shapeFrom(String key, String val) {
        String k = key.toLowerCase();
        if (hasAny(k, DATE_KEYWORDS)) return SHAPE_DATE;
        // 值像 epoch 毫秒(12-13位)或秒(10位)或 yyyy-MM-dd / ISO
        String v = val == null ? "" : val.trim();
        if (looksLikeEpoch(v)) return SHAPE_DATE;
        if (v.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) return SHAPE_DATE;
        String vl = v.toLowerCase();
        if ("true".equals(vl) || "false".equals(vl)) return SHAPE_BOOL;
        if (isNumeric(v)) return SHAPE_INT;
        // 名字含强会员词 -> premium 兜底
        if (hasAny(k, PAID_KEYWORDS) || hasAny(k, TIER_KEYWORDS) || hasAny(k, UNLOCK_KEYWORDS)) {
            return SHAPE_STR_PREMIUM;
        }
        return SHAPE_STR_PREMIUM;
    }

    private static boolean looksLikeEpoch(String v) {
        if (!isNumeric(v)) return false;
        int len = v.length();
        return len >= 10 && len <= 13;
    }

    private static boolean looksLikeDateVal(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) return true;
        return false;
    }

    /** 运行时命中观测：把 key/方法/形态 记入内存规则（保证本进程命中）；仅当
     *  尚未生成 hkrules.txt（即首次学习窗口内）才追加 hkhits.log —— 已学过则
     *  不再写盘，符合“只观测第一次”。 */
    private static void note(String key, String method, String shape) {
        if (!ENABLE_LEARN) return;
        try {
            String pkg = currentPkg();
            if (pkg == null || "?".equals(pkg)) return;
            String sig = pkg + "\u0001" + key + "\u0001" + method;
            if (!notedSig.add(sig)) return;
            // 该 key 已被词表/运行命中 -> 确认其为会员字段，进入内存规则（若尚无更精确形态）
            String rule = learnedRule.get(pkg + "\u0001" + key);
            if (rule == null) {
                learnedRule.put(pkg + "\u0001" + key, shape);
            }
            // 只写 hkhits.log：文件已存在说明首次学习已完成，跳过（避免反复写盘）
            File rf = new File(uvipDir(pkg), FN_RULES);
            if (!rf.isFile()) {
                File f = new File(uvipDir(pkg), FN_HITS);
                appendLine(f, key + "\t" + method + "\t" + shape);
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // 文件小工具（仅 java.io，无 Android 依赖，保证编译安全）
    // ------------------------------------------------------------------
    private static String readFile(File f) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeAppend(File f, String content) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter w = new FileWriter(f, false);
            w.write(content);
            w.close();
        } catch (Throwable ignore) {
        }
    }

    private static void appendLine(File f, String line) {
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.write("\n");
            w.close();
        } catch (Throwable ignore) {
        }
    }

    /** 从一行 xml 提取 name=".."（可含转义，宽松处理）。 */
    private static String extractAttr(String line, String attr) {
        int idx = line.indexOf(attr + "=");
        if (idx < 0) return null;
        int q1 = line.indexOf('"', idx);
        if (q1 < 0) return null;
        int q2 = line.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return line.substring(q1 + 1, q2);
    }

    /** 从 "<string name='..'>VALUE</string>" 提取 VALUE。 */
    private static String extractValue(String line) {
        int gt = line.indexOf('>');
        int lt = line.lastIndexOf('<');
        if (gt < 0 || lt < 0 || lt <= gt) return null;
        String inner = line.substring(gt + 1, lt);
        return inner.isEmpty() ? "" : inner;
    }
}
