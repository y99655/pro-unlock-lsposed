package com.example.ubilling;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 联网鉴权抗 HOOK 自测通道（NetLabHook）—— 仅用于【对自己拥有/获授权的 App】做
 * 安全评估：模拟“破解方针对服务端/联网鉴权型 App”的常见 hook 攻击面，验证能否被
 * 打穿，从而指导服务端加固。请勿用于破解他人收费服务。
 *
 * ============================================================================
 * 背景：对“会员态由服务端下发、本地只做展示缓存”的 App（如 DCloud/H5 壳 +
 * 云函数发卡），SharedPreferences/Billing/dex 三通道均无消费点。但攻击者仍会尝试
 * 下面 3 类 hook（这正是需要你自己先测一遍的）：
 *
 *   T1 网络响应篡改：hook OkHttp(2/3) ResponseBody.string()，把服务端返回 JSON 里
 *      的会员字段就地改写（如 "hylx":"0" -> "hylx":"4"）。能否得逞取决于服务端是否
 *      二次校验/是否信任客户端读取 —— 若你 App 的关键放行在服务端，本通道会测出
 *      “改响应无效”，即防住了；若客户端信任改后的 body，就是漏洞。
 *   T2 证书校验探测：hook okhttp3.CertificatePinner.check —— 若命中，说明 App 做了
 *      SSL pinning（防抓包/防中间人改包）；配合 T1 可判断“改包难度”。
 *   T3 WebView JS 注入面：对 H5/内嵌 WebView 类 App，攻击者注入 JS 改页面逻辑 /
 *      改本地缓存(plus.storage 的 userdata)。默认只【记录】 javascript: 调用与
 *      evaluateJavascript；如需验证“注入能否改到你的判定”，在 NEEDLE_JS 填注入脚本
 *      （默认空 = 不注入，仅观测）。
 *
 * 设计：
 *   - 挂载点 = handleLoadPackage（按 LSPosed 勾选进程生效，不污染未勾选 App）。
 *   - 只对“进程里真加载了 okhttp3 / WebView”的 App 挂对应子通道（类不存在即跳过）。
 *   - 默认 LOG_ONLY=阶段：全部只记录不改写，先看攻击面覆盖；确认要“实战改写”时把
 *     LOG_ONLY 置 false 并按需填 REPLACEMENTS / NEEDLE_JS 再测。
 *   - 日志统一 [UNet]，含包名与方法，便于对照“哪条链路被模拟攻击命中/未命中”。
 * ============================================================================
 */
public class NetLabHook {

    private static final String TAG = "[UNet]";

    /** true = 只观测打日志(安全默认)；false = 执行改写/注入(自测实战阶段)。 */
    public static final boolean LOG_ONLY = true;

    /**
     * T1 响应改写规则（仅 LOG_ONLY=false 时生效）：顺序执行字符串替换。
     * 示例(云函数型 H5 App)：{{"\"hylx\":\"0\"","\"hylx\":\"4\""},
     *                         {"\"vipky\":\"false\"","\"vipky\":\"true\""}}
     * 自测时按你服务端实际返回 JSON 的字段填。服务端二次校验强的话，改完仍不放行
     * —— 那正是你要的“防住了”结论。
     */
    public static final String[][] REPLACEMENTS = {
        // {"\"hylx\":\"0\"", "\"hylx\":\"4\""}
    };

    /**
     * T3 JS 注入脚本（仅 LOG_ONLY=false 且非空时生效）：在目标 App 每个 WebView
     * loadUrl 后注入一次。默认空 = 仅观测不注入。示例(H5/plus.storage 型)：
     *   "(function(){try{var u=JSON.parse(plus.storage.getItem('userdata'));" +
     *   "if(u){u.hylx='4';u.vipky='true';plus.storage.setItem('userdata',JSON.stringify(u));}}" +
     *   "catch(e){}})();"
     */
    public static final String NEEDLE_JS = "";

    /** 每进程只挂一次各子通道。 */
    private static volatile boolean doneOkHttp = false;
    private static volatile boolean doneWebView = false;
    private static volatile boolean donePinner = false;

    /** T3 注入只做一次(避免每个页面重复注入污染测试结论)。 */
    private static volatile boolean jsInjectedOnce = false;

    /** 响应日志去重（防刷屏）：存 内容前 60 字符 hash。 */
    private static final Set<String> loggedBody = java.util.Collections.synchronizedSet(new HashSet<String>());

    public static void hook(ClassLoader cl, String pkg) {
        if (cl == null) return;
        try { hookOkHttp(cl, pkg); } catch (Throwable t) { XposedBridge.log(TAG + " okhttp 通道失败 " + t); }
        try { hookPinner(cl, pkg); } catch (Throwable t) { XposedBridge.log(TAG + " pinner 通道失败 " + t); }
        try { hookWebView(cl, pkg); } catch (Throwable t) { XposedBridge.log(TAG + " webview 通道失败 " + t); }
    }

