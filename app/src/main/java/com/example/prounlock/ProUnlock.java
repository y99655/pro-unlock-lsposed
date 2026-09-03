package com.example.prounlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真正的"通杀"解锁点（替代原先基于 Google Play Billing 的错误假设）。
 *
 * 经对指尖3D / Digit3D (com.mobilecad.app) 各版本 smali 逆向确认：
 *   PRO 激活状态保存在应用内部的一个数据对象里（如 1.3.2 的 q5/o0），
 *   其结构为：
 *     field public final a:Z        <- 激活布尔位（PRO 是否激活）
 *     field public final b:L<枚举>; <- 档位枚举（standard / pro ...）
 *     field public final c:J        <- long（到期时间等）
 *     field public final d:Z        <- 另一个布尔位
 *   构造签名固定为 (Z, 枚举, long, Z)V，且 a 由【第一个布尔参数】写入。
 *   该字段是 final，构造后不可变，因此只要在构造时把第一个布尔参数强制为 true，
 *   应用后续读取到的永远是"已激活"，与服务端 / 计费无关 → 这才是真正的通杀。
 *
 * 为避免写死混淆后的类名（q5/o0 随版本变化），本模块在运行时扫描
 * com.mobilecad.app 包下所有类，找出"构造签名恰为 (Z, Enum, long, Z) 且类含
 * boolean / enum / long 字段"的类，对其构造器挂钩，在 beforeHook 里强制 args[0]=true。
 */
public class ProUnlock {

    private static final String PKG_PREFIX = "com.mobilecad.app";
    private static boolean scanned = false;

    public static void hook(ClassLoader cl) {
        if (scanned) return;
        scanned = true;
        try {
            List<String> classes = listDexClasses(cl);
            int hooked = 0;
            for (String cn : classes) {
                if (cn == null || !cn.startsWith(PKG_PREFIX)) continue;
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    if (c.isInterface() || c.isAnnotation() || c.isEnum() || c.isPrimitive()) continue;
                    if (!hasProShape(c)) continue;
                    for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                        Class<?>[] ps = ctor.getParameterTypes();
                        if (ps.length == 4
                                && ps[0] == boolean.class
                                && ps[1].isEnum()
                                && ps[2] == long.class
                                && ps[3] == boolean.class) {
                            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    param.args[0] = Boolean.TRUE;
                                }
                            });
                            hooked++;
                            XposedBridge.log("[ProUnlock] 挂钩 PRO 构造器: " + c.getName());
                        }
                    }
                } catch (Throwable ignore) {
                    // 个别类无法加载 / 解析，跳过
                }
            }
            XposedBridge.log("[ProUnlock] PRO 构造器扫描完成，共挂钩 " + hooked
                    + " 个（若为 0 说明未匹配到，请回报机型/版本）");
        } catch (Throwable t) {
            XposedBridge.log("[ProUnlock] 扫描异常: " + t);
        }
    }

    /** 二次过滤：类必须同时拥有 boolean 字段、enum 字段、long 字段（与 PRO 对象结构吻合）。 */
    private static boolean hasProShape(Class<?> c) {
        boolean b = false, e = false, l = false;
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == boolean.class) b = true;
            else if (t.isEnum()) e = true;
            else if (t == long.class) l = true;
        }
        return b && e && l;
    }

    /** 列举该 classloader 所有 dex 内的类名。 */
    private static List<String> listDexClasses(ClassLoader cl) {
        List<String> out = new ArrayList<>();
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object el : elements) {
                Object dexFile = XposedHelpers.getObjectField(el, "dexFile");
                if (dexFile == null) continue;
                String[] names = (String[]) XposedHelpers.callMethod(dexFile, "getClassNameList");
                if (names == null) continue;
                for (String n : names) out.add(n);
            }
        } catch (Throwable t) {
            XposedBridge.log("[ProUnlock] 列举 dex 类失败: " + t);
        }
        return out;
    }
}
