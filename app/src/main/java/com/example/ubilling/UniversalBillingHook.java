package com.example.ubilling;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 通用 Google Play Billing 解锁 Hook（跨 App、跨版本、混淆无关）。
 *
 * 设计依据：
 *   Google Play Billing 的官方 SDK 类名 —— BillingClient / Purchase / BillingResult /
 *   PurchasesResponseListener / QueryPurchasesParams / QueryProductDetailsParams ——
 *   属于应用依赖的 AAR，【不参与应用自身 R8/ProGuard 混淆】，因此无论哪个 App、
 *   哪个版本，这些类名都恒定。我们只需反射这些恒定类名即可“通杀”。
 *
 * 与专版（只针对 com.mobilecad.app）的区别：
 *   1. 不做任何包名白名单 —— 只要 App 进程加载了 Billing SDK 就生效；
 *   2. 伪造订单的 packageName 动态取当前进程包名，不再写死；
 *   3. SKU 不写死成某个 App 的：注入集合 = 「本次 App 真正查询的 SKU」
 *      并集「内置可配置 SKU 表」，两者都算已购。
 *
 * 破解语义（诚实说明）：
 *   它覆盖的是“App 查询到目标 SKU 的已购记录即视为解锁/PRO/去广告”这一类逻辑，
 *   即绝大多数把购买状态存在本地的 Billing 应用。若某 App 走服务端二次验签、
 *   或购买后由自家服务器下发授权，则本模块只能让客户端看到“已购”，
 *   能否真正解锁取决于该 App 是否信任本地查询结果。
 */
public class UniversalBillingHook {

    private static final String TAG = "[UBilling]";

    /**
     * 内置可配置 SKU 表：可自由增删。所有命中进程都会被注入这些“已购”SKU。
     * 注意：这些只是兜底，真正关键的 SKU 会由 queryProductDetailsAsync 探测自动补上。
     */
    public static final Set<String> EXTRA_SKUS = new LinkedHashSet<>();
    static {
        // 示例/历史：com.mobilecad.app 用过的商品 id
        EXTRA_SKUS.add("unlock_pro");
        EXTRA_SKUS.add("unlock_pro_2");
    }

    /** 运行时探测到的、目标 App 本次真正查询的 SKU（自动累积，跨调用共享）。 */
    private static final Set<String> seenSkus = new LinkedHashSet<>();

    private static String currentPackage = "";

    public static void hook(ClassLoader cl, String pkgName) {
        currentPackage = pkgName == null ? "" : pkgName;
        hookQueryProductDetails(cl);   // 探测 App 真实查询哪些 SKU（并自动纳入已购）
        hookQueryPurchases(cl);        // 真正解锁点：queryPurchasesAsync 注入假已购
        hookLaunchBilling(cl);         // 防弹窗：启动购买流程直接回调已购
        XposedBridge.log(TAG + " 通用 Billing Hook 已挂载 @ " + currentPackage);
    }

