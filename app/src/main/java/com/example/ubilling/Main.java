package com.example.ubilling;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 通用 Billing 解锁 + 自动 VIP 拦截 模块入口。
 *
 * 【A】Google Play Billing 解锁（针对加载了 Billing SDK 的 App）
 *   不做任何包名白名单 —— 对系统里任意 App 进程都尝试挂载。只有真正加载了
 *   Billing SDK 的 App 才有意义：存在 BillingClient -> 挂载通用 Billing Hook；
 *   否则静默跳过。
 *
 * 【B】全兼容自动 VIP 拦截 + 观测学习（UniversalVipSweeper）
 *   在 handleLoadPackage() 目标进程内挂载一次：拦截 android.app.SharedPreferencesImpl 的
 *   getString/getBoolean/getInt/getLong/getFloat/getStringSet。key 命中多套语义
 *   （付费/会员/PRO/解锁/去广告/到期）时按 getXxx 返回类型自动塞解锁值，
 *   其中“到期/有效期”类读取一律回 2099-01-01，令“未过期”判断恒成立。
 *   与 Billing 无关 —— 对“把付费态存本地 SP、本地判断即解锁”的 App
 *   （含不带 Billing SDK 的国内 App）都尝试生效。
 *   观测学习：首次命中时会扫该 App 自己的 shared_prefs/*.xml 与运行读取，
 *   把“确实存在会员/日期形态的 key”写入内存规则表（扩大匹配面——即使 key 完全
 *   不含词表关键词也能命中），并把观测记录写到 /data/data/&lt;该App&gt;/files/uvip/
 *   （records.txt / hits.log），供查看与人工调参。
 *
 *   ★ 作用域约束（重要，v14 起）：
 *   全部通道（含 SP 拦截【B】）现在【只】在 LSPosed 作用域勾选的 App 进程内生效。
 *   不再在 initZygote 期对全系统挂载 SP 钩子——否则会对【所有】进程（系统 app、
 *   其它应用、system_server 等）都拦截 SharedPreferences，既越权又造成误伤。
 *   现在 handleLoadPackage 只在“本模块作用域勾选的 App 及其子进程”被 LSPosed 调用，
 *   因此 B/D/E/F/G 通通只操作你勾选的 App，绝不动其它应用。
 *   副作用：若想对某 App 的 SP 型会员解锁，须把该 App 勾进作用域并重启生效。
 *
 * 【D】联网鉴权抗 HOOK 自测通道（NetLabHook，仅授权自测）
 *   模拟“破解方针对服务端/联网鉴权型 App”的攻击面：OkHttp 响应篡改(T1)、
 *   SSL pinning 探测(T2)、WebView JS 注入面(T3)。默认 LOG_ONLY 只观测不打日志外
 *   任何改；填 NetLabHook.REPLACEMENTS / NEEDLE_JS 并关 LOG_ONLY 后重建，可对自己
 *   的 App 验证“改响应/注入 JS 能否得逞”，据此加固服务端。请勿用于破解他人服务。
 *
 * 【E】配置化精确返回值 Hook（MethodRuleHook，仅授权自测）
 *   支持“按 类.方法 -> 指定返回值”强制改写 App 里某个具名业务方法的返回。
 *   覆盖“会员态由 cn.ms.util.CommonUtil.getLingPaiZuanShi() 这类【具名方法】判定、
 *   不走 SP/Billing/构造器”的自有 App。规则配在 MethodRuleHook.RULES
 *   （{类名, 方法名, 返回值, 参数类型可选}），返回值按方法真实返回类型自动转换。
 *   中性技术能力，仅用于你自己/获授权 App 的防御自测，勿配规则去破解他人收费服务。
 *
 * 【F】全 VIP/PRO 自动盲扫通道（AutoVipProHook，仅授权自测）
 *   不靠人工配置，按【方法名强词表】自动遍历目标 App 已加载类的无参 getter/isXxx/
 *   hasXxx，找出“会员判定方法”(isVip/isPro/isPremium/getVipLevel 等)并强制改写，
 *   覆盖“会员态由某个具名 getter 返回、不走 SP/Billing/人工配置”的自有 App。
 *   v1.8 并入原 C(ProActivator) 的“结构盲扫”：不看类名/方法名，纯按会员状态对象
 *   构造器结构 (boolean,Enum,long,boolean) 对每个已加载类自动探测挂钩，内存对象型
 *   （类名/方法名全混淆如指尖3D）亦命中。
 *   v1.10 起为【两级注入闸门】(替代 v1.9 单一 LOG_ONLY 全开)：
 *     · 第一级恒注入(不读开关, 近零误伤)：结构盲扫 (Z,Enum,J,Z) + STRONG_BOOL 精确
 *       整词解锁位(isPro/isVip/isPremium…) —— 指尖3D 这类内存对象会员态开箱即解锁。
 *     · 第二级宽泛受 INJECT_WIDE 控制(默认 false=仅观测打 [UAuto] 不改值)：inVipContext
 *       放宽布尔 / 档位到期 getter / 静态字段 / 单例实例字段。需连宽泛也注入时置
 *       AutoVipProHook.INJECT_WIDE=true 重建。
 *   请在 LSPosed 只勾选自己/获授权 App。仅用于你自己/获授权 App 的防御自测。
 *
 * 【G】SQLite / DB 会员盲扫通道（DBSweeperHook，仅授权自测）
 *   覆盖“会员态存在本地 SQLite/Room 表、判定时 SELECT 出来比”的 App。hook
 *   SQLiteDatabase.rawQuery/query 出口 + AbstractCursor 的 getString/getInt/getLong，
 *   按【列名语义】把“布尔会员位列/等级列”读取改写为开通态；到期列因秒/毫秒二义
 *   只观测不强注入。默认 LOG_ONLY=true 只打 [UDB] 观测。仅自有/授权 App 自测。
 *
 * 【I】网络层去广告通道（NetAdBlocker，v1.14 重做）
 *   你提供 Close_3.9.3.apk=AdClose(著名开源去广告模块)做参考，按它的核心有效做法重写：
 *   不再 hook 应用 ClassLoader.loadClass 去"屏蔽广告 SDK 类"(拦不住联网下发、硬引用会崩，
 *   v1.13 已默认放行=等于没拦)。改为【网络层域名拦截】：在本进程内 hook
 *   java.net.InetAddress 的 DNS 解析出口(getAllByName/getByName)，命中广告域名就让它
 *   "解析失败"(getAllByName 返回空数组 / 其它抛 UnknownHostException)。广告 SDK 无论用
 *   OkHttp/HttpURLConnection/Socket，请求前都要解析广告域名，这一刀掐断 → 广告无物料可渲染。
 *   黑名单数据：内置 AdClose 17,475 条离线清单(assets/adblock_domains.txt) + 可配置 URL
 *   在线更新(设置页填 adblock_url)。匹配安全(只拦命中域名，白域名/系统/业务正常解析不受影响)。
 *   全逻辑 try/catch、只挂一次，面向加固目标也绝不闪退。UI 里 I/去广告 打勾仍作总开关。
 *
 * ============================================================================
 * v1.14 变更：删除 AdBlockHook.java(旧 loadClass 屏蔽广告类法)，新写 NetAdBlocker +
 * BlockDomainStore。AdGuard.java 保留（它另用于防 VIP 注入把广告激活，独立互补）。
 *
 * ============================================================================
 * v1.12 新增：
 *   1) 【每通道打勾总开关】—— 打开模块的 MainActivity(桌面"kill vip"图标)，
 *      为 A/Billing / B/UVip / D/NetLab / E/MethodRule / F/Auto / G/DB / I/去广告
 *      各勾一个开关。勾选→该通道对作用域内勾选 App 生效；不打勾→该通道完全不挂载。
 *      勾选存模块 SharedPreferences("ubilling_settings")，被 hook 的 App 进程用
 *      XSharedPreferences 读到（Settings.channelOn）。改完需软重启/重启目标 App。
 *      默认全部勾选(向后兼容：不打开 UI 时行为与 v1.11 一致)。
 *   2) 【广告护栏 AdGuard】—— 防止 VIP 注入把广告激活：B/F/G 在注入前判定候选是否
 *      属广告服务控制上下文(广告SDK类/广告开关类SP key/方法/列)，是则跳过注入并打
 *      [UAdGuard] 观测日志，便于用户实测是哪个通道把哪个广告相关东西判成了会员。
 *      与去广告通道 I(AdBlockHook)互补。
 *
 * 用法：LSPosed 作用域勾选目标 App（各通道是否生效 = 作用域勾选 ∧ MainActivity 里该通道
 * 打勾；都只在勾选 App 的进程内生效）。日志 [UVip]/[UBilling]/[UNet]/[URule]/[UAuto]/
 * [UDB]/[UAd]；广告护栏跳过 [UAdGuard]。
 */
