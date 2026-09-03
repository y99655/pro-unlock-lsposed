package com.example.prounlock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.res.Resources;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 去除底部导航里的「我的」Tab（版本无关、混淆无关）。
 *
 * 背景：Digit3D/指尖3D 主界面是 Jetpack Compose，底部 4 个 Tab（首页/我的/设置/教程）
 * 由一个静态持有类（1.3.2 为 z5/d40）在 <clinit> 里构造出 4 个 Tab 对象，
 * 存进静态字段 i:Ljava/util/List;（元素类形如 字段{a:页面枚举, b:图标枚举, c:int标签资源}）。
 * 渲染时 Composable 读该静态 List 逐个画 Tab。因此只要把静态 List 里的「我的」元素摘掉，
 * 再替换回静态字段，首次组合就不会再画「我的」Tab —— 无需 recreate、无闪烁。
 *
 * 识别「我的」不靠类名/字段名（混淆名每版随机），而靠【内容判定】，天然通杀：
 *   遍历 dex 中所有"含 static java.util.List 字段"的类（读到列表内容），
 *   对每个 Tab 元素，读它的 int 字段，若该 int 作为资源 id 解析出的资源条目名
 *   恰为 "nav_mine_short"（或文案为 我的/Me/Mine），则该元素就是「我的」Tab，摘除之。
 *
 * 触发点：MainActivity.onCreate 之前（此时 UI 尚未 setContent，摘除后无需重绘）。
 * com.mobilecad.app.MainActivity 是不混淆的主入口，正好给到有效 Context 用于解析资源名。
 */
public class NavTabCleaner {

    private static final String TAG = "[NavTab]";
    private static volatile boolean done = false;

    /** 需要摘除的 Tab 标签资源名（应用自己的 strings，不随混淆改名，跨版本稳定）。 */
    private static final String MINE_RES_NAME = "nav_mine_short";
    /** 兜底文案（zh/默认/en）。 */
    private static final String[] MINE_TEXTS = {"我的", "Me", "Mine", "我的项目"};

    public static void hook(final ClassLoader appLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.mobilecad.app.MainActivity", appLoader,
                    "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (done) return;
                            try {
                                Context ctx = (Context) param.thisObject;
                                if (ctx == null) return;
                                cleanNavTabs(ctx, appLoader);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " 清理异常(可忽略): " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " 已挂载：MainActivity.onCreate 前自动摘除「我的」Tab");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂载失败: " + t);
        }
    }

    // ---------- 主流程：遍历含 static List 字段的类，内容判定后摘除 ----------
    private static void cleanNavTabs(Context ctx, ClassLoader cl) {
        List<String> holderNames = new ArrayList<>();
        try {
            // 复用 ProUnlock 的 dex 直接枚举：先拿全部应用类名，再按“含 static java.util.List 字段”初筛（不初始化类，安全）
            for (String nm : ProUnlock.dexClassNames(cl)) {
                if (isSystem(nm)) continue;
                // 几何内核(com.mobilecad.domain.*,JNI保留不混淆)与纯入口(com.mobilecad.app)不含“我的Tab”静态列表,跳过以缩小范围、减少初始化
                if (nm.startsWith("com.mobilecad.domain.") || nm.startsWith("com.mobilecad.app.")) continue;
                Class<?> c;
                try {
                    c = Class.forName(nm, false, cl);
                } catch (Throwable ignore) {
                    continue;
                }
                if (c.isInterface() || c.isEnum() || c.isAnnotation()) continue;
                if (hasStaticListField(c)) holderNames.add(nm);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 候选枚举异常: " + t);
        }
        XposedBridge.log(TAG + " 候选(含 static List 字段)类数 = " + holderNames.size());

        Resources res = null;
        try {
            res = ctx.getResources();
        } catch (Throwable ignore) {
        }

        for (String nm : holderNames) {
            Class<?> c;
            try {
                c = Class.forName(nm, true, cl); // initialize=true：只有真正含目标列表的类才被初始化前需要读取，读到即摘除后立即返回
            } catch (Throwable ignore) {
                continue;
            }
            try {
                if (tryCleanOne(c, res)) {
                    done = true;
                    return;
                }
            } catch (Throwable ignore) {
            }
        }
        XposedBridge.log(TAG + " 未找到含「我的」Tab 的静态列表（请回报上方候选数）");
    }

    /** 尝试清理某个类：遍历它的 static List 字段，内容判定含「我的」则摘除并替换字段值。 */
    private static boolean tryCleanOne(Class<?> c, Resources res) throws Exception {
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != List.class && f.getType() != java.util.Collection.class) continue;
            f.setAccessible(true);
            Object raw;
            try {
                raw = f.get(null);
            } catch (Throwable t) {
                continue; // 读取触发该字段/类初始化出错，跳过
            }
            if (!(raw instanceof List)) continue;
            List<?> list = (List<?>) raw;
            List<Object> keep = new ArrayList<>();
            int removed = 0;
            for (Object item : list) {
                if (isMineTab(item, res)) {
                    removed++;
                } else {
                    keep.add(item);
                }
            }
            if (removed > 0 && keep.size() < list.size()) {
                // 替换静态字段（原多为 static final 的不可变/定长 List）
                replaceStaticField(f, keep);
                XposedBridge.log(String.format(
                        "%s 命中列表: %s.%s  元素 %d -> %d，已摘除「我的」Tab",
                        TAG, c.getName(), f.getName(), list.size(), keep.size()));
                return true;
            }
        }
        return false;
    }

    private static boolean isMineTab(Object item, Resources res) {
        if (item == null) return false;
        Class<?> t = item.getClass();
        // 该元素须为 Tab 形态：含一个 int 字段（标签资源 id）；有枚举字段更好但以 int 资源为准
        for (Field f : t.getDeclaredFields()) {
            if (f.getType() != int.class) continue;
            try {
                f.setAccessible(true);
                int v = f.getInt(item);
                // 1) 资源条目名判定（最稳，跨语言）
                if (res != null) {
                    try {
                        String name = res.getResourceEntryName(v);
                        if (MINE_RES_NAME.equals(name)) return true;
                    } catch (Throwable ignore) {
                    }
                }
                // 2) 文案兜底
                try {
                    if (res != null) {
                        String s = res.getString(v);
                        if (s != null) {
                            for (String mine : MINE_TEXTS) {
                                if (s.equals(mine)) return true;
                            }
                        }
                    }
                } catch (Throwable ignore) {
                }
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    private static void replaceStaticField(Field f, List<?> newList) {
        try {
            f.setAccessible(true);
            // 去掉 final（静态对象字段在 ART 上须去掉 FINAL 才能反射改写）
            try {
                Field mf = Field.class.getDeclaredField("modifiers");
                mf.setAccessible(true);
                mf.setInt(f, f.getModifiers() & ~Modifier.FINAL);
            } catch (Throwable ignore) {
            }
            f.set(null, newList);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 替换静态字段失败 " + f + ": " + t);
        }
    }

    private static boolean hasStaticListField(Class<?> c) {
        try {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        && (f.getType() == List.class || f.getType() == java.util.Collection.class)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean isSystem(String n) {
        return n.startsWith("android.") || n.startsWith("androidx.")
                || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.") || n.startsWith("kotlinx.")
                || n.startsWith("com.google.") || n.startsWith("com.android.")
                || n.startsWith("dalvik.") || n.startsWith("sun.")
                || n.startsWith("jdk.") || n.startsWith("org.");
    }
}