    // ------------------------------------------------------------------
    // A) queryPurchasesAsync(QueryPurchasesParams|String, listener)
    //    —— 应用启动时判断“我是否已购买”的主入口，直接回灌已购列表
    // ------------------------------------------------------------------
    private static void hookQueryPurchases(final ClassLoader cl) {
        try {
            final Class<?> billingClient = cl.loadClass("com.android.billingclient.api.BillingClient");
            final Class<?> listenerCls = cl.loadClass("com.android.billingclient.api.PurchasesResponseListener");
            boolean any = false;
            for (final Method m : billingClient.getDeclaredMethods()) {
                if (!"queryPurchasesAsync".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && listenerCls.isAssignableFrom(p[1])) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object listener = param.args[1];
                            if (listener == null) return;
                            List<Object> purchases = buildPurchases(cl);
                            Object result = buildBillingResult(cl, 0); // 0 = OK
                            if (purchases == null || result == null) return;
                            param.setResult(null);                       // 阻断真实网络查询
                            invokeOnResponse(listenerCls, listener, result, purchases);
                            XposedBridge.log(TAG + " queryPurchasesAsync 注入 " + purchases.size()
                                    + " 笔已购: " + skusOf(purchases));
                        }
                    });
                    any = true;
                }
            }
            if (!any) XposedBridge.log(TAG + " queryPurchasesAsync 无匹配重载(该 Billing 版本可能极旧)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookQueryPurchases 跳过: " + t);
        }
    }

    // ------------------------------------------------------------------
    // B) queryProductDetailsAsync(QueryProductDetailsParams, listener)
    //    —— App 在注册/展示商品前，会查询目标 SKU 的价格等信息。
    //       我们从参数里读出“它到底想看哪些 SKU”，自动加进已购集合 + 打日志，
    //       这样无需预先知道目标 App 的商品 id，跑一次就能从日志看到并用上。
    // ------------------------------------------------------------------
    private static void hookQueryProductDetails(final ClassLoader cl) {
        try {
            final Class<?> billingClient = cl.loadClass("com.android.billingclient.api.BillingClient");
            for (final Method m : billingClient.getDeclaredMethods()) {
                if (!"queryProductDetailsAsync".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 第 0 参 = QueryProductDetailsParams，反射取 productList 里的 sku/productId
                        List<String> req = extractSkus(param.args[0]);
                        if (req != null && !req.isEmpty()) {
                            boolean added = false;
                            synchronized (seenSkus) {
                                for (String s : req) {
                                    if (s != null && !s.isEmpty() && seenSkus.add(s)) added = true;
                                }
                            }
                            if (added) XposedBridge.log(TAG + " 探测到 App 查询 SKU: " + req);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            // 旧版 Billing 可能没有 queryProductDetailsAsync，可忽略
        }
    }

    // ------------------------------------------------------------------
    // C) launchBillingFlow(Activity, BillingFlowParams) —— 用户点“购买”时，
    //    干脆直接把它当成“已购买成功”，拦截弹窗与真实下单。
    // ------------------------------------------------------------------
    private static void hookLaunchBilling(final ClassLoader cl) {
        try {
            final Class<?> billingClient = cl.loadClass("com.android.billingclient.api.BillingClient");
            for (final Method m : billingClient.getDeclaredMethods()) {
                if (!"launchBillingFlow".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 2) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 从 BillingFlowParams 里尽力拿 sku，纳入已购集合
                        List<String> req = extractSkusFromFlow(param.args[1]);
                        if (req != null) {
                            synchronized (seenSkus) {
                                for (String s : req) if (s != null && !s.isEmpty()) seenSkus.add(s);
                            }
                        }
                        param.setResult(buildBillingResult(cl, 0)); // 返回 OK，假装已进入购买
                        XposedBridge.log(TAG + " launchBillingFlow 拦截(视为已购)");
                    }
                });
            }
        } catch (Throwable t) {
            // 可忽略
        }
    }

    // ------------------------------------------------------------------
    // SKU 提取工具
    // ------------------------------------------------------------------
    private static List<String> extractSkus(Object params) {
        List<String> out = new ArrayList<>();
        if (params == null) return out;
        try {
            // QueryProductDetailsParams.getProductList() -> List<QueryProductDetailsParams.Product>
            Method gl = params.getClass().getMethod("getProductList");
            Object list = gl.invoke(params);
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item == null) continue;
                    // item 是 Product：取 productId（兼容老 getSku）
                    String id = callString(item, "getProductId");
                    if (id == null) id = callString(item, "getSku");
                    if (id == null && item instanceof String) id = (String) item;
                    if (id != null && !id.isEmpty()) out.add(id);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static List<String> extractSkusFromFlow(Object flowParams) {
        List<String> out = new ArrayList<>();
        if (flowParams == null) return out;
        try {
            // 老版本 BillingFlowParams.getSku(); 新版本 getProductList()
            String sku = callString(flowParams, "getSku");
            if (sku != null) { out.add(sku); return out; }
            Method gl = flowParams.getClass().getMethod("getProductList");
            Object list = gl.invoke(flowParams);
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    String id = callString(item, "getProductId");
                    if (id == null) id = callString(item, "getSku");
                    if (id != null && !id.isEmpty()) out.add(id);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static String callString(Object o, String m) {
        try {
            Method mm = o.getClass().getMethod(m);
            Object r = mm.invoke(o);
            return r == null ? null : r.toString();
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 构造假购买记录：SKU = 探测到的 ∪ 配置表
    // ------------------------------------------------------------------
    private static List<Object> buildPurchases(ClassLoader cl) {
        try {
            Class<?> purchaseCls = cl.loadClass("com.android.billingclient.api.Purchase");
            Constructor<?> ctor = purchaseCls.getConstructor(String.class, String.class); // (json, signature)
            List<Object> list = new ArrayList<>();
            Set<String> all = new LinkedHashSet<>();
            synchronized (seenSkus) { all.addAll(seenSkus); }
            all.addAll(EXTRA_SKUS);
            long now = System.currentTimeMillis();
            for (String sku : all) {
                if (sku == null || sku.isEmpty()) continue;
                String json = "{"
                        + "\"productId\":\"" + sku + "\","
                        + "\"products\":[\"" + sku + "\"],"
                        + "\"purchaseToken\":\"ubilling_" + sku + "\","
                        + "\"purchaseTime\":" + now + ","
                        + "\"quantity\":1,"
                        + "\"acknowledged\":true,"
                        + "\"purchaseState\":1,"            // 1 = PURCHASED
                        + "\"orderId\":\"GPA.0000-0000-0000-00000\","
                        + "\"packageName\":\"" + currentPackage + "\""
                        + "}";
                list.add(ctor.newInstance(json, "ubilling_dummy_signature"));
            }
            return list;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " buildPurchases 失败: " + t);
            return null;
        }
    }

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

    private static void invokeOnResponse(Class<?> listenerCls, Object listener, Object result, List<Object> purchases) {
        try {
            Method m = listenerCls.getMethod("onQueryPurchasesResponse", result.getClass(), List.class);
            m.invoke(listener, result, purchases);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " invokeOnResponse 失败: " + t);
        }
    }

    private static String skusOf(List<Object> purchases) {
        StringBuilder sb = new StringBuilder("[");
        for (Object p : purchases) {
            try {
                Method g = p.getClass().getMethod("getProducts");
                Object r = g.invoke(p);
                if (r instanceof List) for (Object x : (List<?>) r) sb.append(x).append(",");
            } catch (Throwable ignore) {
            }
        }
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }
}
