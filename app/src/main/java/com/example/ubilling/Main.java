package com.example.ubilling;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam;

/**
 * 通用 Billing 解锁 + 自动 VIP 拦截 模块入口。
 *
 * 【A】Google Play Billing 解锁（针对加载了 Billing SDK 的 App）
 *   不做任何包名白名单 —— 对系统里任意 App 进程都尝试挂载。只有真正加载了
 *   Billing SDK 的 App 才有意义：存在 BillingClient -> 挂载通用 Billing Hook；
 *   否则静默跳过。
 *
 * 【B】全兼容自动 VIP 拦截 + 观测学习（UniversalVipSweeper，任意 App 通用）
 *   在 initZygote() 系统级挂载一次：拦截 android.app.SharedPreferencesImpl 的
 *   getString/getBoolean/getInt/getLong/getFloat/getStringSet。key 命中多套语义
 *   （付费/会员/PRO/解锁/去广告/到期）时按 getXxx 返回类型自动塞解锁值，
 *   其中“到期/有效期”类读取一律回 2099-01-01，令“未过期”判断恒成立。
 *   与 Billing 无关 —— 对“把付费态存本地 SP、本地判断即解锁”的任意 App
 *   （含不带 Billing SDK 的国内 App）都尝试生效。
 *   观测学习：首次命中时会扫该 App 自己的 shared_prefs/*.xml 与运行读取，
 *   把“确实存在会员/日期形态的 key”写入内存规则表（扩大匹配面——即使 key 完全
 *   不含词表关键词也能命中），并把观测记录写到 /data/data/&lt;该App&gt;/files/uvip/
 *   （records.txt / hits.log），供查看与人工调参。
 *
 * 【C】选定 App 精确激活增强（ProActivator，白名单 com.mobilecad.app）
 *   对把 PRO 位存在【内存对象】(不经 SP) 的 App（如指尖3D 的 EntitlementState，
 *   构造器 (Z,Enum,J,Z) 首参=pro）做 dex 级精确构造器钩强制激活。
 *   仅在 handleLoadPackage 命中白名单包名时启用 —— 这是 UVip(SP) 与
 *   UBilling(Billing) 覆盖不到的“内部状态”通道。
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
 *   默认 LOG_ONLY=true 只观测打 [UAuto] 日志，绝不改值 —— 先跑一轮看命中清单、
 *   确认无误伤后，再把 AutoVipProHook.LOG_ONLY 置 false 重载模块做真实注入。
 *   仅用于你自己/获授权 App 的防御自测。
 *
 * 用法：LSPosed 作用域勾选目标 App（VIP 拦截因是 zygote 级，勾“系统框架”
 * 即可对全部 App 生效），重启后看日志 [UBilling] / [UVip] / [UPro] / [UNet] / [URule]
 * / [UAuto]。
 */
public class Main implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 跳过系统进程自身与无关框架，减小开销（仍不对业务包名做任何过滤）
        if (lpparam.packageName == null) return;
        // 跳过 LSPosed / Xposed 自身，避免自递归
        if (lpparam.packageName.startsWith("org.lsposed.")
                || lpparam.packageName.equals("com.example.ubilling")) {
            return;
        }
        final ClassLoader cl = lpparam.classLoader;
        if (cl == null) return;

        // 【C】选定 App 精确激活增强：仅对 com.mobilecad.app（指尖3D）生效 ——
        //     它的 PRO 位是内存对象 EntitlementState(构造器 (Z,Enum,J,Z) 首参=pro)，
        //     不经 SharedPreferences（故 UVip 管不到），需 dex 级精确构造器钩。
        //     白名单限定，绝不对任意 App 生效，避免 EArc 式误伤。
        if (ProActivator.TARGET_PKG.equals(lpparam.packageName)) {
            try {
                ProActivator.hook(cl);
            } catch (Throwable t) {
                XposedBridge.log("[UPro] 挂载失败: " + t);
            }
        }

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

        // 【F】全 VIP/PRO 自动盲扫通道（AutoVipProHook）——按方法名强词表自动发现并
        //     改写会员判定 getter。默认 LOG_ONLY=true 只观测打 [UAuto]，不改值。
        //     确认无误伤后置 AutoVipProHook.LOG_ONLY=false 重建做真实注入。仅自有/授权自测。
        try {
            AutoVipProHook.hook(cl, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[UAuto] 挂载失败: " + t);
        }

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

    @Override
    public void initZygote(StartupParam startupParam) {
        // 系统级自动 VIP 拦截：zygote 期挂一次，对所有进程生效。
        // 它 hook 的是 Android SDK 类 SharedPreferencesImpl（永不混淆），
        // 与目标 App 是否加载 Billing SDK 无关。
        try {
            UniversalVipSweeper.hook();
        } catch (Throwable t) {
            XposedBridge.log("[UVip] 挂载失败: " + t);
        }
    }
}
