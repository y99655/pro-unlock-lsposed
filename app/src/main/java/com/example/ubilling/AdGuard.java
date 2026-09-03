package com.example.ubilling;

import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 广告上下文识别护栏（v1.12 新增）—— 防止 VIP/PRO 解锁注入【把广告激活】。
 *
 * ============================================================================
 * 背景（用户反馈："之前的通道会激活部分广告"）
 *   SP 盲扫(B/UVip)、自动盲扫(F/Auto)、DB 盲扫(G/DB) 是按【语义关键词 / 类名 / 方法名】
 *   猜"会员态"。但广告 SDK 内部也有大量【形似会员开关】的标识：
 *     - SP key：banner_enable / splash_switch / ad_show_enabled / cpm_init /
 *                interstitial_ready / reward_switch …（名字带开关词，词表可能判成解锁）
 *     - 方法：ad 对象的 isEnabled()/isActive()/isShow()/load()/show() …（判成会员态强制 true）
 *     - 类：banner/AdLoader/AdManager/RewardVideo …（vipCls 误判放宽后可能被扫）
 *     - DB 列：ad_cache / ads_state / is_ad_ready …
 *   当注入把这类【广告服务控制位】设成"开/已就绪/展示"时，反而把广告放了出来。
 *
 *   本护栏在两处作用（与【I】AdBlockHook 整类屏蔽互补）：
 *     A) VIP 解锁通道(B/F/G) 在决定注入【之前】问一次：这个候选是不是"广告服务上下文"？
 *        是 -> 跳过注入，不把广告激活；打一条 [UAdGuard] 观测日志(仅观测、绝不注入)。
 *     B) F 通道的类遍历里，广告 SDK 的类整类不当作会员类扫（靠 isAdClass）。
 *
 *   观测价值：护栏每次"本可命中但因广告上下文被跳过"都会打 [UAdGuard]，配合 [UVip]/
 *   [UAuto]/[UDB] 的命中日志，用户能直接看出"是哪个通道把哪个广告相关的东西判成了会员"，
 *   从而实测定责（用户要求"先把钩子加上，我实测是哪个激活的广告"）。
 *
 *   ================================================================
 *   重要边界：本类判定的是【广告 SDK / 广告服务本身】的上下文，用于"别去激活广告"。
 *   它不拦截、不屏蔽任何广告 —— 真正的去广告(整类屏蔽)由 AdBlockHook【I】负责。
 *   与 AdBlockHook 的包名前缀白名单是同一套广告包名库，故两处判定口径一致。
 *   ================================================================
 */
public final class AdGuard {

    /** 日志前缀（与 AdBlockHook 的 [UAd] 区分：本类是"别误激活广告"护栏）。 */
    public static final String TAG = "[UAdGuard]";

    /**
     * 主流第三方广告 SDK 包名前缀（与 AdBlockHook.AD_BLOCK_PREFIXES 同口径，
     * 覆盖 AdMob/穿山甲/优量汇/百青藤/快手/汇量/Mintegral…）。命中类全名即认为
     * 该类属于广告 SDK —— 其成员【绝不】按会员态注入，类也【不】当会员类扫。
     */
    private static final String[] AD_SDK_PACKAGES = {
            // Google AdMob / AdSense
            "com.google.android.gms.ads.", "com.google.ads.",
            // 穿山甲 / Pangle / ByteDance
            "com.bytedance.sdk.openadsdk", "com.pangle.", "com.bytedance.embedapplog.",
            // 优量汇 / 腾讯广告 / sigmob
            "com.qq.e.", "com.qq.e.ads", "com.tencent.gdt.", "com.tencent.sigmob.",
            // 百青藤 / 百度移动广告
            "com.baidu.mobads.", "com.baidu.mobad.", "com.baidu.ad.",
            // 快手联盟
            "com.kwad.", "com.kuaishou.ad.",
            // 汇量 / Mintegral
            "com.mintegral.", "com.mobvista.",
            // 其它聚合/独立广告
            "com.mopub.", "com.flurry.", "com.vungle.", "com.applovin.",
            "com.unity3d.ads.", "com.unity3d.services.", "com.tapjoy.",
            "com.inmobi.", "com.chartboost.", "com.adcolony.",
            "com.supersonicads.", "com.ironsource.", "com.fyber.", "com.startapp.",
            "com.smaato.", "com.facebook.ads.", "com.adjust.sdk.",
            "com.topon.", "com.anythink.", "com.zplay.", "com.mob.", "com.cpm."
    };

