package com.example.prounlock;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真正的"通杀"解锁点。
 *
 * 经对指尖3D / Digit3D (com.mobilecad.app) 各版本 smali 逆向确认：
 *   PRO 激活状态保存在应用内部的一个数据对象里（如 1.3.2 的 q5/o0），
 *   构造签名固定为 (Z, 枚举, long, Z)V，且激活布尔位【a:Z】由第一个布尔参数写入。
 *   该字段是 final，构造后不可变；只要在构造时把第一个布尔参数强制为 true，
 *   应用后续读取到的永远是"已激活"，与计费 / 服务端无关 → 真正的通杀。
 *
 * 旧方案用 DexFile.getClassNameList 反射枚举类，该方法在 Android 10+ 已失效，
 * 导致扫描到 0 个类、挂钩 0 个。现改为：挂钩 ClassLoader.loadClass，
 * 每当应用加载一个 com.mobilecad.app 的类就用标准反射检查其构造签名，
 * 命中即挂钩构造器、在 beforeHook 强制 args[0]=true。
 * 由于"挂钩"发生在 loadClass 返回之后、new 执行之前，即便是首次实例化也已生效。
 */
public class ProUnlock {

    private static final String TAG = "[ProUnlock]";
    private static final String PKG_PREFIX = "com.mobilecad.app";

    // 已扫描类名，避免重复扫描
    private static final Set<String> scanned = new HashSet<>();
    // 防止 getParameterTypes() 触发类加载时递归进入本 hook
    private static final ThreadLocal<Boolean> inScan = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static int hookedCount = 0;
    private static int scannedCount = 0;

    public static void hook() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 递归保护：避免检查参数类型时触发的类加载再次进入扫描
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
                                // 单个类解析失败，跳过
                            } finally {
                                inScan.set(Boolean.FALSE);
                            }
                        }
                    });
            XposedBridge.log(TAG + " loadClass 扫描器已挂载（扫描 com.mobilecad.app 全部加载类）");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadClass hook 失败: " + t);
        }
    }

    /** 检查某类的构造签名是否为 (Z, Enum, J, Z)，命中则挂钩。 */
    private static void tryScan(Class<?> c) {
        if (c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) return;
        // 仅当类确实含有 boolean 字段时才值得检查构造器，减少开销
        boolean hasBoolField = false;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (f.getType() == boolean.class) { hasBoolField = true; break; }
        }
        if (!hasBoolField) return;

        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length != 4) continue;
            if (p[0] != boolean.class) continue;
            if (p[1].isEnum()) continue;            // 第 2 个必须是枚举档位
            if (p[2] != long.class) continue;       // 第 3 个必须是 long
            if (p[3] != boolean.class) continue;     // 第 4 个必须是 boolean
            hookConstructor(c, ctor, p[1].getName());
        }
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

    /** 应用首次实例化完成后打印统计（由 Main 在延迟任务里调用一次即可，这里留作备用）。 */
    public static void reportStats() {
        XposedBridge.log(String.format(
                "%s 扫描完成：已扫描 %d 个类，共挂钩 %d 个 PRO 构造器（若为 0 说明未匹配，请回报机型/版本）",
                TAG, scannedCount, hookedCount));
    }
}
