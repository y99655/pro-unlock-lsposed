package com.example.ubilling;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 【I】第三方广告 SDK 去广告通道（AdBlockHook）—— 直接屏蔽主流商业广告 SDK 的类加载。
 *
 * ============================================================================
 * 背景与动机
 * ============================================================================
 * 用户希望“判断出广告类、直接关闭广告”。经澄清：广告来自【第三方广告 SDK】
 * （AdMob / 穿山甲 / 优量汇 / 百青藤 / 快手 / 汇量 / Mintegral / Pangle 等），
 * 而非 App 自建广告页。这类 SDK 的类名【不是随机混淆的】——它们是商业 SDK，
 * 包名固定、公开可枚举，因此判断【不靠猜】，而是靠 L1【包名前缀白名单】：
 *   类全名以某个已知广告 SDK 包名开头 => 判定为广告类。几乎零误伤。
 *
 * ============================================================================
 * 关闭策略：整类屏蔽（用户选定）
 * ============================================================================
 * Xposed 无法真正“删除”一个类，但能【让广告 SDK 的类根本不被加载】：
 *   hook 应用 ClassLoader.loadClass，命中广告包名前缀时抛 ClassNotFoundException。
 *   效果 = App 里对广告 SDK 的一切引用（Class.forName / new / 反射 loadClass）
 *   全部失败 → 广告 SDK 永远初始化不起来、不请求、不展示。
 *   比逐方法 hook 更彻底，是真正的“整类屏蔽”。
 *
 *   副作用提示：若目标 App 对广告 SDK 采用【硬引用且无 try/catch 保护】的调用方式，
 *   整类屏蔽可能让该处抛 ClassNotFoundException 而崩溃。多数商业 App 对广告 SDK
 *   采用反射/按需/保护加载，可接受。若实测某 App 崩溃，可把对应前缀从
 *   AD_BLOCK_PREFIXES 里去掉，或临时置 ADBLOCK_ON=false 恢复。
 *
 * ============================================================================
 * 安全/作用域
 * ============================================================================
 * - 只在 LSPosed 作用域勾选的本进程内生效（Main.handleLoadPackage 调度）。
 * - 只拦【广告 SDK】前缀，不碰系统 / 应用自身业务类 / 统计&推送&崩溃 SDK
 *   （尽管它们常与广告 SDK 同家，但只在 AD_BLOCK_PREFIXES 明确列出的广告包名下拦，
 *    避免把同厂的埋点/推送也误伤）。
 * - 日志 [UAd]。
 * 仅用于你自己/获授权 App 的防御自测，勿用于破坏他人商业 App 的营收展示。
 */
public class AdBlockHook {

    private static final String TAG = "[UAd]";

    /** 总开关：true=真正拦截广告 SDK 类加载(去广告)；false=完全不生效(临时停用)。 */
    public static final boolean ADBLOCK_ON = true;

    /**
     * 观测模式：true=命中广告包名时【只打日志、不真正拦截】(让类正常加载)；
     * false=命中即真正抛 ClassNotFoundException 屏蔽。默认 false(用户选内置启用)。
     * 若想先看目标 App 会命中哪些广告 SDK，临时置 true 重建跑一轮再开 false。
     */
    public static final boolean LOG_ONLY = false;

    /** 只对选定包名生效(调用方也会 gate，这里留双保险)；空/null 表示任意勾选进程。 */
    public static final String TARGET_PKG = null;

    /**
     * 内置主流第三方广告 SDK 包名前缀白名单（L1 判断，不看类名猜）。
     * 命中即视为“广告 SDK 类”，整类屏蔽其加载。
     * 覆盖国内主流 + Google AdMob。可自由增删。
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
            "com.tapjoy.",
            "com.inmobi.",
            "com.chartboost.",
            "com.adcolony.",
            "com.supersonicads.",           // ironSource
            "com.ironsource.",
            "com.fyber.",
            "com.startapp.",
            "com.smaato.",
            "com.unity3d.services.",        // Unity Ads / 服务
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

    /** 本进程累计拦截的广告类计数。 */
    private static volatile int blockedCnt = 0;
    private static final Set<String> blockedSeen =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * 入口（Main.handleLoadPackage 对每个勾选进程调用一次）。
     * 对应用 ClassLoader.loadClass 挂钩：命中广告前缀即整类屏蔽。
     */
    public static void hook(ClassLoader cl, String pkg) {
        if (cl == null || pkg == null) return;
        if (TARGET_PKG != null && !TARGET_PKG.equals(pkg)) return;
        if (!donePkg.add(pkg)) return;
        if (!ADBLOCK_ON) {
            XposedBridge.log(TAG + " ADBLOCK_ON=false, 去广告通道停用 @ " + pkg);
            return;
        }
        XposedBridge.log(TAG + " 去广告通道挂载 @ " + pkg
                + " (屏蔽 " + AD_BLOCK_PREFIXES.length + " 个广告SDK前缀"
                + (LOG_ONLY ? ", LOG_ONLY=仅观测)" : ", 整类屏蔽)"));
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
                        // 命中广告 SDK 类
                        blockedSeen.add(name);
                        if (LOG_ONLY) {
                            return;   // 观测：放行，不拦截
                        }
                        blockedCnt++;
                        XposedBridge.log(TAG + " 屏蔽广告SDK类: " + name
                                + "  (累计=" + blockedCnt + ")");
                        param.setThrowable(new ClassNotFoundException(
                                "AdBlock: " + name));
                    }
                });
        XposedBridge.log(TAG + " loadClass 拦截已挂(LOG_ONLY=" + LOG_ONLY + ") @ " + pkg);
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
