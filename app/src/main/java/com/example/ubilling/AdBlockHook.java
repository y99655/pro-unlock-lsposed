package com.example.ubilling;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 【I】第三方广告 SDK 去广告通道（AdBlockHook）。
 *
 * ============================================================================
 * v1.13 变更（为什么默认改成"放行+观测"，不再默认整类屏蔽）
 * ============================================================================
 * 【闪退根因】旧版 v1.11/1.12 用"整类屏蔽"：hook ClassLoader.loadClass，命中广告
 * SDK 包名前缀就抛 ClassNotFoundException，让广告类根本加载不起来。若目标 App
 * 对广告 SDK 是【硬引用】(编译期直接 import / new / 顶层调用) 且该处没有
 * try/catch 保护，那么加载失败会把异常抛到调用方栈 → App 闪退。
 *
 * 【v1.13 修复】把"默认行为"从"抛异常整类屏蔽"改为"放行 + 观测"：
 *   - HARD_BLOCK=false（默认）→ loadClass 命中广告前缀【放行】(类正常加载)，
 *     只打一条 [UAd] 观测日志。广告类照常加载、App 绝不因本通道闪退。
 *   - HARD_BLOCK=true → 恢复旧版"整类屏蔽"(抛 CNFE)。仅当你确认目标 App 的
 *     广告 SDK 是【懒加载 / 反射按需加载 / 有 try/catch 保护】、硬屏蔽不会崩时，
 *     才应打开，以获得最强的去广告效果。
 *
 * 【为什么不能"既彻底去广告又对任意 App 永不崩"】
 *   业界成熟去广告模块(AdClose/净化模块等)对"去广告"本质是【逐应用适配】：掐断
 *   具体广告 SDK 的 init/加载入口或拦截其广告网络请求。所谓"通用启发式去广告"在
 *   加固 App / 自绘广告 / 聚合 SDK 混用的现实里并不可靠。本通道保持轻量通用：
 *   先保证不崩 + 用 [UAd] 观测告诉你目标 App 实际命中了哪些广告 SDK 类；
 *   若确要认真屏蔽某家，再针对性加规则(或临时开 HARD_BLOCK)。
 * ============================================================================
 *
 * ============================================================================
 * 关闭策略说明
 * ============================================================================
 * - 判断仍用 L1【包名前缀白名单】AD_BLOCK_PREFIXES（商业广告 SDK 包名固定公开，
 *   不靠猜类名），几乎零误伤。只拦明确列出的广告包名，不碰系统/应用自身业务类/
 *   统计&推送&崩溃 SDK（除非它同时出现在广告前缀里）。
 * - 只在本 LSPosed 作用域勾选的进程内生效(Main.handleLoadPackage 调度)。
 * - 开关：ADBLOCK_ON 总开关 + MainActivity 里 I/去广告 打勾(Settings.channelOn)。
 * - 日志 [UAd]。
 * 仅用于你自己/获授权 App 的防御自测，勿用于破坏他人商业 App 的营收展示。
 */
public class AdBlockHook {

    private static final String TAG = "[UAd]";

    /** 总开关：false=完全不生效(停用去广告通道)。默认 true(启用本通道能力)。 */
    public static final boolean ADBLOCK_ON = true;

    /**
     * 硬屏蔽开关（v1.13 起默认 false = 放行观测，防闪退）：
     *   false（推荐默认）→ loadClass 命中广告前缀【放行】，只打 [UAd] 观测，绝不闪退；
     *   true → 命中即抛 ClassNotFoundException 整类屏蔽(旧版强去广告)。
     *   仅当目标 App 的广告 SDK 是懒加载/反射/带 try-catch、硬屏蔽不会崩时才开 true。
     */
    public static final boolean HARD_BLOCK = false;

    /** 只对选定包名生效(调用方也会 gate，这里留双保险)；空/null 表示任意勾选进程。 */
    public static final String TARGET_PKG = null;

