package com.example.prounlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
 * PRO 激活状态保存在应用内部数据对象里（如 1.3.2 的 q5/o0），
 * 构造签名 (Z, 枚举, long, Z)V，激活布尔位【a:Z】由第一个布尔参数写入，
 * 且该字段 final、构造后不可变。只要在构造时把第一个布尔参数强制为 true 即可解锁，
 * 与计费 / 服务端无关 → 真通杀。
 *
 * 扫描方案（v3，修复 Android 10+ 失效问题）：
 *   挂钩 ClassLoader.loadClass，每当 com.mobilecad.app 加载一个类，
 *   用标准反射检查其构造签名。命中即挂钩构造器、beforeHook 强制 args[0]=true。
 *
 * v3.2 新增诊断：
 *   - 命中后打印 "挂钩 PRO 构造器: ..."（每命中一次）
 *   - 若某类具备 PRO 形态(boolean+enum+long 字段)但构造签名未精确匹配，
 *     打印 "候选未匹配" 以便回报表中真实签名
 *   - 启动约 8 秒后打印一次汇总：已扫描类数 / 已挂钩数（即使为 0 也有结论）
 */
public class ProUnlock {

    private static final String TAG = "[ProUnlock]";
    private static final String PKG_PREFIX = "com.mobilecad.app";

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
                            String name = c.getName();
                            if (!name.startsWith(PKG_PREFIX)) return;
                            if (!scanned.add(name)) return;
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
            XposedBridge.log(TAG + " loadClass 扫描器已挂载（扫描 " + PKG_PREFIX + " 全部加载类）");
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
                            "%s 扫描汇总：已扫描 %d 个类，命中挂钩 %d 个，候选未匹配 %d 个"
                                    + "（挂钩>0 即生效；若=0 请把下方\"候选未匹配\"的签名回报）",
                            TAG, scannedCount, hookedCount, candidateCount));
                }
            }, 8000L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 汇总调度失败(可忽略): " + t);
        }
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
        // 诊断：具备 PRO 形态(boolean+enum+long 字段)但未精确匹配 -> 记录真实构造签名
        if (!matched && hasEnumF && hasLong && candidateCount < 15) {
            if (loggedCandidate.add(c.getName())) {
                candidateCount++;
                StringBuilder sb = new StringBuilder();
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    sb.append(" (");
                    Class<?>[] p = ctor.getParameterTypes();
                    for (int i = 0; i < p.length; i++) {
                        sb.append(i == 0 ? "" : ", ").append(shortName(p[i]));
                    }
                    sb.append(")V");
                }
                XposedBridge.log(String.format(
                        "%s 候选未匹配: %s  字段[bool=%b,enum=%b,long=%b] 构造器:%s",
                        TAG, c.getName(), hasBool, hasEnumF, hasLong, sb));
            }
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
