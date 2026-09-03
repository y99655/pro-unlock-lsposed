package com.example.ubilling;

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
 * 选定 App 的精确 PRO 激活增强（当前白名单：com.mobilecad.app / 指尖3D）。
 *
 * ============================================================================
 * 为什么需要它（与 UniversalVipSweeper 的分工）：
 *   UniversalVipSweeper 只拦截 SharedPreferences 读取，对“把会员态存在内存对象、
 *   不经 SP”的 App（如指尖3D）无效。指尖3D 1.3.x 逆向实证：
 *     类 q5/o0 = EntitlementState(pro, member{NONE/TRIAL/PURCHASED/LICENSED/ACCOUNT},
 *                                 expiresAtMillis, ...)
 *     构造器签名 (Z, Enum, long, Z)V，构造体内把【第一个布尔参数】写入激活位 pro；
 *     getter a()Z 返回激活位。
 *   因此：在该 App 进程里精确匹配这类构造器并强制首参=true，即可把 pro 位锁死。
 *
 * ============================================================================
 * 安全性设计（吸取 EArc 误伤教训）：
 *   1. 只在该方法被调用的进程里跑 —— 调用方(Main)仅在包名==com.mobilecad.app 时
 *      才 hook(cl)，绝不会对任意 App 生效；
 *   2. 只对“同时含 boolean 字段 + 精确 (Z, Enum, J, Z) 四参构造器”的类挂钩，
 *      绝不碰其它含 bool 的普通业务/几何类（曾因误伤 EArc 导致功能损坏）；
 *   3. 挂钩的 boolean getter 仅限无参 public、返回 boolean 的 getter，带参一律跳过。
 *
 * 方式：dex 直接枚举类名(反射 pathList.dexElements[].dexFile.mCookie) + loadClass
 * 钩 + 多时间点重扫，覆盖 R8 混淆包(q5/…)的懒加载/分包。
 * ============================================================================
 */
public class ProActivator {

    private static final String TAG = "[UPro]";

    /** 精确激活增强只对选定的 App 生效（调用方已按包名 gate，这里留常量备查）。 */
    public static final String TARGET_PKG = "com.mobilecad.app";

    private static final Set<String> scanned = new HashSet<>();
    private static final ThreadLocal<Boolean> inScan = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Set<String> loggedCandidate = new HashSet<>();

    private static int hookedCount = 0;
    private static int scannedCount = 0;
    private static int candidateCount = 0;

    private static volatile List<String> cachedDexNames = null;

    /**
     * 拿到应用全部 dex 类名（含 android.* 等系统类，调用方自行过滤）。
     * 反射 pathList.dexElements[].dexFile.mCookie -> DexFile.getClassNameList。
     */
    public static List<String> dexClassNames(ClassLoader cl) {
        if (cachedDexNames != null) return cachedDexNames;
        synchronized (ProActivator.class) {
            if (cachedDexNames != null) return cachedDexNames;
            List<String> out = new ArrayList<>();
            try {
                Object pathList = XposedHelpers.getObjectField(cl, "pathList");
                Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
                if (dexElements != null) {
                    Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
                    Method getClassNameList = dexFileClass.getDeclaredMethod("getClassNameList", Object.class);
                    getClassNameList.setAccessible(true);
                    for (Object element : dexElements) {
                        try {
                            Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                            if (dexFile == null) continue;
                            Object cookie = XposedHelpers.getObjectField(dexFile, "mCookie");
                            if (cookie == null) continue;
                            String[] names = (String[]) getClassNameList.invoke(dexFile, cookie);
                            if (names != null) for (String nm : names) out.add(nm);
                        } catch (Throwable ignore) {
                        }
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " dex 类名枚举异常: " + t);
            }
            cachedDexNames = out;
            return out;
        }
    }

    /** 入口：在目标 App 进程内调用一次。 */
    public static void hook(final ClassLoader appLoader) {
        if (appLoader == null) return;
        XposedBridge.log(TAG + " 精确激活增强已挂载 @ " + TARGET_PKG);
        try {
            enumerateDex(appLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dex 枚举失败: " + t);
        }
        // loadClass 钩：覆盖之后才加载的类 / 分包
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
        // 多时间点重扫，覆盖晚加载类
        scheduleSummary(1500L, 1);
        scheduleSummary(4000L, 2);
        scheduleSummary(9000L, 3);
    }

    private static void enumerateDex(ClassLoader cl) {
        int seen = 0;
        try {
            List<String> all = dexClassNames(cl);
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
                                    + "(挂钩>0 即生效；若=0 说明该版本 PRO 类签名变化，请回报日志)",
                            TAG, round, scannedCount, hookedCount, candidateCount));
                }
            }, delay);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 汇总调度失败(可忽略): " + t);
        }
    }

    private static void tryScan(Class<?> c) {
        if (c == null || c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) return;

        boolean hasBool = false;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == boolean.class) { hasBool = true; break; }
        }
        if (!hasBool) return; // 连 boolean 字段都没有，不可能是 PRO 对象

        // 只在“精确命中 (Z, Enum, J, Z) 构造器”时挂钩，绝不碰其它 bool 类
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 4
                    && p[0] == boolean.class
                    && p[1].isEnum()
                    && p[2] == long.class
                    && p[3] == boolean.class) {
                hookConstructor(c, ctor, p[1].getName());
                hookSingleBooleanGetter(c);
                logProShape(c, "PRO构造器命中");
                return;
            }
        }
    }

    private static void logProShape(Class<?> c, String reason) {
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
        XposedBridge.log(String.format("%s PRO对象: %s  匹配=%s  构造器:%s",
                TAG, c.getName(), reason, ct));
    }

    /**
     * 只对“已被精确匹配的 PRO 类”钩无参 public boolean getter(如 a()Z)，强制 true。
     * 只在该类构造器精确命中后被调用，绝不对任意 bool 类生效。
     */
    private static void hookSingleBooleanGetter(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (m.getReturnType() != boolean.class) continue;
                if (Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
                if (m.getParameterTypes().length != 0) continue; // 只钩无参 getter
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    });
                    XposedBridge.log(TAG + "  钩 PRO getter: " + c.getName() + "." + m.getName() + "() -> true");
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
