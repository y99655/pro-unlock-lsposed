package com.example.ubilling;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam;

/**
 * 通用 Google Play Billing 解锁模块入口。
 *
 * 不做任何包名白名单 —— 对系统里任意 App 进程都尝试挂载。因为只有真正
 * 加载了 Google Play Billing SDK 的应用才有意义，所以我们先探测是否存在
 * BillingClient 类：
 *   - 存在  -> 挂载通用 Billing Hook；
 *   - 不存在 -> 绝大多数普通 App / 国内 App，静默跳过（不打日志刷屏）。
 *
 * 用法：在 LSPosed 的“作用域”里勾选希望作用的 App（一般可勾“系统框架”或
 * 逐个勾选目标 App），重启后该 App 内任何 queryPurchasesAsync 都会被回灌
 * “已购”结果。
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
        // 无需 zygote 级初始化
    }
}