public class Main implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 跳过系统进程自身与无关框架，减小开销。
        // 注意：LSPosed 只对本模块“作用域”勾选的 App 调用 handleLoadPackage，
        // 因此下列所有通道天然只操作被勾选的应用，绝不对未勾选应用生效。
        if (lpparam.packageName == null) return;
        // 跳过 LSPosed / Xposed 自身，避免自递归
        if (lpparam.packageName.startsWith("org.lsposed.")
                || lpparam.packageName.equals("com.example.ubilling")) {
            return;
        }
        final ClassLoader cl = lpparam.classLoader;
        if (cl == null) return;

        // ==================================================================
        // 每通道总开关(v1.12)：MainActivity 里打勾才让该通道对作用域 App 生效。
        // 默认 true(向后兼容)。改完勾选需软重启/重启目标 App 才会让新配置进到本进程。
        // ==================================================================
        boolean onBilling    = Settings.channelOn(Settings.K_BILLING);
        boolean onUVip       = Settings.channelOn(Settings.K_UVIP);
        boolean onNetLab     = Settings.channelOn(Settings.K_NETLAB);
        boolean onMethodRule = Settings.channelOn(Settings.K_METHODRULE);
        boolean onAutoVip    = Settings.channelOn(Settings.K_AUTOVIP);
        boolean onDB         = Settings.channelOn(Settings.K_DB);
        boolean onAdBlock    = Settings.channelOn(Settings.K_ADBLOCK);
        XposedBridge.log("[killvip] 通道开关 @ " + lpparam.packageName
                + " A/Billing=" + onBilling + " B/UVip=" + onUVip
                + " D/NetLab=" + onNetLab + " E/MethodRule=" + onMethodRule
                + " F/Auto=" + onAutoVip + " G/DB=" + onDB + " I/AdBlock=" + onAdBlock);

        // 【B】全兼容自动 VIP 拦截（SP 多语义 + 观测学习）。
        // v14 起【不再】在 initZygote 全系统挂载，而是改在此处按目标进程挂载——
        // 借 LSPosed 的作用域机制，让 SP 拦截只作用于被勾选的 App，不动其它应用。
        // v1.12：MainActivity 不打勾(B/UVip)则整个 B 通道不挂载。
        if (onUVip) {
            try {
                UniversalVipSweeper.hook();
            } catch (Throwable t) {
                XposedBridge.log("[UVip] 挂载失败: " + t);
            }
        }

        // 【C】通道已移除(v1.7)：ProActivator(指尖3D 专用定向白名单) 不再单列；
        //     v1.8 已把其【结构盲扫】(内存对象构造器签名, 不看类名) 并入 F(AutoVipProHook),
        //     对所有勾选 App 自动生效，无需白名单、无需人工配置。

        // 【D】联网鉴权抗 hook 自测通道（NetLabHook）——对每个勾选进程尝试；
        //     内部按“是否加载 okhttp3/WebView”自动决定挂哪些面，无对应类即静默跳过。
        //     默认只观测(LOG_ONLY=true)；自测实战需改 NetLabHook 常量并重建。
        //     v1.12：MainActivity 不打勾(D/NetLab)则跳过。
        if (onNetLab) {
            try {
                NetLabHook.hook(cl, lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log("[UNet] 挂载失败: " + t);
            }
        }

        // 【E】配置化精确返回值 Hook（MethodRuleHook）——按 MethodRuleHook.RULES
        //     里“类.方法 -> 返回值”精确改写；规则为空则静默跳过。仅自有/授权 App 自测。
        //     v1.12：MainActivity 不打勾(E/MethodRule)则跳过。
        if (onMethodRule) {
            try {
                MethodRuleHook.hook(cl, lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log("[URule] 挂载失败: " + t);
            }
        }

        // 【G】SQLite/DB 会员盲扫通道（DBSweeperHook）——hook SQLiteDatabase 出口 +
        //     AbstractCursor 读取，按列名语义改写会员列。默认 LOG_ONLY 只打 [UDB] 观测。
        //     v1.12：MainActivity 不打勾(G/DB)则跳过。
        if (onDB) {
            try {
                DBSweeperHook.hook(cl, lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log("[UDB] 挂载失败: " + t);
            }
        }

        // 【F】全 VIP/PRO 自动盲扫通道（AutoVipProHook）——按方法名强词表自动发现并
        //     改写会员判定 getter，并入结构盲扫(内存对象构造器签名)。v1.10 两级闸门：
        //     结构(Z,Enum,J,Z)+STRONG_BOOL 恒注入；宽泛(inVipContext/档位getter/字段扫)
        //     受 INJECT_WIDE(默认 false=仅观测)。仅自有/授权 App 自测。
        //     v1.12：MainActivity 不打勾(F/Auto)则跳过。
        if (onAutoVip) {
            try {
                AutoVipProHook.hook(cl, lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log("[UAuto] 挂载失败: " + t);
            }
        }

        // 【I】网络层去广告通道（NetAdBlocker，v1.14）——hook InetAddress DNS，命中
        //     广告域名令其解析失败，掐断广告 SDK 联网要物料那一跳。黑名单=内置 AdClose
        //     离线清单 + 可配置 URL 在线更新(见 BlockDomainStore / 设置页 adblock_url)。
        //     全逻辑 try/catch、只挂一次，面向加固目标也绝不闪退。
        //     v1.12：MainActivity 不打勾(I/去广告)则整个去广告通道不挂载。
        if (onAdBlock) {
            try {
                NetAdBlocker.hook(cl, lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log("[UAd] 挂载失败: " + t);
            }
        }

        // 【H】通道已移除(v1.6)：TimeFreezeHook(时间冻结/拨回) 经评估不再需要，
        //     避免对进程当前时间做全局回调带来的副作用。仅保留 G(DB盲扫)+F(自动盲扫)。

        // 探测目标进程是否真的用了 Google Play Billing SDK
        if (!onBilling) {
            return; // v1.12: MainActivity 不打勾(A/Billing)，连探测也跳过
        }
        boolean hasBilling;
        try {
            Class.forName("com.android.billingclient.api.BillingClient", false, cl);
            hasBilling = true;
        } catch (ClassNotFoundException e) {
            hasBilling = false;
        }
        if (!hasBilling) {
            return; // 普通 App：无 Billing SDK，直接跳过，不刷日志
        }

        try {
            UniversalBillingHook.hook(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[UBilling] 挂载失败: " + t);
        }
    }
}
