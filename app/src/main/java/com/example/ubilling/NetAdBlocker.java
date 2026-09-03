package com.example.ubilling;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 【I】网络层去广告通道（NetAdBlocker）—— v1.14 起取代旧的"屏蔽广告 SDK 类"做法。
 *
 * ============================================================================
 * 为什么换（v1.14，按你提供 Close_3.9.3.apk=AdClose 逆向结论重做）：
 *   旧做法 hook 应用 ClassLoader.loadClass，靠"包名前缀猜广告 SDK 类"再整类屏蔽。
 *   拦不住广告联网、硬引用会闪退（v1.13 已默认放行=等于没拦）。AdClose 等成熟模块
 *   真正有效且通用的是【网络层拦截】：掐断"向广告服务器要物料"的那一跳。而无论广告
 *   SDK 用 OkHttp/HttpURLConnection/Socket，发起前都要先解析广告域名 DNS。因此在本
 *   进程内 Hook java.net.InetAddress 的解析出口，命中广告域名就让其"解析失败"，
 *   广告请求根本到不了广告服务器 → 广告无物料可渲染（banner/插屏/开屏/激励都基于
 *   返回物料渲染）。这是通用、安全、能真正拦住大多数第三方广告 SDK 的关键一刀。
 *
 * ============================================================================
 * 数据源：见 BlockDomainStore —— 内置 AdClose 17,475 条离线清单 + 可配置 URL 在线更新。
 *
 * ============================================================================
 * 安全设计（面向爱加密加固 / 硬引用广告 SDK 的目标 App，绝不闪退）：
 *   1) 全部逻辑 try/catch，任何异常只打日志、绝不抛到调用方。
 *   2) 只在本进程对本方法挂一次（去重），不影响其它进程/父 loader。
 *   3) DNS 拦截语义安全：getAllByName → 返回【空数组】（调用方按"无此主机"处理）；
 *      getByName / getAllByName(String,int) → 抛 UnknownHostException（同"解析失败"）。
 *      这些都是网络层本就允许的失败，被 try/catch 或系统按解析失败处理，不会像
 *      ClassNotFoundException 那样穿透到 UI 栈闪退。
 *   4) 只拦黑名单命中的域名；不碰白域名/内网/本机，绝不拦截系统与业务自身正常解析。
 *
 * 通道总开关仍由 MainActivity 里【I/去广告】决定（Settings.channelOn(K_ADBLOCK)）。
 * 仅用于你自己/获授权 App 的防御自测，勿用于破坏他人商业 App 的营收展示。
 */
public class NetAdBlocker {

    private static final String TAG = "[UAd]";

    /** 总开关：false=停用整个网络层去广告。默认 true。 */
    public static final boolean NETBLOCK_ON = true;

    /** 只对选定包名生效(双保险；Main 也会按作用域 gate)。null=任意勾选进程。 */
    public static final String TARGET_PKG = null;

    /** 本类可用的域名判断开关：false 则完全不判不拦(纯停用)。 */
    private static final boolean ENABLE = true;

    /** 已 hook 过的包名，避免重复挂。 */
    private static final Set<String> donePkg =
            Collections.synchronizedSet(new HashSet<String>());

    /** 拦截计数（仅日志）。 */
    private static final AtomicInteger blockedCnt = new AtomicInteger(0);

    /** 命中日志去重。 */
    private static final Set<String> seenLog =
            Collections.synchronizedSet(new HashSet<String>());

    private NetAdBlocker() {
    }

    /** 入口（Main 对每个勾选进程调用）。 */
    public static void hook(ClassLoader cl, String pkg) {
        if (!NETBLOCK_ON || !ENABLE) {
            XposedBridge.log(TAG + " 网络层去广告未启用(NETBLOCK_ON/ENABLE) @ " + pkg);
            return;
        }
        if (pkg == null) return;
        if (TARGET_PKG != null && !TARGET_PKG.equals(pkg)) return;
        if (!donePkg.add(pkg)) return;
        if (!Settings.channelOn(Settings.K_ADBLOCK)) {
            XposedBridge.log(TAG + " 去广告通道停用(MainActivity 未勾选 I/去广告) @ " + pkg);
            return;
        }
        // 先确保黑名单数据至少加载过内置（后台再尝试在线更新）。只在本进程首次 hook 时做。
        try {
            BlockDomainStore.ensureLoaded();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " BlockDomainStore 初始化异常: " + t);
        }
        XposedBridge.log(TAG + " 网络层去广告挂载 @ " + pkg
                + "  黑名单规模=" + BlockDomainStore.size()
                + "  (DNS拦截; 内置AdClose清单+可配置在线更新)");
        try {
            hookInetAddress();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " DNS 拦截钩挂载失败: " + t);
        }
    }

    /** 对 InetAddress 的域名解析出口挂钩（安全、去重）。 */
    private static void hookInetAddress() throws Throwable {
        final XC_MethodHook dnsHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // 若黑名单尚未就绪，静默放行（不拦）。就绪后再拦。
                if (!BlockDomainStore.isReady()) return;
                if (param.args == null || param.args.length == 0) return;
                Object a0 = param.args[0];
                if (!(a0 instanceof String)) return;
                String host = (String) a0;
                if (!BlockDomainStore.isBlockedHost(host)) return;
                // 命中广告域名：拦截。
                int c = blockedCnt.incrementAndGet();
                if (seenLog.add(host)) {
                    XposedBridge.log(TAG + " 拦截广告域名解析 @ "
                            + (host) + "  (累计=" + c + ")");
                }
                String mn = param.method == null ? "" : param.method.getName();
                try {
                    if ("getAllByName".equals(mn)) {
                        // 返回空数组：调用方按"该主机无地址"处理，安全。
                        param.setResult(new InetAddress[0]);
                    } else {
                        // getByName / getAllByName(String,int)：抛解析失败。
                        param.setThrowable(new UnknownHostException(
                                "AdBlock host: " + host));
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " 拦截设置异常(已放行): " + t);
                }
            }
        };

        // getAllByName(String) -> InetAddress[]
        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName",
                    String.class, dnsHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook getAllByName(String) 失败: " + t);
        }
        // getByName(String) -> InetAddress
        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName",
                    String.class, dnsHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook getByName(String) 失败: " + t);
        }
        // getAllByName(String,int) 少见重载
        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName",
                    String.class, int.class, dnsHook);
        } catch (Throwable t) {
            // 旧版本无此重载，忽略
        }
        XposedBridge.log(TAG + " InetAddress DNS 拦截已挂(getAllByName/getByName)");
    }
}
