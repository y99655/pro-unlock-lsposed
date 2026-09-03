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
 *   默认 LOG_ONLY=true 只观测打 [UAuto] 日志，绝不改值 —— 先跑一轮看命中清单、
 *   确认无误伤后，再把 AutoVipProHook.LOG_ONLY 置 false 重载模块做真实注入。
 *   仅用于你自己/获授权 App 的防御自测。
 *
 * 【G】SQLite / DB 会员盲扫通道（DBSweeperHook，仅授权自测）
 *   覆盖“会员态存在本地 SQLite/Room 表、判定时 SELECT 出来比”的 App。hook
 *   SQLiteDatabase.rawQuery/query 出口 + AbstractCursor 的 getString/getInt/getLong，
 *   按【列名语义】把“布尔会员位列/等级列”读取改写为开通态；到期列因秒/毫秒二义
 *   只观测不强注入。默认 LOG_ONLY=true 只打 [UDB] 观测。仅自有/授权 App 自测。
 *
 * 用法：LSPosed 作用域勾选目标 App（B/D/E/F/G 全通道都只在勾选 App 的进程内
 * 生效，不再对未勾选应用操作），软重启后看日志 [UVip] / [UBilling] / [UNet]
 * / [URule] / [UAuto] / [UDB]。
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

        // 【B】全兼容自动 VIP 拦截（SP 多语义 + 观测学习）。
        // v14 起【不再】在 initZygote 全系统挂载，而是改在此处按目标进程挂载——
        // 借 LSPosed 的作用域机制，让 SP 拦截只作用于被勾选的 App，不动其它应用。
        try {
            UniversalVipSweeper.hook();
        } catch (Throwable t) {
            XposedBridge.log("[UVip] 挂载失败: " + t);
        }

        // 【C】通道已移除(v1.7)：ProActivator(指尖3D 专用定向白名单) 不再单列；
        //     v1.8 已把其【结构盲扫】(内存对象构造器签名, 不看类名) 并入 F(AutoVipProHook),
        //     对所有勾选 App 自动生效，无需白名单、无需人工配置。

        // 【D】联网鉴权抗 hook 自测通道（NetLabHook）——对每个勾选进程尝试；
        //     内部按“是否加载 okhttp3/WebView”自动决定挂哪些面，无对应类即静默跳过。
        //     默认只观测(LOG_ONLY=true)；自测实战需改 NetLabHook 常量并重建。
        try {
            NetLabHook.hook(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[UNet] 挂载失败: " + t);
        }

        // 【E】配置化精确返回值 Hook（MethodRuleHook）——按 MethodRuleHook.RULES
        //     里“类.方法 -> 返回值”精确改写；规则为空则静默跳过。仅自有/授权 App 自测。
        try {
            MethodRuleHook.hook(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[URule] 挂载失败: " + t);
        }

        // 【G】SQLite/DB 会员盲扫通道（DBSweeperHook）——hook SQLiteDatabase 出口 +
        //     AbstractCursor 读取，按列名语义改写会员列。默认 LOG_ONLY 只打 [UDB] 观测。
        try {
            DBSweeperHook.hook(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[UDB] 挂载失败: " + t);
        }

        // 【F】全 VIP/PRO 自动盲扫通道（AutoVipProHook）——按方法名强词表自动发现并
        //     改写会员判定 getter。默认 LOG_ONLY=true 只观测打 [UAuto]，不改值。
        //     确认无误伤后置 AutoVipProHook.LOG_ONLY=false 重建做真实注入。仅自有/授权自测。
        try {
            AutoVipProHook.hook(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[UAuto] 挂载失败: " + t);
        }

        // 【H】通道已移除(v1.6)：TimeFreezeHook(时间冻结/拨回) 经评估不再需要，
        //     避免对进程当前时间做全局回调带来的副作用。仅保留 G(DB盲扫)+F(自动盲扫)。

        // 探测目标进程是否真的用了 Google Play Billing SDK
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
