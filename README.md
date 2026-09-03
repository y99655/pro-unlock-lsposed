# ProUnlock —— LSPosed 通杀模块（com.mobilecad.app 系列 / 指尖3D / Digit3D）

## 它能做什么
针对 `com.mobilecad.app`（Digit 3D / 指尖3D 系列）的 PRO 激活做**真·通杀**解锁。
原理：在运行时**强制应用内部 PRO 状态对象的"激活布尔位"为 true**，
让应用认为自己已拥有 PRO——与服务端、与 Google Play 计费都无关。

## 为什么之前"Hook 没效果"，以及现在为什么能通杀
早先版本（v1）错误地假设本应用走 **Google Play Billing**（`unlock_pro` / `unlock_pro_2`），
去 Hook `BillingClient.queryPurchasesAsync` 回灌假订单。但 `指尖3D` 是国内 App，
PRO 激活**根本不走 Google 计费**，所以那个 Hook 打不到任何点 → 完全无效。

经对各版本 smali 逆向确认，PRO 状态存在应用**内部数据对象**里（如 1.3.2 的 `q5/o0`）：
```
field public final a:Z        <- 激活布尔位（PRO 是否激活）
field public final b:L<枚举>; <- 档位枚举（standard / pro ...）
field public final c:J        <- long（到期时间等）
field public final d:Z        <- 另一个布尔位
.method public constructor <init>(ZL<枚举>;JZ)V
    iput-boolean p1, p0, ->a:Z   # 第一个布尔参数 = 激活位
```
- 字段 `a` 是 `final`，**构造后不可变** → 只要在构造时把第一个布尔参数强制成 `true`，
  应用之后读到的永远是"已激活"。这正是此前 smali 通杀补丁能成功的根本原因。
- 构造签名 `(Z, 枚举, long, Z)V` 在 1.3.0 / 1.3.1 / 1.3.2 中**稳定一致**，
  但混淆后的**类名会变**（q5/o0 只是 1.3.2 的样子）。

因此本模块（v3）的策略是：挂钩 `ClassLoader.loadClass`，每当 `com.mobilecad.app`
加载一个类，就用标准反射检查其构造签名是否为 `(Z, Enum, long, Z)`，
命中即对其构造器挂钩，在 `beforeHook` 里强制 `args[0] = true`。
**不写死任何类名**，所以跨版本、跨混淆都通杀。
（v2 曾用 `DexFile.getClassNameList` 反射枚举类，该方法在 Android 10+ 已失效，
导致扫描到 0 个类、挂钩 0 个——此问题在 v3 已彻底改用 loadClass 方案修复。）

> 兜底：仅当应用确实含 `BillingClient`（Google Play 购买型）才尝试回灌已购记录
> （`BillingHook`）；国内版不含该类的直接跳过，不再打印 ClassNotFoundException 噪音。

## 编译（两种方式）

### 方式 A：GitHub Actions 在线编译（推荐，零本地环境）
本仓库已内置 `.github/workflows/build.yml`：
1. 把本目录推送到你自己的 GitHub 仓库（main 分支）。
2. 在仓库 **Actions** 页等 `Build LSPosed Module` 跑完（约 3–5 分钟）。
3. 在对应 run 的 **Artifacts** 里下载 `ProUnlock-LSPosed-module`（即 `app-debug.apk`）。

CI 产出的是 **debug 自动签名** 的 APK，可直接 `adb install` 并加载，**无需 keystore**。

### 方式 B：本地 Android Studio / Gradle 编译
需要本地装有 Android SDK（compileSdk 34）+ JDK 17：
- 用 Android Studio 打开本目录，Gradle Sync 后 `Build → Build APK(s)`。
- 或命令行：`gradle assembleDebug`（产出 `app/build/outputs/apk/debug/app-debug.apk`）。

## 部署
1. 手机已安装 **LSPosed**（Magisk + Zygisk 或 Riru 方案）。
2. 把编译出的模块 APK 装入手机，在 LSPosed 管理器中**勾选本模块**，
   并在**作用域里勾选目标应用 `com.mobilecad.app`**。
3. **重启目标应用**（或重启系统）。
4. 启动应用，PRO 应直接处于已激活状态（无需购买、无需联网）。

## 验证
- LSPosed 日志（或 `adb logcat | grep ProUnlock`）应出现：
  `[ProUnlock] 挂钩 PRO 构造器: com.mobilecad.app.xxx.yyy`
  `[ProUnlock] PRO 构造器扫描完成，共挂钩 N 个`
- 若日志显示**挂钩 0 个**，说明该版本构造签名有变化，请把机型/版本号回报以便适配。
- 应用内"升级 PRO / 解锁 Pro"页应显示已拥有。

## 文件结构
```
lsposed_pro_unlock/
├── build.gradle / settings.gradle
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml        # xposedmodule 声明
        ├── assets/xposed_init        # 入口类 com.example.prounlock.Main
        └── java/com/example/prounlock/
            ├── Main.java              # 入口，命中 com.mobilecad.app 后挂 ProUnlock + BillingHook
            ├── ProUnlock.java         # 核心：扫描并强制 PRO 对象激活位（真·通杀）
            ├── BillingHook.java       # 兜底：Google Play 购买型版本回灌假订单
            └── MainActivity.java      # 占位 UI
```
