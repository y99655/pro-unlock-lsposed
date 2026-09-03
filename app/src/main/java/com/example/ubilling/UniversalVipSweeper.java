package com.example.ubilling;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 自动 VIP 拦截器（SharedPreferences 方案）—— 对任意 App 通用、混淆无关。
 *
 * ============================================================================
 * 设计依据（为什么从 SharedPreferences 入手最能“通杀”）：
 *   很多 App 把“是否已解锁 / 会员等级 / PRO / 去广告”等付费态持久化到
 *   SharedPreferences（简称 SP）。SP 是 Android SDK 类，App 自身代码可以混淆，
 *   【但 android.app.SharedPreferencesImpl 及它的 getString/getBoolean/getInt/
 *   getLong/getFloat/getStringSet 方法永不混淆】。
 *
 *   更妙的是：App 调哪个 getXxx，就决定了它想要什么类型——
 *     getBoolean(...)  -> 想要 boolean，塞 true 最可能解锁；
 *     getInt(...)      -> 想要整数，塞一个“像已购”的值；
 *     getLong(...)     -> 想要到期时间戳，塞未来时间；
 *     getString(...)   -> 想要档位字符串，塞“premium/vip”这类候选。
 *   因此“自动判断值是布尔/数字/还是英文字母并按型赋值”这一步被方法名天然解决，
 *   无需去猜每个字段的声明类型。
 *
 *   命中判断：键名（key）里含 vip / premium / paid / unlock / member 等付费关键词。
 *   键名通常是给业务逻辑存取的“业务词”，即使类名被混淆、SP 的 key 大多是明文，
 *   因此关键词匹配对“本地判断型” App 命中率很高。
 *
 * ============================================================================
 * 诚实边界（务必知悉）：
 *   ① 只对“付费态存在 SharedPreferences、且 App 信任本地读取结果”的 App 有效。
 *   ② 若 VIP 状态是服务器下发的 entitlement、或 App 每次启动从网络拉取后
 *      用返回值覆盖 SP，则这类拦截仍会“读一次被盖一次”，不能保证解锁。
 *   ③ 命中即改写返回值，不改动磁盘文件 —— 退出后原值仍在，是纯运行时注入。
 *   ④ 本类在 zygote 期挂载一次，对系统里所有进程生效；无相关键的 App 读取
 *      不命中正则、原样返回，几乎零副作用。
 * ============================================================================
 */
public class UniversalVipSweeper {

    private static final String TAG = "[UVip]";

    // ------------------------------------------------------------------
    // ① 关键词表（转小写后子串匹配）。可自行增删。
    //    “pro” 因为误伤太广（profile/progress/prompt…），单独走排除逻辑，
    //    不放在这个数组里，见 matchPro()。
    // ------------------------------------------------------------------
    public static final String[] STRONG_KEYWORDS = {
        "vip", "premium", "paid", "unlock", "unlocked",
        "license", "licence", "entitle", "member",
        "subscrib", "subscription", "gold", "activate", "activated",
        "pro_"                       // 显式带下划线的 pro，如 pro_enabled
    };

    /** pro 的常见误伤子串：命中这些且无更强关键词时，不再当成付费态。 */
    public static final String[] PRO_FALSE_POSITIVES = {
        "profile", "progress", "promote", "promotion", "prompt",
        "property", "protection", "protocol", "processor", "program",
        "product", "professional", "proof", "probe", "propagate",
        "produce", "provider", "promo"
    };

    // ------------------------------------------------------------------
    // ② 命中后的注入值（可自行调整）。
    //    由被调 getXxx 的方法名决定返回类型与取值策略。
    // ------------------------------------------------------------------
    private static final boolean VALUE_BOOL   = true;          // getBoolean
    private static final int      VALUE_INT    = 1;            // getInt   （多数判 >0 即解锁）
    private static final float    VALUE_FLOAT  = 1.0f;         // getFloat
    private static final String   VALUE_STRING = "premium";    // getString
    private static final long     VALUE_MS_FUTURE;             // getLong  未来到期时间