    /**
     * 内置主流第三方广告 SDK 包名前缀白名单（L1 判断，不看类名猜）。
     * 命中即视为"广告 SDK 类"。覆盖国内主流 + Google AdMob。可自由增删。
     */
    private static final String[] AD_BLOCK_PREFIXES = {
            // ---- Google AdMob / AdSense ----
            "com.google.android.gms.ads.",
            "com.google.ads.",
            // ---- 穿山甲 (Pangle / ByteDance) ----
            "com.bytedance.sdk.openadsdk.",
            "com.bytedance.sdk.openadsdk",
            "com.pangle.",
            "com.bytedance.embedapplog.",   // 穿山甲的埋点辅助(常伴随广告), 视需保留
            // ---- 优量汇 / 腾讯广告 (广点通) ----
            "com.qq.e.",
            "com.qq.e.ads",
            "com.tencent.gdt.",
            "com.tencent.sigmob.",          // sigmob(腾讯系) 部分版本
            // ---- 百青藤 / 百度移动广告 ----
            "com.baidu.mobads.",
            "com.baidu.mobads",
            "com.baidu.mobad.",
            "com.baidu.ad.",
            // ---- 快手联盟 ----
            "com.kwad.",
            "com.kuaishou.ad.",
            // ---- 汇量 / Mintegral ----
            "com.mintegral.",
            "com.mobvista.",
            // ---- 其它知名聚合/广告 ----
            "com.mopub.",
            "com.flurry.",
            "com.vungle.",
            "com.applovin.",
            "com.unity3d.ads.",
            "com.unity3d.services.",        // Unity Ads / 服务
            "com.tapjoy.",
            "com.inmobi.",
            "com.chartboost.",
            "com.adcolony.",
            "com.supersonicads.",           // ironSource
            "com.ironsource.",
            "com.fyber.",
            "com.startapp.",
            "com.smaato.",
            "com.facebook.ads.",
            "com.adjust.sdk.",              // Adjust(常伴随广告归因)
            "com.topon.",                   // TopOn 聚合
            "com.anythink.",                // TopOn 新版名
            "com.zplay.",
            "com.mob.",
            "com.umeng.ads.",               // 友盟广告(视需, umeng 本体是统计勿拦)
            "com.cpm."                      // 各类 cpm 聚合
    };

    /** 已 hook 过的包名，避免重复挂。 */
    private static final Set<String> donePkg =
            Collections.synchronizedSet(new HashSet<String>());

    /** 放行模式下已观测记录的广告类（去重，防刷屏）。 */
    private static final Set<String> seenAdClass =
            Collections.synchronizedSet(new HashSet<String>());

    /** 硬屏蔽模式下累计拦截的广告类计数。 */
    private static volatile int blockedCnt = 0;

    /**
     * 入口（Main.handleLoadPackage 对每个勾选进程调用一次）。
     * 对应用 ClassLoader.loadClass 挂钩：
     *   HARD_BLOCK=false -> 命中广告前缀放行 + [UAd] 观测（防闪退默认）；
     *   HARD_BLOCK=true  -> 命中即抛 ClassNotFoundException 整类屏蔽（强去广告）。
     */
    public static void hook(ClassLoader cl, String pkg) {
        if (cl == null || pkg == null) return;
        if (TARGET_PKG != null && !TARGET_PKG.equals(pkg)) return;
        if (!donePkg.add(pkg)) return;
        // v1.12: MainActivity 打勾(I/去广告)才生效；不打勾则整个去广告通道停用。
        if (!Settings.channelOn(Settings.K_ADBLOCK)) {
            XposedBridge.log(TAG + " 去广告通道停用(MainActivity 未勾选 I/去广告) @ " + pkg);
            return;
        }
        if (!ADBLOCK_ON) {
            XposedBridge.log(TAG + " ADBLOCK_ON=false, 去广告通道停用 @ " + pkg);
            return;
        }
        XposedBridge.log(TAG + " 去广告通道挂载 @ " + pkg
                + " (" + AD_BLOCK_PREFIXES.length + " 个广告SDK前缀; "
                + (HARD_BLOCK ? "HARD_BLOCK=整类屏蔽)" : "HARD_BLOCK=false=放行观测防闪退)"));
        try {
            hookLoadClass(cl, pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadClass 钩失败: " + t);
        }
    }

    /** 对应用 loader 挂 loadClass 拦截（只挂一次，不递归到父链以免拦到框架加载）。 */
    private static void hookLoadClass(final ClassLoader cl, final String pkg) {
        XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                String.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 只处理本应用 loader 自己发起的加载，避免干扰其父链/系统
                        if (param.thisObject != cl) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof String)) return;
                        String name = ((String) a0).replace('/', '.');
                        if (!isAdPrefix(name)) return;
                        if (HARD_BLOCK) {
                            // 强去广告：整类屏蔽（仅确认目标 SDK 懒加载/有保护时才开）
                            blockedCnt++;
                            XposedBridge.log(TAG + " 屏蔽广告SDK类(HARD_BLOCK): " + name
                                    + "  (累计=" + blockedCnt + ")");
                            param.setThrowable(new ClassNotFoundException(
                                    "AdBlock: " + name));
                            return;
                        }
                        // 默认：放行 + 观测（防闪退）。类照常加载，App 不会崩。
                        if (seenAdClass.add(name)) {
                            XposedBridge.log(TAG + " 放行广告类(防闪退,未屏蔽) @ " + pkg + " : " + name
                                    + "  -- 若需屏蔽该SDK, 请确认其非硬引用后置 HARD_BLOCK=true");
                        }
                    }
                });
        XposedBridge.log(TAG + " loadClass 拦截已挂(HARD_BLOCK=" + HARD_BLOCK + ") @ " + pkg);
    }

    /** 是否命中任一广告 SDK 包名前缀。 */
    private static boolean isAdPrefix(String name) {
        if (name == null) return false;
        for (String p : AD_BLOCK_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }
}