    // ------------------------------------------------------------------
    // T1: OkHttp ResponseBody.string() —— 响应篡改面（okhttp3 与 okhttp2）
    // ------------------------------------------------------------------
    private static void hookOkHttp(final ClassLoader cl, final String pkg) {
        if (doneOkHttp) return;
        String[] cands = {"okhttp3.ResponseBody", "com.squareup.okhttp.ResponseBody"};
        Class<?> body = null;
        for (String c : cands) {
            try { body = Class.forName(c, false, cl); if (body != null) break; } catch (Throwable ignore) {}
        }
        if (body == null) return;               // 该 App 没走 OkHttp -> 跳过(测试结论: 无此面)
        doneOkHttp = true;
        XposedBridge.log(TAG + " 命中 OkHttp 响应面 @ " + pkg + " (" + body.getName() + ")");

        Method m = null;
        for (Method mm : body.getDeclaredMethods()) {
            if ("string".equals(mm.getName()) && mm.getParameterTypes().length == 0) { m = mm; break; }
        }
        if (m == null) return;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object r = param.getResult();
                if (!(r instanceof String)) return;
                String s = (String) r;
                if (s == null || s.length() < 8) return;
                // 观测日志（去重）
                String sig = pkg + "|" + (s.length() > 60 ? s.substring(0, 60) : s);
                if (loggedBody.add(sig)) {
                    XposedBridge.log(TAG + " [T1] 响应体 " + s.length() + "B @ " + pkg + " 摘要="
                            + (s.length() > 160 ? s.substring(0, 160) : s));
                }
                if (!LOG_ONLY && REPLACEMENTS.length > 0) {
                    String out = s;
                    for (String[] kv : REPLACEMENTS) {
                        if (kv.length == 2) out = out.replace(kv[0], kv[1]);
                    }
                    if (!out.equals(s)) {
                        param.setResult(out);
                        XposedBridge.log(TAG + " [T1] 响应已改写 @ " + pkg + " " + out.length() + "B");
                    }
                }
            }
        });
        XposedBridge.log(TAG + " [T1] okhttp 响应篡改面已挂(LOG_ONLY=" + LOG_ONLY + ") @ " + pkg);
    }

    // ------------------------------------------------------------------
    // T2: SSL Pinning 探测 —— CertificatePinner.check 是否被调用
    // ------------------------------------------------------------------
    private static void hookPinner(final ClassLoader cl, final String pkg) {
        if (donePinner) return;
        Class<?> cp;
        try { cp = Class.forName("okhttp3.CertificatePinner", false, cl); } catch (Throwable t) { return; }
        donePinner = true;
        boolean any = false;
        for (Method m : cp.getDeclaredMethods()) {
            if (!"check".equals(m.getName())) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + " [T2] ★CertificatePinner.check 被调用 @ " + pkg
                            + " -> App 启用了证书固定(防抓包/防中间人), 纯客户端改响应会被 TLS 层拦下");
                }
            });
            any = true;
        }
        if (any) XposedBridge.log(TAG + " [T2] SSL pinning 探测已挂 @ " + pkg);
    }

    // ------------------------------------------------------------------
    // T3: WebView 注入面 —— 记录 javascript: / evaluateJavascript，
    //      NEEDLE_JS 非空时在 loadUrl 后注入一次
    // ------------------------------------------------------------------
    private static void hookWebView(final ClassLoader cl, final String pkg) {
        if (doneWebView) return;
        final Class<?> wv;
        try { wv = Class.forName("android.webkit.WebView", false, cl); } catch (Throwable t) { return; }
        doneWebView = true;
        XposedBridge.log(TAG + " 命中 WebView 面 @ " + pkg + (LOG_ONLY || NEEDLE_JS.isEmpty()
                ? " (仅观测)" : " (注入模式)"));

        // 记录 javascript: 形式的 loadUrl（攻击者最常用的注入入口之一）
        try {
            XposedHelpers.findAndHookMethod(wv, "loadUrl", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String url = (String) param.args[0];
                    if (url != null) {
                        if (url.startsWith("javascript:")) {
                            XposedBridge.log(TAG + " [T3] javascript: 调用 @ " + pkg + " len=" + url.length());
                        }
                        // 自测注入（可选，只注入一次）
                        if (!LOG_ONLY && NEEDLE_JS != null && !NEEDLE_JS.isEmpty()
                                && !jsInjectedOnce) {
                            jsInjectedOnce = true;
                            try {
                                final Method[] ejRef = new Method[1];
                                for (Method mm : wv.getMethods()) {
                                    if ("evaluateJavascript".equals(mm.getName())
                                            && mm.getParameterTypes().length == 2
                                            && mm.getParameterTypes()[0] == String.class) {
                                        ejRef[0] = mm; break;
                                    }
                                }
                                if (ejRef[0] != null) {
                                    final Object w = param.thisObject;
                                    final String js = NEEDLE_JS;
                                    new Thread(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                Thread.sleep(1200);   // 等页面就绪再注入
                                                ejRef[0].invoke(w, js, null);
                                                XposedBridge.log(TAG + " [T3] 已注入 JS @ " + pkg);
                                            } catch (Throwable ignore) {}
                                        }
                                    }, "unet-js").start();
                                }
                            } catch (Throwable ignore) {}
                        }
                    }
                }
            });
        } catch (Throwable ignore) {
        }
        // 观测 evaluateJavascript(..., callback)：攻击者注入 JS 的另一入口
        try {
            XposedHelpers.findAndHookMethod(wv, "evaluateJavascript", String.class,
                    android.webkit.ValueCallback.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String js = (String) param.args[0];
                    if (js != null) {
                        XposedBridge.log(TAG + " [T3] evaluateJavascript @ " + pkg + " len=" + js.length()
                                + " 头=" + (js.length() > 100 ? js.substring(0, 100) : js));
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }
}
