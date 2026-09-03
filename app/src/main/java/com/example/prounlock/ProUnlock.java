package com.example.prounlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真正的"通杀"解锁点。
 *
 * PRO 激活状态保存在应用内部数据对象里（如 1.3.2 的 q5/o0）：
 *   构造签名 (Z, Enum, long, Z)V，激活布尔位【a:Z】由第一个布尔参数写入，
 *   且字段 final、构造后不可变。构造时把第一个布尔参数强制为 true 即可解锁。
 *
 * 关键：该 App 经 R8/ProGuard 混淆后，**几乎全部业务类都在混淆包(a0..z/q5等)里**，
 * com.mobilecad.app 包内只剩 MainActivity 等极少入口。因此不能按包名前缀过滤，
 * 必须扫描 classloader 加载的【所有类】（签名 (Z,Enum,long,Z) 足够独特，不会误伤系统类）。
 *
 * 扫描方案：挂钩 ClassLoader.loadClass，每加载一个非系统应用类就用标准反射检查构造签名。
 * 命中即挂钩构造器、beforeHook 强制 args[0]=true。
 */
public class ProUnlock {

    private static final String TAG = "[ProUnlock]";

    private static final Set<String> scanned = new HashSet<>();
    private static final ThreadLocal<Boolean> inScan = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Set<String> loggedCandidate = new HashSet<>();

    private static int hookedCount = 0;
    private static int scannedCount = 0;
    private static int candidateCount = 0;

    public static void hook() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (inScan.get()) return;
                            Class<?> c = (Class<?>) param.getResult();
                            if (c == null) return;
                            if (!scanned.add(c.getName())) return;
                            // 跳过明显的外部类（android/java/javax/kotlin/com.google/androidx）
                            String n = c.getName();
                            if (n.startsWith("android.") || n.startsWith("androidx.")
                                    || n.startsWith("java.") || n.startsWith("javax.")
                                    || n.startsWith("kotlin.") || n.startsWith("com.google.")
                                    || n.startsWith("com.android.")) {
                                return;
                            }
                            inScan.set(Boolean.TRUE);
                            try {
                                scannedCount++;
                                tryScan(c);
                            } catch (Throwable ignore) {
                            } finally {
                                inScan.set(Boolean.FALSE);
                            }
                        }
                    });
            XposedBridge.log(TAG + " loadClass 扫描器已挂载（扫描全部应用类，不再限定 com.mobilecad.app）");
            scheduleSummary();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadClass hook 失败: " + t);
        }
    }

    /** 启动后延迟打印一次汇总，保证即使挂钩 0 个也有明确结论。 */
    private static void scheduleSummary() {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    XposedBridge.log(String.format(
                            "%s 扫描汇总：已扫描 %d 个类，命中挂钩 %d 个，候选(PRO形态) %d 个"
                                    + "（挂钩>0 即生效；若=0 请把上方的\"候选(PRO形态)\"行回报）",
                            TAG, scannedCount, hookedCount, candidateCount));
                }
            }, 3000L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 汇总调度失败(可忽略): " + t);
        }
    }

    /**
     * 打印"具备 PRO 形态"的类（字段含 boolean + enum + long 且构造含 boolean/long 参数）。
     * 这类就是 PRO 对象（如 q5/o0）。只要出现这一行，就说明扫描已能定位到它，
     * 若此时仍未挂钩，日志里的真实构造签名即可用来精确适配。
     */
    private static void logProShape(Class<?> c, boolean hasEnumF, boolean hasLong) {
        if (candidateCount >= 15) return;
        if (!loggedCandidate.add(c.getName())) return;
        candidateCount++;
        StringBuilder ct = new StringBuilder();
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            ct.append(" (");
            Class<?>[] p = ctor.getParameterTypes();
            for (int i = 0; i < p.length; i++) ct.append(i == 0 ? "" : ", ").append(shortName(p[i]));
            ct.append(")V");
        }
        XposedBridge.log(String.format("%s 候选(PRO形态): %s  enum字段=%b long字段=%b 构造器:%s",
                TAG, c.getName(), hasEnumF, hasLong, ct));
    }

    private static void tryScan(Class<?> c) {
        if (c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) return;

        boolean hasBool = false, hasEnumF = false, hasLong = false;
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == boolean.class) hasBool = true;
            else if (t.isEnum()) hasEnumF = true;
            else if (t == long.class) hasLong = true;
        }
        if (!hasBool) return; // 连 boolean 字段都没有，不可能是 PRO 对象

        boolean matched = false;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            // 精确匹配： (Z, Enum, J, Z)
            if (p.length == 4
                    && p[0] == boolean.class
                    && p[1].isEnum()
                    && p[2] == long.class
                    && p[3] == boolean.class) {
                hookConstructor(c, ctor, p[1].getName());
                matched = true;
            }
        }

        // 只要类具备完整 PRO 形态(字段同时含 boolean+enum+long)就立刻打印，
        // 便于确认扫描已定位到 PRO 对象；即使构造签名不同也能暴露真实签名。
        if (hasEnumF && hasLong) {
            logProShape(c, hasEnumF, hasLong);
            // 兜底：同类的 boolean 无参/单参 getter（如 q5/o0.a()Z 返回激活位）也强制返回 true，
            // 这样即使构造点没被拦到，应用每次“查询是否 PRO”都会被改写成 true。
            hookBooleanGetters(c);
        }
    }

    /** 钩住 PRO 形态类上所有返回 boolean 的访问方法，强制返回 true（构造拦不到的兜底）。 */
    private static void hookBooleanGetters(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (m.getReturnType() != boolean.class) continue;
                if (Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
                if (m.getParameterTypes().length > 1) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    });
                    XposedBridge.log(TAG + "  兜底钩 getter: " + c.getName() + "." + m.getName() + "() -> true");
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " getter 钩失败 " + c.getName() + ": " + t);
        }
    }

    private static String shortName(Class<?> t) {
        if (t == boolean.class) return "Z";
        if (t == long.class) return "J";
        if (t == int.class) return "I";
        if (t == double.class) return "D";
        if (t == float.class) return "F";
        if (t == short.class) return "S";
        if (t == byte.class) return "B";
        if (t == char.class) return "C";
        if (t.isEnum()) return "E:" + t.getSimpleName();
        return t.getSimpleName();
    }

    private static void hookConstructor(Class<?> c, Constructor<?> ctor, String enumName) {
        try {
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0) {
                        param.args[0] = Boolean.TRUE; // 强制第一个布尔参数 = 激活位 = true
                    }
                }
            });
            hookedCount++;
            XposedBridge.log(String.format(
                    "%s 挂钩 PRO 构造器: %s  档位枚举=%s  累计挂钩=%d",
                    TAG, c.getName(), enumName, hookedCount));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂钩失败 " + c.getName() + ": " + t);
        }
    }
}
