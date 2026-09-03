package com.example.ubilling;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块运行期配置读取器（v1.12 新增）—— 让每个 HOOK 通道 / 去广告功能都由模块 UI 里
 * 的"打勾开关"决定：在 UI 打勾 → 该通道对作用域内勾选 App 生效；不打勾 → 完全不挂载。
 *
 * ============================================================================
 * 跨进程配置链路（模块 UI 进程 ⇄ 被 hook 的目标 App 进程）：
 *   · 写：模块自己的 MainActivity 用普通 SharedPreferences 写
 *         "ubilling_settings"（MODE_WORLD_READABLE，LSPosed 会帮忙使其可读）。
 *   · 读：本类在被 hook 的【目标 App 进程】内，用 de.robv.android.xposed.
 *         XSharedPreferences("com.example.ubilling", "ubilling_settings")
 *         直接读模块那份 pref XML（LSPosed / Xposed 框架在 zygote 里具备跨 uid
 *         读取模块数据目录的权限，故目标进程能读到模块配置）。
 *   · 生效时机：改完勾选后需【软重启 / 重启被 hook 的 App】才让新配置进到目标进程；
 *        本类每次读取前用 hasFileChanged()+reload() 感知文件变化（节流），
 *        故进程重载后读到的是最新勾选。
 *
 * ============================================================================
 * 键值与默认（关键：默认一律 true —— 向后兼容）
 *   若用户从未打开 UI，或 XSharedPreferences 因故读不到（文件不可读），
 *   全部通道保持开启，行为与 v1.11 完全一致，绝不因配置缺失而静默关掉解锁。
 *   用户要停用某通道，就在 UI 取消对应勾选并重启目标 App。
 *   键名：
 *     ch_billing    【A】Google Play Billing 解锁
 *     ch_uvip       【B】SharedPreferences 全兼容自动 VIP（含观测学习）
 *     ch_netlab     【D】联网鉴权抗 hook 自测（NetLabHook）
 *     ch_methodrule 【E】具名方法返回值改写（MethodRuleHook）
 *     ch_autovip    【F】VIP/PRO 自动盲扫（AutoVipProHook）
 *     ch_db         【G】SQLite/DB 会员盲扫（DBSweeperHook）
 *     ch_adblock    【I】第三方广告 SDK 去广告（AdBlockHook）
 *   （A 只在目标进程真加载 Billing SDK 时才有意义；通道开启仅代表"允许挂载"。）
 *
 * 线程/开销：本类只在通道入口 / 注入判定调用，读取走缓存 + 节流 reload，
 *   不在性能热路径里反复建实例（官方也建议别在热路径 new/频繁 reload）。
 */
public final class Settings {

    /** 模块包名（写 XSharedPreferences 的第一参）。 */
    public static final String MODULE_PKG = "com.example.ubilling";
    /** 与 MainActivity 约定同一个 pref 文件名（不带 .xml）。 */
    public static final String PREFS_FILE = "ubilling_settings";

    /** ===== 各通道开关键 ===== */
    public static final String K_BILLING    = "ch_billing";
    public static final String K_UVIP       = "ch_uvip";
    public static final String K_NETLAB     = "ch_netlab";
    public static final String K_METHODRULE = "ch_methodrule";
    public static final String K_AUTOVIP    = "ch_autovip";
    public static final String K_DB         = "ch_db";
    public static final String K_ADBLOCK    = "ch_adblock";

    /** 只读偏好句柄（懒建一次，后续 reload）。 */
    private static volatile XSharedPreferences sPrefs = null;
    private static volatile long sLastReload = 0L;
    private static final long RELOAD_THROTTLE_MS = 700L; // 每次命中前至多 reload 一次/700ms

    private Settings() {
    }

    /**
     * 读取某通道开关。缺省 / 读不到一律按 true（向后兼容，见类头说明）。
     * 每次调用：若距上次检查超过节流窗口且底层文件有变，则 reload 一次。
     */
    public static boolean channelOn(String key) {
        XSharedPreferences p = prefs();
        if (p == null) return true;               // 读不到 -> 默认开（安全向后兼容）
        try {
            maybeReload(p);
            return p.getBoolean(key, true);       // 键不存在默认 true
        } catch (Throwable t) {
            return true;                          // 任何异常都退回"开"，不因读配置失败静默关通道
        }
    }

    /**
     * 便捷：判断任一配置项（除通道开关外，也可放别的布尔）。与 channelOn 同语义。
     */
    public static boolean bool(String key, boolean def) {
        XSharedPreferences p = prefs();
        if (p == null) return def;
        try {
            maybeReload(p);
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------
    private static XSharedPreferences prefs() {
        XSharedPreferences p = sPrefs;
        if (p != null) return p;
        synchronized (Settings.class) {
            p = sPrefs;
            if (p != null) return p;
            try {
                p = new XSharedPreferences(MODULE_PKG, PREFS_FILE);
                if (p != null && p.getFile() != null && p.getFile().canRead()) {
                    sPrefs = p;
                } else {
                    // 文件暂时不可读（可能模块刚装/未开过 UI）：记录为空，调用方退回默认
                    return null;
                }
            } catch (Throwable t) {
                return null;
            }
        }
        return sPrefs;
    }

    /** 节流 reload：底层文件有变才重读，最多 RELOAD_THROTTLE_MS 一次。 */
    private static void maybeReload(XSharedPreferences p) {
        long now = System.currentTimeMillis();
        if (now - sLastReload < RELOAD_THROTTLE_MS) return;
        sLastReload = now;
        try {
            if (p.hasFileChanged()) {
                p.reload();
            }
        } catch (Throwable ignore) {
        }
    }
}
