package com.example.prounlock;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 核心：Hook Google Play Billing 的查询接口，回灌一笔"已购买"的假订单。
 *
 * 为什么这样能"通杀"：
 *   - com.mobilecad.app 的 PRO 激活 = 购买 Google Play 商品 unlock_pro / unlock_pro_2。
 *   - BillingClient / PurchasesResponseListener / Purchase / BillingResult 都是
 *     Google 官方 SDK 的类名，【不参与应用自身的 R8/ProGuard 混淆】，
 *     因此无论目标 App 怎么混淆、怎么换版本，这些类名都不变。
 *   - 我们只在 queryPurchasesAsync 时拦截、塞入伪造的已购记录，
 *     应用据此认为自己已拥有 PRO，与本地代码怎么改名无关。
 */
public class BillingHook {

    private static final String TAG = "ProUnlock";

    // 从 APK 中提取的 PRO 商品 id（v5/q.smali、v5/h.smali、c6/wt.smali 均有引用）
    private static final String[] SKUS = {"unlock_pro", "unlock_pro_2"};

    public static void hook(ClassLoader cl) {
        hookQueryPurchases(cl);
        hookVerifySignature(cl);
    }

    // ------------------------------------------------------------------
    // 1) queryPurchasesAsync(..., PurchasesResponseListener) —— 真正解锁点
    // ------------------------------------------------------------------
    private static void hookQueryPurchases(ClassLoader cl) {
        try {
            Class<?> billingClient = cl.loadClass("com.android.billingclient.api.BillingClient");
            Class<?> listenerCls = cl.loadClass("com.android.billingclient.api.PurchasesResponseListener");

            for (final Method m : billingClient.getDeclaredMethods()) {
                if (!"queryPurchasesAsync".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                // 找到第二个参数是 PurchasesResponseListener 的重载
                // （覆盖旧签名 (String, listener) 与新签名 (QueryPurchasesParams, listener)）
                if (p.length == 2 && listenerCls.isAssignableFrom(p[1])) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object listener = param.args[1];
                            if (listener == null) return;
                            List<Object> purchases = buildPurchases(cl);
                            Object result = buildBillingResult(cl, 0); // 0 = BillingClient.BillingResponseCode.OK
                            if (purchases == null || result == null) return;

                            // 阻断真实计费请求，直接回灌假数据
                            param.setResult(null);
                            invokeOnResponse(listenerCls, listener, result, purchases);
                            XposedBridge.log(TAG + " queryPurchasesAsync -> 注入 " + purchases.size() + " 笔假订单");
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookQueryPurchases 失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 2) 尽力绕过本地签名校验（若应用对 Purchase 做 verifySignature）
    //    找不到对应方法时静默跳过，不影响主流程。
    // ------------------------------------------------------------------
    private static void hookVerifySignature(ClassLoader cl) {
        // 常见校验方法名，命中即强制返回 true
        String[] candidates = {"verifySignature", "verifyPurchase", "isSignatureValid", "checkSignature"};
        try {
            // 在已加载类里找含这些方法的类代价较高，这里仅尝试对已知计费相关类下手
            Class<?> purchaseCls = cl.loadClass("com.android.billingclient.api.Purchase");
            for (Method m : purchaseCls.getDeclaredMethods()) {
                for (String name : candidates) {
                    if (m.getName().equals(name) && boolean.class.equals(m.getReturnType())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(true);
                            }
                        });
                        XposedBridge.log(TAG + " hookVerifySignature 命中: " + m);
                    }
                }
            }
        } catch (Throwable t) {
            // 静默：Purchase 无此类方法属正常
        }
    }

    // ------------------------------------------------------------------
    // 构造假购买记录
    // ------------------------------------------------------------------
    private static List<Object> buildPurchases(ClassLoader cl) {
        try {
            Class<?> purchaseCls = cl.loadClass("com.android.billingclient.api.Purchase");
            Constructor<?> ctor = purchaseCls.getConstructor(String.class, String.class); // (purchaseData, signature)
            List<Object> list = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (String sku : SKUS) {
                String json = "{"
                        + "\"productId\":\"" + sku + "\","
                        + "\"products\":[\"" + sku + "\"],"
                        + "\"purchaseToken\":\"prounlock_" + sku + "\","
                        + "\"purchaseTime\":" + now + ","
                        + "\"quantity\":1,"
                        + "\"acknowledged\":true,"
                        + "\"purchaseState\":1,"
                        + "\"orderId\":\"GPA.0000-0000-0000-00000\","
                        + "\"packageName\":\"com.mobilecad.app\""
                        + "}";
                list.add(ctor.newInstance(json, "prounlock_dummy_signature"));
            }
            return list;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " buildPurchases 失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 构造成功的 BillingResult
    // ------------------------------------------------------------------
    private static Object buildBillingResult(ClassLoader cl, int responseCode) {
        try {
            Class<?> brCls = cl.loadClass("com.android.billingclient.api.BillingResult");
            Object builder = brCls.getMethod("newBuilder").invoke(null);
            builder.getClass().getMethod("setResponseCode", int.class).invoke(builder, responseCode);
            builder.getClass().getMethod("setDebugMessage", String.class).invoke(builder, "ok");
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " buildBillingResult 失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 回调 listener.onQueryPurchasesResponse(BillingResult, List<Purchase>)
    // ------------------------------------------------------------------
    private static void invokeOnResponse(Class<?> listenerCls, Object listener, Object result, List<Object> purchases) {
        try {
            Method m = listenerCls.getMethod("onQueryPurchasesResponse", result.getClass(), List.class);
            m.invoke(listener, result, purchases);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " invokeOnResponse 失败: " + t);
        }
    }
}
