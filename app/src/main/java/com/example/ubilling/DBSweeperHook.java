package com.example.ubilling;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 【G】SQLite / DB 会员判定盲扫通道（DBSweeperHook）—— 覆盖“会员态存在本地
 * SQLite/Room 数据库表、判定时 SELECT 出来比”的 App。这是 UVip(SP) 覆盖不到、
 * 但大量国内 App 爱用的另一类本地持久化载体。
 *
 * ============================================================================
 * 为什么需要它（与既有通道的分工）：
 *   【B】UVip           只拦 SharedPreferences —— 覆盖 SP 型。
 *   【F】AutoVipProHook 扫内存中已加载类的方法/字段 —— 覆盖内存态。
 *   但很多 App 把 vip_flag / vip_expire_time / user_level 存进一张 SQLite 表，
 *   判定路径是 db.rawQuery(...).getInt/getLong/getString(...)。这类判定：
 *     - 不经 SharedPreferences（UVip 拦不到）；
 *     - 列名常在反射/Dao 里读取，成员可能不在“会员类名”里（AutoVipProHook 漏）。
 *   本通道 hook SQLite 读取出口，按【列名语义】把“会员/到期/等级”列的读取
 *   改写成开通态，等价于“把数据库里那张会员表的判断值直接改到放行”。
 *
 * ============================================================================
 * 实现思路（hook 通用出口，不碰具体业务类）：
 *   1. hook android.database.sqlite.SQLiteDatabase 的 rawQuery/query(...)，
 *      拿到返回的 Cursor 后登记其【列名 -> 会员语义】(WeakHashMap)。
 *   2. hook android.database.AbstractCursor 的 getString/getInt/getLong/
 *      getFloat/getColumnIndex —— SQLiteCursor 等实现都继承它，一个钩子覆盖
 *      几乎所有 Cursor。读取时查“本列名是否会员语义”，是则改写返回值。
 *   安全收窄：只改【布尔位】(vip/isvip/member/paid... -> true/1) 与【等级位】
 *     (level/grade/tier/type -> 高值8)；【到期列】因“存秒还是存毫秒”无法从
 *     声明推断，只观测打日志，不强注入（避免把 10 位秒误写成 13 位毫秒）。
 *
 * ============================================================================
 * 与其它通道一致的安全边界：
 *   默认 LOG_ONLY=true：只打 [UDB] 观测日志（命中列/原值/拟注入），不改任何值。
 *   确认误伤后可把 LOG_ONLY 置 false 重载做真实注入。仅用于你自己开发/拥有或
 *   明确获授权做安全评估的 App —— 请勿用于破解他人的收费服务。
 * ============================================================================
 */
public class DBSweeperHook {

    private static final String TAG = "[UDB]";

    /** ====== 观测/注入总开关 ======
     *  LOG_ONLY=true  -> 只观测：命中会员列打印 [UDB] 候选（含原值与拟注入），不改值。
     *  LOG_ONLY=false -> 真实改写命中列（布尔位/等级位）。 */
    private static final boolean LOG_ONLY = true;

    /** 仅对选定包名执行(调用方也 gate，双保险)；null 表示任意作用域进程。 */
    public static final String TARGET_PKG = null;

    /** 注入“顶级档位”用的 int 值（int 等级列）。 */
    private static final int TIER_HIGH = 8;

    /** 布尔解锁位列名（转小写后含任一即视为“是否会员/已付费”标志列）。 */
    private static final String[] BOOL_COL_HINT = {
        "vip", "svip", "isvip", "vipflag", "ismember", "member", "premium",
        "ispro", "pro", "paid", "ispaid", "entitle", "unlock", "license",
        "gold", "goldvip", "activated", "fullversion"
    };
    /** 等级/档位列名。 */
    private static final String[] TIER_COL_HINT = {
        "level", "grade", "tier", "viplevel", "viptype", "memberlevel",
        "membertype", "usertype", "userlevel", "rank", "viplv", "membergrade"
    };
    /** 到期列名（只观测不强注入，因秒/毫秒二义）。 */
    private static final String[] DATE_COL_HINT = {
        "expire", "expiry", "expiration", "deadline", "duedate", "endtime",
        "enddate", "validuntil", "validity", "expiretime", "vipexpire",
        "memberexpire", "expirets", "expiretimestamp", "endts"
    };
    /** 列名即使含会员词也绝不碰(误伤：时间戳/会话/版本等基建列)。 */
    private static final String[] HARD_PASS_COL = {
        "createtime", "updatetime", "updatetime", "addtime", "registertime",
        "lastlogin", "launchtime", "logtime", "sync", "heartbeat", "token",
        "session", "version", "apiversion", "build", "report", "probe", "debug"
    };

