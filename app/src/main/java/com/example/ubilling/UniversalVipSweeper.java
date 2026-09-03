package com.example.ubilling;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 自动 VIP 全兼容拦截器（SharedPreferences 多语义方案）—— 对任意 App 通用、混淆无关。
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
 *   这样覆盖“布尔解锁、整型已购标记、Long 到期时间戳、String 档位、String 存日期、
 *   StringSet 权限集合、去广告开关”等常见形态。Google Play Billing 通道由
 *   UniversalBillingHook 单独覆盖，本类专注 SP 这一“本地判定”主战场。
 *
 * ============================================================================
 * 语义分类（关键词，转小写后子串匹配；PRO_ 裸 pro 单独排除误伤）：
 *   1) PAID   : vip/premium/paid/purchase/license/entitle/member/gold/plus/…
 *   2) TIER   : pro_/deluxe/ultimate/activated/active 等“升级档位”
 *   3) UNLOCK : unlock/unlocked/unlockfeature/…
 *   4) ADS    : remove_ads/no_ads/ad_free/adblock/disableads/…
 *   5) DATE   : expire/expiry/deadline/valid_until/end_time/end_date/purchase_date/…
 *               （命中它 => 判“未过期”优先，见 farDate 分支）
 *
 *   裸 “pro” 会误伤 profile/progress/product 等，故 PRO_FALSE_POSITIVES 命中时
 *   除非还有更强关键词，否则不判定为付费。
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
 * 日期统一规则（用户硬性要求）：
 *   凡是表达“到期 / 有效期 / 截止 / valid_until / 剩余时间”的读取，一律让 App
 *   认为已续费到 2099-01-01，从而“未过期”判断恒成立。SP 里可能用毫秒 Long、
 *   秒 Long、或字符串 "yyyy-MM-dd" 三种形式存到期，我们分别注入对应的 2099 形态。
 *
 * ============================================================================
 * 诚实边界（务必知悉）：
 *   ① 只对“付费态存在 SP、且 App 信任本地读取结果”的 App 有效；
 *   ② 服务器 entitlement / 每次启动从网络拉取后覆盖 SP 的 App 不保证；
 *   ③ 命中即改写内存返回值，不改磁盘文件 —— 纯运行时注入，重启后原值仍在；
 *   ④ 本类在 zygote 期挂载一次，对所有进程生效；无关 key 不命中、原样返回。
 *   ⑤ “深层字段/构造器激活位”这类需要按具体 App 逆向、且极易误伤业务对象
 *      （历史上曾把几何类 EArc 当 PRO 误杀导致功能损坏）的破解不做全兼容——
 *      只保留对任意 App 都安全的 SP + Billing 双通道。
 * ============================================================================
 */
public class UniversalVipSweeper {

    private static final String TAG = "[UVip]";

