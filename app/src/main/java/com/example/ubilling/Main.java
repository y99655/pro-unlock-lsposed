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
 * 【B】自动 VIP 拦截（UniversalVipSweeper，任意 App 通用）
 *   在 initZygote() 系统级挂载一次：拦截 android.app.SharedPreferencesImpl 的
 *   getString/getBoolean/getInt/getLong/getFloat/getStringSet，当 key 名命中
 *   vip/premium/unlock 等关键词时，按被调 getXxx 的返回类型自动塞解锁值。
 *   该功能与 Billing 无关 —— 对“把付费态存本地 SP、本地判断即解锁”的任意 App
 *   都尝试生效（包括不带 Billing SDK 的国内 App）。
 *
 * 用法：LSPosed 作用域勾选目标 App（VIP 拦截因是 zygote 级，勾“系统框架”
 * 即可对全部 App 生效），重启后看日志 [UBilling] / [UVip]。
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
