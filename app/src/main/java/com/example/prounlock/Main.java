package com.example.prounlock;

import de.robv.android.xposed.IXposedMod;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed / Xposed 入口。
 * 命中目标包 com.mobilecad.app 后挂上计费 hook。
 */
public class Main implements IXposedMod {

    // 目标应用包名（Digit 3D / 指尖3D 系列均为 com.mobilecad.app）
    private static final String TARGET_PKG = "com.mobilecad.app";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[ProUnlock] 命中目标包: " + lpparam.packageName
                + " (cl=" + lpparam.classLoader + ")");
        try {
            BillingHook.hook(lpparam.classLoader);
            XposedBridge.log("[ProUnlock] BillingHook 挂载完成");
        } catch (Throwable t) {
            XposedBridge.log("[ProUnlock] BillingHook 失败: " + t);
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        // 无需 zygote 级初始化
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        // 无需资源 hook
    }
}