    // ==================================================================
    // 状态
    // ==================================================================
    private static final Set<String> donePkg = Collections.synchronizedSet(new HashSet<String>());
    /** 列名 -> 会员语义(0=未知 1=布尔位 2=等级位 3=到期列)，按列名字符串全局记忆。 */
    private static final Map<String, Integer> colSem = Collections.synchronizedMap(new java.util.HashMap<String, Integer>());
    /** cursor 对象 -> 其列名数组(弱引用防泄漏)。 */
    private static final Map<Object, WeakReference<String[]>> cursorCols =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<String[]>>());

    public static void hook(final ClassLoader cl, final String pkg) {
        if (cl == null || pkg == null) return;
        if (TARGET_PKG != null && !TARGET_PKG.equals(pkg)) return;
        if (!donePkg.add(pkg)) return;

        XposedBridge.log(TAG + " SQLite/DB 盲扫通道挂载 @ " + pkg
                + (LOG_ONLY ? " (LOG_ONLY=观测, 不改值)" : " (注入开启)"));
        try {
            hookQueryExports();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " query 出口挂载失败: " + t);
        }
        try {
            hookAbstractCursor();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Cursor 挂载失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 1) hook SQLiteDatabase.rawQuery / query：拿到 Cursor 并登记列名
    // ------------------------------------------------------------------
    private static void hookQueryExports() {
        Class<?> db;
        try {
            db = Class.forName("android.database.sqlite.SQLiteDatabase");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 未找到 SQLiteDatabase(进程可能无 DB 使用): " + t);
            return;
        }
        // rawQuery / query：凡返回 Cursor 的重载都在 after 登记列名（不挑签名）
        hookAnyCursorReturning(db, "rawQuery");
        hookAnyCursorReturning(db, "rawQueryWithFactory");
        hookAnyCursorReturning(db, "query");
        XposedBridge.log(TAG + " 挂钩 SQLiteDatabase rawQuery/query 出口");
    }

    private static void hookAnyCursorReturning(Class<?> db, String name) {
        try {
            List<Method> targets = new ArrayList<>();
            for (Method m : db.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!isCursor(m.getReturnType())) continue;
                targets.add(m);
            }
            if (targets.isEmpty()) {
                // rawQueryWithFactory 在很多 API 没有，静默
                return;
            }
            for (final Method m : targets) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object cs = param.getResult();
                            if (cs == null) return;
                            registerCursor(cs);
                        } catch (Throwable ignore) {
                        }
                    }
                });
            }
            XposedBridge.log(TAG + " 挂钩 " + db.getName() + "." + name + " (" + targets.size() + " 个重载)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂钩 " + name + " 失败: " + t);
        }
    }

    private static boolean isCursor(Class<?> ret) {
        try {
            Class<?> c = Class.forName("android.database.Cursor");
            return c.isAssignableFrom(ret);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 登记一个 Cursor：读它的列名，映射会员语义。 */
    private static void registerCursor(Object cursor) {
        try {
            if (cursorCols.containsKey(cursor)) return;
            String[] names = readColumnNames(cursor);
            if (names == null || names.length == 0) return;
            cursorCols.put(cursor, new WeakReference<String[]>(names));
            // 顺带按首个 SQL 的 from 表跳过系统表无需——列名层已过滤
        } catch (Throwable ignore) {
        }
    }

    private static String[] readColumnNames(Object cursor) {
        try {
            Method m = cursor.getClass().getMethod("getColumnNames");
            m.setAccessible(true);
            Object r = m.invoke(cursor);
            return (String[]) r;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 2) hook AbstractCursor 的读取方法：按列名语义改写
    // ------------------------------------------------------------------
    private static void hookAbstractCursor() {
        Class<?> ac;
        try {
            ac = Class.forName("android.database.AbstractCursor");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 未找到 AbstractCursor: " + t);
            return;
        }
        hookCursorRead(ac, "getString", int.class);
        hookCursorRead(ac, "getInt", int.class);
        hookCursorRead(ac, "getLong", int.class);
        XposedBridge.log(TAG + " Cursor 读取挂钩完成 @ AbstractCursor"
                + (LOG_ONLY ? " (观测)" : " (注入)"));
    }

    private static void hookCursorRead(final Class<?> owner, final String mname, Class<?>... pt) {
        try {
            Method m = owner.getDeclaredMethod(mname, pt);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object cursor = param.thisObject;
                        if (cursor == null) return;
                        Object idxObj = param.args[0];
                        if (!(idxObj instanceof Integer)) return;
                        int idx = (Integer) idxObj;
                        WeakReference<String[]> ref = cursorCols.get(cursor);
                        if (ref == null) return;
                        String[] names = ref.get();
                        if (names == null || idx < 0 || idx >= names.length) return;
                        String col = names[idx];
                        if (col == null) return;
                        int sem = classifyColumn(col);
                        if (sem == 0) return;
                        Object orig = param.getResult();   // after 里能读到原值
                        if (sem == 1) {
                            // 布尔位：getInt 0/1 或 getString "false" -> true/1
                            boolean falsey = mname.equals("getString") ? isFalseyStr(orig)
                                    : (mname.equals("getInt") && num(orig) == 0);
                            if (!falsey) return;
                            if (LOG_ONLY) {
                                XposedBridge.log(TAG + " [观测] DB布尔位 " + col + "=" + String.valueOf(orig)
                                        + " -> 本可注入" + (mname.equals("getString") ? "true" : "1") + " (idx=" + idx + ")");
                                return;
                            }
                            if (mname.equals("getString")) param.setResult("true");
                            else if (mname.equals("getInt")) param.setResult(1);
                        } else if (sem == 2) {
                            // 等级位：getInt/getLong -> 顶级档(若当前偏低)
                            if (mname.equals("getString")) return;   // 字符串等级位不盲改
                            long o = num(orig);
                            if (o >= TIER_HIGH) return;
                            if (LOG_ONLY) {
                                XposedBridge.log(TAG + " [观测] DB等级位 " + col + "=" + String.valueOf(orig)
                                        + " -> 本可注入" + TIER_HIGH + " (idx=" + idx + ")");
                                return;
                            }
                            if (mname.equals("getInt")) param.setResult(TIER_HIGH);
                            else if (mname.equals("getLong")) param.setResult((long) TIER_HIGH);
                        } else if (sem == 3) {
                            // 到期列：秒/毫秒二义，只观测提示，不强注入
                            XposedBridge.log(TAG + " [观测] DB到期列 " + col + "=" + String.valueOf(orig)
                                    + " (秒/毫秒二义, 建议在自有App里按schema定制或走MethodRuleHook)");
                        }
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static boolean isFalseyStr(Object orig) {
        if (orig == null) return true;
        String s = orig.toString().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.equals("0") || s.equals("false") || s.equals("null");
    }

    private static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt(((String) o).trim()); } catch (Exception e) { return -1; }
        }
        return -1;
    }

    /** 列名 -> 会员语义。 */
    private static int classifyColumn(String col) {
        if (col == null) return 0;
        String low = col.toLowerCase(Locale.ROOT);
        for (String h : HARD_PASS_COL) if (low.contains(h)) return 0;
        // v1.12 广告护栏(AdGuard)：广告服务控制/缓存列(ad_cache/ads_state/banner…)
        // 绝不按会员列改写 —— 强制改写可能把广告状态设成"开/已就绪"而激活广告。
        if (AdGuard.isAdControl(low)) return 0;
        Integer cached = colSem.get(low);
        if (cached != null) return cached;
        int sem = 0;
        if (containsAny(low, DATE_COL_HINT)) sem = 3;
        else if (containsAny(low, BOOL_COL_HINT)) sem = 1;
        else if (containsAny(low, TIER_COL_HINT)) sem = 2;
        colSem.put(low, sem);
        return sem;
    }

    private static boolean containsAny(String low, String[] arr) {
        for (String w : arr) if (low.contains(w)) return true;
        return false;
    }
}
