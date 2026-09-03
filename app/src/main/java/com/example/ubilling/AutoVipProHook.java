package com.example.ubilling;

import java.lang.reflect.Field;
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
 *
 * ============================================================================
 * v16 扩展【类名 + 原值类别】盲扫（用户需求：遍历搜索类名含 vip/pro 等的类，
 * 然后按"成员原有值类别"hook）：
 *
 *   仅靠"方法名"扫会漏掉两类真会员态：
 *     A) 会员状态存放在一个【类名带 vip/member/premium】的对象里(如 VipInfo/
 *        MemberState/UserVip)，用普通字段(getter 名本身不含 vip/pro 词)保存：
 *        isMember、level=3、expireTs、tier="normal"。方法名扫抓不到这些 getter
 *        (core 无 vip/member 词)。
 *     B) 直接以【字段】存、代码不经 getter 就读(如 static boolean isVip / 单例对象
 *        的字段)。Xposed 无法拦截裸字段读，只能改写存储值。
 *
 *   v16 因此在方法名扫之外，新增一条【类名门禁】深扫：
 *     - 类全名(小写)命中 CLS_INCLUDE( vip/svip/premium/entitle/platinum/
 *       memberinfo/membership/membercenter/… ) 且未命中 CLS_EXCLUDE
 *       (provider/protocol/processor/proxy/import/…) 的，视为"会员信息类"；
 *     - 在该类内：
 *        ① 放宽 getter 方法名门禁 —— 不必自带 vip/pro 词，只要类型符合且方法名
 *           不是明显工具布尔，即可候选；注入值按【返回类型/名字形态】给类别值。
 *        ② 扫其字段：static 非 final 的会员字段(boolean/int/long/String)——
 *           反射读【原有值】并按类别改写存储值为开通态(布尔 true / 等级高值 /
 *           long 到期 2099 / string 档位 premium)；实例字段无 getter 则仅观测
 *           提示(需单例才能改写)。
 *     - 类别判定(与 UVip 对 SP 的"类型自适应"一致)：
 *         boolean/Boolean        -> true
 *         int/Integer  名含 level/grade/tier/lv/type -> 高值(如 8)；否则 1
 *         long/Long    名含 expire/end/valid/date/ts 或任意(会员上下文) -> 2099-01-01
 *         String       名含 expire/valid/date -> "2099-01-01"；含 level/tier/type
 *                       -> 原值已是 premium/vip/pro 则保留，否则 "premium"
 *     - 默认 LOG_ONLY=true：只打 [UAuto] 观测(类·成员·类别·原值·拟注入)，不改值。
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

    /** 纯时间/工具布尔 getter 极易与"解锁位"混淆 —— 命中即放行，绝不注入。 */
    private static final String[] HARD_PASS = {
        "isempty", "isnull", "isnetwork", "isconnected", "isvisible", "isvalid",
        "isenabled", "isalive", "isready", "isdone", "isrunning", "isstarted",
        "isshown", "isdisplayed", "issupport", "ischecked", "isselected",
        "isfirst", "islast", "islogin", "islogined", "isinit", "isloaded"
    };

    // ==================================================================
    // v16 类名门禁：遍历"类名像会员"的类，按成员原值类别 hook
    // ==================================================================
    /** 类全名(小写)含任一下列子串 -> 视为会员信息/状态类，深扫字段与 getter。 */
    private static final String[] CLS_INCLUDE = {
        "vip", "svip", "premium", "entitle", "entitlement", "platinum",
        "memberinfo", "membervo", "membership", "membercenter",
        "membermanager", "memberstate", "membermodel", "vipext", "goldvip"
    };
    /** 类名命中即排除(纯 SDK/工具/非会员对象)。 */
    private static final String[] CLS_EXCLUDE = {
        "provider", "protocol", "processor", "improve", "promote", "proof",
        "probe", "property", "proxy", "promise", "proguard", "import",
        "android.", "java.", "kotlin.", "retrofit", "okhttp", "gson",
        "memberlistadapter", "memberlistitem", "listmember", "teammember"
    };

    /** 到期/时间戳语义(类别=日期，注入 2099)。用较长/精确词避免误吞 send/trend/invalid。 */
    private static final String[] CAT_DATE_HINT = {
        "expire", "expiry", "expiration", "deadline", "duedate", "endtime",
        "enddate", "endat", "expiresat", "validuntil", "validto", "validity",
        "timestamp", "createtime", "endts", "overdate", "vipexpire", "memberexpire"
    };
    /** 等级/档位语义(类别=等级 int/档位 string)。 */
    private static final String[] CAT_TIER_HINT = {
        "level", "grade", "tier", "viptype", "viplevel", "usertype",
        "memberlevel", "membertype", "rank"
    };

    private static final long FAR_MS = 4070908800000L;   // 2099-01-01 00:00:00 UTC
    private static final String FAR_STR = "2099-01-01";

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
        final boolean vipCls = isVipLikeClass(c);
        // v16: 会员信息类先扫一遍静态字段(按原值类别改写存储态)
        if (vipCls) {
            try { scanFields(c); } catch (Throwable ignore) {}
        }
        Method[] ms;
        try { ms = c.getDeclaredMethods(); } catch (Throwable t) { return; }
        for (Method m : ms) {
            try {
                judge(c, m, vipCls);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 对单个方法做门禁判断。vipCls=方法所在类是否为"会员信息类"(类名门禁命中)。
     *  vipCls 时放宽"方法名必须自带 vip/pro 词"——类名已提供会员语境，只要类型/
     *  语义对、非工具布尔即可候选。 */
    private static void judge(Class<?> c, Method m, boolean vipCls) {
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
            // vipCls(类名已是会员类)时放宽：布尔 getter 只要 core 带任意会员/开通词
            //   (vip/member/premium/pro/gold/active/enable/license/paid/unlock 等)即可候选；
            // 否则须精确整词或强词结尾(极安全)。
            boolean strong = inWords(core, EXACT_BOOL) || endsWithWord(core, BOOL_SUFFIX);
            boolean inVipContext = vipCls && (core.contains("vip") || core.contains("member")
                    || core.contains("premium") || core.contains("pro")
                    || core.contains("gold") || core.contains("active")
                    || core.contains("license") || core.contains("paid")
                    || core.contains("unlock") || core.contains("enable"));
            if (!strong && !inVipContext) return;

            candBool++;
            if (LOG_ONLY) {
                XposedBridge.log(TAG + " [观测] boolean解锁位: " + c.getName() + "." + name
                        + "() -> 本可注入true (core=" + core + (vipCls ? ", 类名会员语境)" : ")"));
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
            boolean hasVipWord = core.contains("vip") || core.contains("member")
                    || core.contains("premium") || core.contains("pro")
                    || core.contains("gold") || core.contains("svip");
            // 档位/日期 getter：方法名(去前缀)需以 档位词 结尾，或命中日期词。
            boolean tierish = endsWithWord(core, TIER_SUFFIX);
            boolean dateish = hasAnyCat(core, CAT_DATE_HINT);
            // 需满足：本身带会员词，或所在类为会员类(vipCls)——否则不把普通 level 当会员等级
            if ((!vipCls && !hasVipWord) || (!tierish && !dateish)) return;
            // 在会员语境里，日期/到期 getter 与档位 getter 都算候选
            candTier++;
            if (LOG_ONLY) {
                XposedBridge.log(TAG + " [观测] " + (dateish ? "到期getter" : "档位getter")
                        + ": " + c.getName() + "." + name
                        + "() : " + ret.getSimpleName()
                        + (dateish ? " -> 本可注入2099" : " -> 本可注入顶级档位")
                        + " (core=" + core + (vipCls ? ", 类名会员语境)" : ")"));
                return;
            }
            String want = catValue(ret, dateish, core);
            hookTo(c, m, want, dateish ? "到期getter" : "档位getter");
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
    // v16：类名门禁 + 原值类别判定 + 字段盲扫
    // ==================================================================
    /** 类全名(小写)是否像"会员信息/状态类"。 */
    private static boolean isVipLikeClass(Class<?> c) {
        String n = c.getName().toLowerCase(Locale.ROOT);
        for (String x : CLS_EXCLUDE) if (n.contains(x)) return false;
        for (String w : CLS_INCLUDE) if (n.contains(w)) return true;
        return false;
    }

    /** 小写串是否命中任一类别提示词。 */
    private static boolean hasAnyCat(String core, String[] arr) {
        for (String w : arr) if (core.contains(w)) return true;
        return false;
    }

    /**
     * 按【返回类型 + 名字形态】给类别注入值（与 UVip 对 SP 的"类型自适应"一致）：
     *   long/Long  (会员语境, 几乎必为到期戳)  -> 2099-01-01(毫秒)
     *   String 且日期形态(expire/end/valid/date) -> "2099-01-01"
     *   String 且档位形态(level/tier/type)     -> 见上方顶级档位
     *   int/Integer                            -> 日期形态给剩余天数大数, 否则高等级
     */
    private static String catValue(Class<?> ret, boolean dateish, String core) {
        if (ret == long.class || ret == Long.class) return String.valueOf(FAR_MS);
        if (ret == String.class) return dateish ? FAR_STR : "premium";
        // int 日期形态给"剩余天数≈恒未过期"; 否则给高等级档位
        return dateish ? "99999" : "8";
    }

    /**
     * 扫会员信息类的字段：按【原有值/声明类别】决定改写值。
     *  - static 非 final 会员字段：反射读原值 -> 类别 -> 直接改写存储值为开通态
     *    (代码若裸读该静态字段即可看到开通值；进程级只做一次)。
     *  - 实例字段：无实例无法直接改写(Xposed 拦不了裸字段读)；仅观测提示，若其后有
     *    同名 getter 会由 judge 走方法钩子覆盖。
     */
    private static final Set<String> fieldTouched = java.util.Collections.synchronizedSet(new HashSet<String>());
    private static void scanFields(Class<?> c) {
        Field[] fs;
        try { fs = c.getDeclaredFields(); } catch (Throwable t) { return; }
        String cn = c.getName();
        boolean strongCls = isStrongMemberClass(c);
        for (Field f : fs) {
            try {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) && Modifier.isFinal(mod)) continue; // final 常量不碰
                if (Modifier.isTransient(mod) || f.isSynthetic()) continue;
                Class<?> t = f.getType();
                boolean ok = t == boolean.class || t == Boolean.class
                        || t == int.class || t == Integer.class
                        || t == long.class || t == Long.class
                        || t == String.class;
                if (!ok) continue;
                String fn = f.getName().toLowerCase(Locale.ROOT);
                boolean hp = false;
                for (String h : HARD_PASS) if (fn.contains(h)) { hp = true; break; }
                if (hp) continue;                              // 工具/时间布尔名 -> 跳过
                boolean nameMem = fieldMemName(fn);
                // 字段名无会员语义 且 类也非强会员类 -> 跳过(避免处理 id/name 等普通字段)
                if (!nameMem && !strongCls) continue;
                boolean dateish = hasAnyCat(fn, CAT_DATE_HINT);
                boolean tierish = hasAnyCat(fn, CAT_TIER_HINT);
                boolean isBool = t == boolean.class || t == Boolean.class;
                // 布尔字段 / 日期字段 / 等级字段 / 字段名带会员词 四类才处理
                if (!isBool && !dateish && !tierish && !nameMem) continue;
                if (Modifier.isStatic(mod)) {
                    // 静态字段：尝试读原值再改写(仅一次)
                    f.setAccessible(true);
                    String sig = cn + "#" + f.getName();
                    Object orig;
                    try { orig = f.get(null); } catch (Throwable e) { orig = null; }
                    String want = fieldCatValue(t, dateish, tierish, orig);
                    if (LOG_ONLY) {
                        XposedBridge.log(TAG + " [观测] 静态字段 " + sig
                                + " : " + t.getSimpleName() + " cur=" + String.valueOf(orig)
                                + " -> 本可改写为 " + want);
                        continue;
                    }
                    if (!fieldTouched.add(sig)) continue;
                    setFieldValue(f, null, want);
                    XposedBridge.log(TAG + " 改写静态字段 " + sig + " : cur="
                            + String.valueOf(orig) + " -> " + want);
                } else {
                    // 实例字段(无实例)只观测
                    XposedBridge.log(TAG + " [观测] 实例字段 " + cn + "#" + f.getName()
                            + " : " + t.getSimpleName()
                            + " (无实例不改写; 若有同名 getter 走方法钩子)");
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** 字段名(小写)是否带会员/开通语义。 */
    private static boolean fieldMemName(String fn) {
        return fn.contains("vip") || fn.contains("member") || fn.contains("premium")
                || fn.contains("pro") || fn.contains("gold") || fn.contains("entitle")
                || fn.contains("license") || fn.contains("unlock") || fn.contains("paid")
                || fn.contains("svip") || fn.contains("platinum");
    }

    /** 是否为"强会员类"：类名含极强会员词(非仅 memberxxx 弱语境)。 */
    private static boolean isStrongMemberClass(Class<?> c) {
        String n = c.getName().toLowerCase(Locale.ROOT);
        return n.contains("vip") || n.contains("svip") || n.contains("premium")
                || n.contains("entitle") || n.contains("platinum") || n.contains("goldvip");
    }

    /** 字段原值 -> 目标值(按类别 + 原值形态)。 */
    private static String fieldCatValue(Class<?> t, boolean dateish, boolean tierish, Object orig) {
        if (t == boolean.class || t == Boolean.class) return "true";
        if (t == long.class || t == Long.class) return String.valueOf(FAR_MS); // 到期戳
        if (t == String.class) {
            if (dateish) return FAR_STR;
            if (orig != null) {
                String o = orig.toString().toLowerCase(Locale.ROOT);
                if (o.equals("premium") || o.equals("vip") || o.equals("pro")
                        || o.equals("true") || o.equals("1")) return orig.toString();
            }
            return "premium";
        }
        // int：日期语义给"剩余天数≈恒未过期"，否则高等级
        return dateish ? "99999" : "8";
    }

    private static void setFieldValue(Field f, Object inst, String want) {
        try {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == boolean.class || t == Boolean.class) f.setBoolean(inst, true);
            else if (t == int.class || t == Integer.class) f.setInt(inst, Integer.parseInt(want));
            else if (t == long.class || t == Long.class) f.setLong(inst, Long.parseLong(want));
            else f.set(inst, want);
        } catch (Throwable ignore) {
        }
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