    /**
     * 广告服务【控制/激活/展示/加载/配置】语义词。SP key / 方法名 / DB 列名转小写后，
     * 若同时含"广告词"与这些"开关词"，则很可能是"广告要不要展示/已就绪"的控制位，
     * 注入它等于打开广告 -> 一律跳过。这是防"激活广告"的核心判定。
     */
    private static final String[] AD_CONTROL = {
            "banner", "interstitial", "rewardvideo", "reward_video", "splashad",
            "splash_ad", "adshow", "ad_show", "adload", "ad_load", "adsload",
            "showad", "show_ad", "loadad", "load_ad", "openads", "adopen",
            "ad_req", "adreq", "ad_fetch", "adfetch", "ad_preload", "adcache",
            "ad_cache", "ads_state", "ad_state", "ad_active", "adready",
            "ad_ready", "ads_ready", "ad_switch", "adswitch", "cpm", "ad_enable",
            "adblock"   // adblock 在 SP 语义里是"已去广告"(remove_ads 副产物)——这是用户要的真去广告
    };

    /** 广告上下文里还会出现的名词词根（帮上面 AD_CONTROL 兜底：ad_xxx 且带这些也能归广告）。 */
    private static final String[] AD_HINT = {
            "advert", "advertisement", "ad_", "ads", "_ad", "adid", "advert_id"
    };

    private AdGuard() {
    }

    /**
     * 一个类是否属于广告 SDK（F 通道用它把广告类整类排除出会员类扫描）。
     * 只看包名前缀，不看名字猜。
     */
    public static boolean isAdSdkClass(String className) {
        if (className == null) return false;
        for (String p : AD_SDK_PACKAGES) {
            if (className.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * SP key / 方法名 / DB 列名（转小写后）是否属于"广告服务控制/激活"上下文。
     * 规则：命中广告词根(ad/ads/advert…)，且命中开关/展示词(banner/load/show/switch/
     * cpm/enable/ready/state…) 才算 —— 避免把用户业务里普通含 "ad" 的字段误伤。
     */
    public static boolean isAdControlToken(String lowToken) {
        if (lowToken == null) return false;
        // 先硬命中广告控制组合词（banner_enable / splashad / ad_load …）
        for (String c : AD_CONTROL) {
            if (lowToken.contains(c)) {
                // 组合词本身已足够强，直接判广告控制；但要排除用户真会员"已去广告"类
                if (lowToken.contains("remove_ads") || lowToken.contains("noads")
                        || lowToken.contains("no_ads") || lowToken.contains("ad_free")
                        || lowToken.contains("adfree") || lowToken.contains("ads_removed")
                        || lowToken.contains("disable_ads")) {
                    return false;   // 这是"付费去广告"开关，注入它=去广告，不是开广告
                }
                return true;
            }
        }
        // 兜底：带广告词根 + 命中任一控制词根
        boolean isAd = false;
        for (String h : AD_HINT) {
            if (lowToken.contains(h)) { isAd = true; break; }
        }
        if (!isAd) return false;
        // 普通含 ad 的名词(如 add/adapter/address)别误判 —— 需要再带广告语义强词
        return lowToken.contains("banner") || lowToken.contains("splash")
                || lowToken.contains("interstitial") || lowToken.contains("reward")
                || lowToken.contains("adshow") || lowToken.contains("showad")
                || lowToken.contains("adload") || lowToken.contains("loadad")
                || lowToken.contains("openads") || lowToken.contains("cpm")
                || lowToken.contains("ads_") || lowToken.contains("_ad_")
                || lowToken.contains("advert") || lowToken.contains("advertisement");
    }

    /**
     * 便捷：把任意字符串(SP key / 方法名 / 列名)转小写后判断是否广告控制。
     */
    public static boolean isAdControl(String raw) {
        return raw != null && isAdControlToken(raw.toLowerCase(Locale.ROOT));
    }

    /**
     * 观测日志：某 VIP 注入通道本可命中某候选，但因它属广告服务上下文被【跳过注入】。
     * 打 [UAdGuard]，让用户能对照 [UVip]/[UAuto]/[UDB] 判定"哪个通道把广告当会员了"。
     * kind 建议传 "SP"/"方法"/"字段"/"DB列"/"类" 等；from 传通道名(B/F/G)。
     */
    public static void logSkipped(String from, String kind, String candidate) {
        XposedBridge.log(TAG + " [" + from + "] 跳过注入(广告服务上下文, 防激活广告) " + kind
                + "=" + candidate);
    }
}
