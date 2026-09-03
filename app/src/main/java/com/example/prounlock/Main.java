package com.example.prounlock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam;

/**
 * LSPosed / Xposed 入口。
 * 命中目标包 com.mobilecad.app 后挂上计费 hook。
 */
public class Main implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    // 目标应用包名（Digit 3D / 指尖3D 系列均为 com.mobilecad.app）
    private static final String TARGET_PKG = "com.mobilecad.app";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[ProUnlock] 命中目标包: " + lpparam.packageName);
        // 主解锁：dex 直接枚举 + 强制应用内部 PRO 对象的激活布尔位（与版本/混淆无关，真正通杀）
        try {
            ProUnlock.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[ProUnlock] ProUnlock 挂钩失败: " + t);
        }
        // 兜底：仅当目标为 Google Play 版（真正含 BillingClient）才尝试回灌已购记录，
        // 否则直接跳过，避免无谓的 ClassNotFoundException 噪音
        try {
            boolean hasBilling;
            try {
                Class.forName("com.android.billingclient.api.BillingClient", false, lpparam.classLoader);
                hasBilling = true;
            } catch (ClassNotFoundException e) {
                hasBilling = false;
            }
            if (hasBilling) {
                BillingHook.hook(lpparam.classLoader);
            } else {
                XposedBridge.log("[ProUnlock] 未检测到 BillingClient（国内版正常），跳过计费兜底");
            }
        } catch (Throwable t) {
            XposedBridge.log("[ProUnlock] BillingHook 兜底失败(可忽略): " + t);
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        // 无需 zygote 级初始化
    }
}
