package com.example.prounlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真正的"通杀"解锁点。
 *
 * PRO 状态保存在应用内部数据对象里。逆向确认（1.3.x 通用）：
 *   类 q5/o0：字段 a:Z=激活位 / b:Lq5/n0;=档位枚举 / c:J / d:Z
 *   构造签名 (Z, Enum, long, Z)V，构造体内 iput-boolean p1 -> a:Z，
 *   即【第一个布尔参数】= 激活位；字段 a:Z 为 public final，构造后不可变。
 *   因此：把构造器第一个布尔参数强制为 true 即可解锁（与 smali 通杀补丁同原理）。
 *   getter a()Z 返回激活位 —— 也钩住强制返回 true，双保险。
 *
 * 关键：App 被 R8 混淆，业务类都在混淆包(q5/a0/...)，com.mobilecad.app 内只有
 * MainActivity。不能按包名过滤，也不能只等 loadClass（懒加载+时延导致扫不到）。
 *
 * 方案（v4）：【dex 直接枚举】拿目标 app 的 classloader，反射走
 *   pathList.dexElements[i].dexFile.mCookie 调 DexFile.getClassNameList(cookie)，
 *   一次性拿到应用全部类名（不依赖懒加载时机），逐个 Class.forName 后 tryScan 挂钩。
 *   再叠加 loadClass 钩 + 多时间点重扫，覆盖动态/分包加载。
 */
public class ProUnlock {

    private static final String TAG = "[ProUnlock]";

    private static final Set<String> scanned = new HashSet<>();
    private static final ThreadLocal<Boolean> inScan = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Set<String> loggedCandidate = new HashSet<>();

    private static int hookedCount = 0;
    private static int scannedCount = 0;
    private static int candidateCount = 0;

    public static void hook(final ClassLoader appLoader) {
        // 1) dex 直接枚举：反射 pathList.dexElements[].dexFile.mCookie + getClassNameList
        try {
            enumerateDex(appLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dex 枚举失败: " + t);
        }
        // 2) 叠加 loadClass 钩：覆盖之后才加载的类 / 分包
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (inScan.get()) return;
                            Class<?> c = (Class<?>) param.getResult();
                            if (c == null) return;
                            String n = c.getName();
                            if (!scanned.add(n)) return;
                            if (isSystem(n)) return;
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadClass hook 失败(可忽略): " + t);
        }
        XposedBridge.log(TAG + " 扫描器已挂载（dex 直接枚举 + loadClass）");
        // 3) 多时间点汇总：覆盖晚加载的类
        scheduleSummary(1500L, 1);
        scheduleSummary(4000L, 2);
        scheduleSummary(9000L, 3);
    }

    // ---------- dex 直接枚举 ----------
    private static void enumerateDex(ClassLoader cl) {
        int seen = 0;
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            if (dexElements == null) return;
            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
            Method getClassNameList = dexFileClass.getDeclaredMethod("getClassNameList", Object.class);
            getClassNameList.setAccessible(true);
            List<String> all = new ArrayList<>();
            for (Object element : dexElements) {
                try {
                    Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                    if (dexFile == null) continue;
                    Object cookie = XposedHelpers.getObjectField(dexFile, "mCookie");
                    if (cookie == null) continue;
                    String[] names = (String[]) getClassNameList.invoke(dexFile, cookie);
                    if (names != null) for (String nm : names) all.add(nm);
                } catch (Throwable ignore) {
                }
            }
            for (String nm : all) {
                if (isSystem(nm)) continue;
                if (!scanned.add(nm)) continue;
                seen++;
                try {
                    Class<?> c = Class.forName(nm, false, cl);
                    inScan.set(Boolean.TRUE);
                    try {
                        scannedCount++;
                        tryScan(c);
                    } finally {
                        inScan.set(Boolean.FALSE);
                    }
                } catch (Throwable ignore) {
                }
            }
            XposedBridge.log(TAG + " dex 枚举完成：读取 " + all.size() + " 个类名，扫描 " + seen + " 个应用类");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dex 枚举异常: " + t);
        }
    }

    private static boolean isSystem(String n) {
        return n.startsWith("android.") || n.startsWith("androidx.")
                || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.") || n.startsWith("kotlinx.")
                || n.startsWith("com.google.") || n.startsWith("com.android.")
                || n.startsWith("dalvik.") || n.startsWith("sun.")
                || n.startsWith("jdk.") || n.startsWith("org.");
    }

    private static void scheduleSummary(long delay, int round) {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    XposedBridge.log(String.format(
                            "%s [第%d轮] 扫描汇总：已扫描 %d 个类，命中挂钩 %d 个，候选(PRO形态) %d 个"
                                    + "（挂钩>0 即生效；若=0 请把上方\\\"候选(PRO形态)\\\"行回报）",
                            TAG, round, scannedCount, hookedCount, candidateCount));
                }
            }, delay);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 汇总调度失败(可忽略): " + t);
        }
    }

    private static void tryScan(Class<?> c) {
        if (c == null || c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) return;

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
            // 精确匹配： (Z, Enum, J, Z)  —— q5/o0 真身
            if (p.length == 4
                    && p[0] == boolean.class
                    && p[1].isEnum()
                    && p[2] == long.class
                    && p[3] == boolean.class) {
                hookConstructor(c, ctor, p[1].getName());
                matched = true;
            }
        }

        // 具备完整 PRO 形态(boolean+enum+long 字段)的类：打印真实签名 + 兜底钩 boolean getter
        if (hasEnumF && hasLong) {
            logProShape(c);
            hookBooleanGetters(c);
        }
        // 即便形态不全（只有 boolean+long，无 enum 字段）也打印一条轻量线索，便于排错
        else if (matched && !hasEnumF) {
            logProShape(c);
        }
    }

    private static void logProShape(Class<?> c) {
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
        XposedBridge.log(String.format("%s 候选(PRO形态): %s 构造器:%s",
                TAG, c.getName(), ct));
    }

    /** 钩住返回 boolean 的无参/单参 public 方法，强制返回 true（构造拦不到的兜底）。 */
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