    // ==================================================================
    // ① 语义关键词表（转小写后子串匹配）。可自行增删调参。
    // ==================================================================
    /** 付费/会员/已购：出现即视为“收费门禁”。 */
    public static final String[] PAID_KEYWORDS = {
        "vip", "premium", "paid", "purchase", "purchased",
        "license", "licence", "entitle", "entitlement", "member",
        "subscrib", "subscription", "gold", "activate", "activated",
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

    /** 进程级去重，避免同一 key 每次读都刷屏。 */
    private static final Set<String> logged = java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * 仅供日志标注当前是哪个 App 在读写。ActivityThread 是 @hide 类，标准 android.jar
     * 不含，故用反射 currentPackageName()，避免编译期引用隐藏 API。
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

    // ------------------------------------------------------------------
    // 语义判定
    // ------------------------------------------------------------------
    /** 判定 key 是否命中任一词义（付费/档位/解锁/去广告/到期）。 */
    static boolean hit(String rawKey) {
        if (rawKey == null) return false;
        String k = rawKey.toLowerCase();
        if (hasAny(k, PAID_KEYWORDS)) return true;
        if (hasAny(k, TIER_KEYWORDS)) return true;
        if (hasAny(k, UNLOCK_KEYWORDS)) return true;
        if (hasAny(k, ADS_KEYWORDS)) return true;
        if (hasAny(k, DATE_KEYWORDS)) return true;
        // 裸 pro（如 "pro"/"ispro"/"provalue"）：仅当不含误伤词才算会员，否则 profile/progress 等会误伤
        if (k.contains("pro")) {
            return !hasAny(k, PRO_FALSE_POSITIVES);
        }
        return false;
    }

    /** 判定 key 是否带“到期/有效期”语义（决定是否走 2099 日期分支）。 */
    static boolean isDateKey(String rawKey) {
        if (rawKey == null) return false;
        String k = rawKey.toLowerCase();
        return hasAny(k, DATE_KEYWORDS);
    }

    private static boolean hasAny(String k, String[] arr) {
        for (String w : arr) if (k.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // 入口：zygote 期调用一次，对全进程生效。
    // ------------------------------------------------------------------
    public static void hook() {
        try {
            // SharedPreferencesImpl 是 boot classpath 的隐藏类，用 Class.forName 加载
            // （运行时不校验 @hide，仅编译期不直接引用类型即可）。boot 类全局唯一。
            final Class<?> sp = Class.forName("android.app.SharedPreferencesImpl");
            hookBoolean(sp);
            hookInt(sp);
            hookFloat(sp);
            hookLong(sp);
            hookString(sp);
            hookStringSet(sp);
            XposedBridge.log(TAG + " 全兼容自动VIP已挂载(SP多语义 + 到期统一2099-01-01)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂载失败: " + t);
        }
    }

    // ==================================================================
    // 每个 getXxx 的注入策略
    // ==================================================================
    private static void hookBoolean(final Class<?> sp) {
        hookScalar(sp, "getBoolean", new Injector() {
            @Override public Object val(String key) { return VALUE_BOOL; }
        });
    }

    private static void hookFloat(final Class<?> sp) {
        hookScalar(sp, "getFloat", new Injector() {
            @Override public Object val(String key) { return VALUE_FLOAT; }
        });
    }

    private static void hookLong(final Class<?> sp) {
        // Long 命中（付费上下文里几乎必为“到期时间戳”），无论是否日期语义都统一回 2099-01-01
        hookScalar(sp, "getLong", new Injector() {
            @Override public Object val(String key) { return FAR_DATE_MS; }
        });
    }

    private static void hookInt(final Class<?> sp) {
        hookScalar(sp, "getInt", new Injector() {
            @Override public Object val(String key) {
                // 日期/剩余类 int：返回“超大剩余”保证未过期；否则返回 1
                return isDateKey(key) ? Integer.valueOf(VALUE_INT_DAYS) : Integer.valueOf(VALUE_INT);
            }
        });
    }

    private static void hookString(final Class<?> sp) {
        try {
            Method m = null;
            for (Method mm : sp.getDeclaredMethods()) {
                if (mm.getName().equals("getString")
                        && mm.getParameterTypes().length == 2
                        && mm.getParameterTypes()[0] == String.class) {
                    m = mm;
                    break;
                }
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (!hit(key)) return;                 // 无关 key：原样放行
                    Object def = param.args[1];
                    Object val = stringValueWithDefault(key, def == null ? null : def.toString());
                    param.setResult(val);
                    logOnce(key, "getString", def, val);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /**
     * getString 的取值策略（默认值 def 也参与判定）：
     *   - 日期语义 key：默认值若是 epoch（纯数字）-> 注入对应 2099 数值字符串
     *                    （13 位左右按毫秒、其余按秒）；否则统一回 "2099-01-01"；
     *   - 非日期 key：默认值已是“已解锁/开”语义 -> 原样保留（防把 true 字符串改坏）；
     *                 否则回 "premium"（最常见的会员档位字符串）。
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

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 通用标量钩子（boolean/int/long/float）：key 命中 -> inj 算出注入值 -> setResult
    // ------------------------------------------------------------------
    private interface Injector {
        Object val(String key);
    }

    private static void hookScalar(final Class<?> sp, final String method, final Injector inj) {
        try {
            Method m = null;
            for (Method mm : sp.getDeclaredMethods()) {
                if (mm.getName().equals(method)
                        && mm.getParameterTypes().length == 2
                        && mm.getParameterTypes()[0] == String.class) {
                    m = mm;
                    break;
                }
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (!hit(key)) return;                 // 无关 key：原样放行
                    Object def = param.args[1];
                    Object val = inj.val(key);
                    param.setResult(val);
                    logOnce(key, method, def, val);
                }
            });
        } catch (Throwable ignore) {
        }
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
                    String key = (String) param.args[0];
                    if (!hit(key)) return;
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
}