    static {
        // 距今约 +30 年，保证“到期时间戳 > now”的判断恒成立
        VALUE_MS_FUTURE = System.currentTimeMillis() + 30L * 365L * 24L * 60L * 60L * 1000L;
    }

    /** 进程级已打印提示的去重，避免每个 key 每次读都刷屏。 */
    private static final Set<String> logged = java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * 仅供日志标注当前是哪个 App 在读写。
     * ActivityThread 是 @hide 类，标准 android.jar 不含，故用反射调用 currentPackageName()，
     * 避免编译期引用隐藏 API。
     */
    private static String currentPkg() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentPackageName");
            Object r = m.invoke(null);
            return r == null ? "?" : r.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 判定一个 key 是否命中“付费/会员”语义。 */
    static boolean hit(String rawKey) {
        if (rawKey == null) return false;
        String k = rawKey.toLowerCase();
        for (String w : STRONG_KEYWORDS) {
            if (k.contains(w)) return true;      // 强命中，直接算
        }
        // 没有更强关键词时，才单独判 pro（需避开误伤词）
        if (k.contains("pro")) return !hasFalsePositive(k);
        return false;
    }

    private static boolean hasFalsePositive(String k) {
        for (String fp : PRO_FALSE_POSITIVES) {
            if (k.contains(fp)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 入口：在 zygote 期调用一次，对全进程生效。
    // ------------------------------------------------------------------
    public static void hook() {
        try {
            // SharedPreferencesImpl 是 boot classpath 里的隐藏类，用 Class.forName 加载
            // （运行时不校验 @hide，仅编译期不直接引用类型即可）。boot 类全局唯一，
            // 无论哪个 classloader 加载都是同一 Class 对象。
            final Class<?> sp = Class.forName("android.app.SharedPreferencesImpl");
            hookGet(sp, "getBoolean", boolean.class, VALUE_BOOL);
            hookGet(sp, "getInt", int.class, VALUE_INT);
            hookGet(sp, "getFloat", float.class, VALUE_FLOAT);
            hookGet(sp, "getLong", long.class, VALUE_MS_FUTURE);
            hookGet(sp, "getString", String.class, VALUE_STRING);
            hookGetSet(sp);
            XposedBridge.log(TAG + " 自动 VIP 拦截已挂载(SharedPreferences 方案)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂载失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 对 getString / getBoolean / getInt / getLong / getFloat 统一挂载。
    // 方法名决定了返回类型 -> 自动按类型塞对应注入值。
    // 命中逻辑相同：key 命中即把返回值替换为注入值（保留 defValue 供日志）。
    // ------------------------------------------------------------------
    private static void hookGet(final Class<?> sp, final String method,
                                final Class<?> paramType, final Object injectVal) {
        try {
            final Method m = findMethod(sp, method, String.class, paramType);
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (!hit(key)) return;                       // 不相关键：原样放行
                    Object def = param.args[1];                  // 原默认值（用于日志对比）
                    param.setResult(injectVal);                  // 改写返回
                    logOnce(key, method, def, injectVal);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    // getStringSet 单独处理（返回 Set<String>）
    private static void hookGetSet(final Class<?> sp) {
        try {
            Method m = null;
            for (Method mm : sp.getDeclaredMethods()) {
                if ("getStringSet".equals(mm.getName())) { m = mm; break; }
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (!hit(key)) return;
                    Set<String> val = new HashSet<>();
                    val.add(VALUE_STRING);
                    val.add("vip");
                    val.add("pro");
                    param.setResult(val);
                    logOnce(key, "getStringSet", null, val);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... sig) {
        try {
            Method m = c.getDeclaredMethod(name, sig);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 每个 key 首次命中时打一条日志，避免刷屏。 */
    private static void logOnce(String key, String method, Object def, Object val) {
        String sig = key + "::" + method + "  def=" + def + " -> " + val;
        if (logged.add(sig)) {
            XposedBridge.log(TAG + " 命中(自动赋值) @ " + currentPkg() + "  " + sig);
        }
    }
}
