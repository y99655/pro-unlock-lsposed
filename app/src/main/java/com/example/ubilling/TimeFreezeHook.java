package com.example.ubilling;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 【H】时间冻结 / 时钟拨回通道（TimeFreezeHook）—— 覆盖“会员到期判定 = 服务器/本地
 * 下发的到期时间 与 当前时间比较（now > expireTs 即过期）”。这类判定即便你把到期
 * 字段/SP/DB 全改成 2099，只要代码用 System.currentTimeMillis()/Date/SystemClock
 * 取“当前时间”来比较，仍然会判过期。本通道直接从【时间源】下手：把目标进程读到的
 * “当前时间”整体回调一段时间，令“未过期”判定恒成立。
 *
 * ============================================================================
 * 判定载体覆盖对照：
 *   【B】UVip   改 SP 里存的到期 key（治标：到期值本身）；
 *   【F】Auto   改内存 getter/字段返回（治标：返回值本身）；
 *   【G】DB     改数据库列的读取（治标：DB 里的到期值）；
 *   但“过期 = now > expire”里的【now】还没人动。App 若把比较写成
 *     long now = System.currentTimeMillis();
 *     if (now < getExpireTs()) 已开通 else 已过期
 *   上面三通道把 getExpireTs 改成 2099 就够；可若到期判断是“服务端秒 + 本地 now”，
 *   或本地根本没有到期字段、纯靠 now 落在某个开放窗口，就需本通道拨时间。
 *   =========================================================================
 *   ⇒ hook 的【时间源】（绝对时间，可用于到期比较）：
 *     System.currentTimeMillis()                 —— 唯一关键源（Date/Calendar 都基于它）
 *     android.os.SystemClock.uptimeMillis()       —— 自开机单调，多数不用；拨回会干扰计时，默认不动
 *     android.os.SystemClock.elapsedRealtime()    —— 同上，默认不动
 *   默认只 hook System.currentTimeMillis()，安全、够用。
 *
 * ============================================================================
 * 用法：
 *   1) 找到该 App 判到期的“now 前移多少天”才放行 → 把 PULLBACK_MS 填成对应毫秒
 *      （如把当前时间回调 180 天 = -180L*24*3600*1000）。
 *   2) 默认 PULLBACK_MS=0（不偏移、仅挂钩并打一条证明生效）；
 *      确认在你自有 App 上安全后再设非 0 做真实拨回。
 *   仅用于你自己开发/拥有或明确获授权做安全评估的 App —— 请勿用于破解他人的收费服务。
 * ============================================================================
 */
public class TimeFreezeHook {

    private static final String TAG = "[UTime]";

    /** ====== 时间拨回量（毫秒）======
     *  0           -> 不偏移时间，仅演示挂钩成功（打印一次当前真实时间）。
     *  负值         -> 把进程读到的时间往前回调该毫秒数（-N 毫秒）。
     *  正(不建议)   -> 往前拨。
     *  例：把“当前时间”回调 180 天：
     *        private static final long PULLBACK_MS = -180L * 24L * 3600L * 1000L;
     *  例：回调 1 年：-365L * 24L * 3600L * 1000L */
    private static final long PULLBACK_MS = 0L;

    /** 是否顺带把 SystemClock.uptimeMillis 也回调（默认 false——单调时钟拨回会干扰时长
     *  判断，除非确认目标 App 到期判定用了它才开）。 */
    private static final boolean ALSO_UPTIME = false;

    /** 仅对选定包名执行(调用方也 gate，双保险)；null 表示任意作用域进程。 */
    public static final String TARGET_PKG = null;

    private static final Set<String> donePkg = Collections.synchronizedSet(new HashSet<String>());

    public static void hook(final ClassLoader cl, final String pkg) {
        if (cl == null || pkg == null) return;
        if (TARGET_PKG != null && !TARGET_PKG.equals(pkg)) return;
        if (!donePkg.add(pkg)) return;

        boolean active = PULLBACK_MS != 0L;
        XposedBridge.log(TAG + " 时间冻结通道挂载 @ " + pkg + " (PULLBACK_MS=" + PULLBACK_MS + "ms, "
                + (active ? "生效: 进程读到的时间被回调 " + (-PULLBACK_MS) + "ms" : "PULLBACK_MS=0 未拨回(仅挂钩)") + ")");

        hookSystemMillis();
        if (ALSO_UPTIME) hookSystemClock("uptimeMillis");
    }

    /** 拦截 System.currentTimeMillis()：返回 now + PULLBACK_MS。 */
    private static void hookSystemMillis() {
        try {
            Method m = System.class.getMethod("currentTimeMillis");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    long real = (Long) param.getResult();
                    param.setResult(real + PULLBACK_MS);
                }
            });
            XposedBridge.log(TAG + " 已挂钩 System.currentTimeMillis (真实now+"
                    + PULLBACK_MS + "ms) @ 进程");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂钩 System.currentTimeMillis 失败: " + t);
        }
    }

    /** 拦截 android.os.SystemClock.<m>（uptimeMillis/elapsedRealtime），需平台类反射找。 */
    private static void hookSystemClock(final String mname) {
        try {
            Class<?> sc = Class.forName("android.os.SystemClock");
            Method m = sc.getMethod(mname);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    long real = (Long) param.getResult();
                    param.setResult(real + PULLBACK_MS);
                }
            });
            XposedBridge.log(TAG + " 已挂钩 android.os.SystemClock." + mname);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂钩 SystemClock." + mname + " 失败: " + t);
        }
    }
}
