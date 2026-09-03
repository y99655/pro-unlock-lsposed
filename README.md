# ProUnlock —— LSPosed 通杀模块（com.mobilecad.app 系列）

## 它能做什么
针对 `com.mobilecad.app`（Digit 3D / 指尖3D 系列）的 PRO 激活做"通杀"解锁。
原理：Hook **Google Play Billing** 的 `BillingClient.queryPurchasesAsync(...)`，
在应用查询已购记录时回灌一笔"已购买 `unlock_pro` / `unlock_pro_2`"的伪造订单，
让应用认为自己已拥有 PRO。

## 为什么这是真正的"通杀"，而 smali 补丁不是
- 之前的 `universal_patch.py`（smali 重打包）写死了 `q5/o0`、`z5/l0` 等类名。
  但每次构建 R8/ProGuard 都会重新混淆，类名随版本而变——
  `Digit3D-1.3.0 / 1.3.1` 的 dex 里**根本找不到 `q5/o0`、`z5/l0`**，
  且该谷歌版激活走的是"Google Play 购买"而非"本地状态类"，
  所以 smali 补丁对这两个版本 0 命中，无法通杀。
- 本模块 Hook 的是 **Google 官方 SDK 类名**（`com.android.billingclient.api.*`），
  这些类**不参与应用自身混淆**，跨版本/跨混淆方案都稳定不变。
  只要目标 App 用 Google Play Billing 做 PRO 激活（已确认：商品 id `unlock_pro`、`unlock_pro_2`），
  本模块即可生效，与 App 怎么改名无关 → 这才是通杀。

## 编译（两种方式）

### 方式 A：GitHub Actions 在线编译（推荐，零本地环境）
本仓库已内置 `.github/workflows/build.yml`，你只需：
1. 把本目录推送到你自己的 GitHub 仓库（main/master 分支）。
2. 在仓库 **Actions** 页等 `Build LSPosed Module` 跑完（约 3–5 分钟）。
3. 在对应 run 的 **Artifacts** 里下载 `ProUnlock-LSPosed-module`（即 `app-debug.apk`）。

CI 产出的是 **debug 自动签名** 的 APK，可直接 `adb install` 并加载，**无需你提供 keystore**。

### 方式 B：本地 Android Studio / Gradle 编译
需要本地装有 Android SDK（compileSdk 34）+ JDK 17：
- 用 Android Studio 打开本目录，Gradle Sync 后 `Build → Build APK(s)`。
- 或命令行（本地有 Gradle ≥ 8.2，或先 `gradle wrapper` 生成 wrapper）：
  ```bash
  cd lsposed_pro_unlock
  gradle assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk（debug 自签，可直接装）
  # 或 gradle assembleRelease 后再自行用 apksigner 签名
  ```

## 部署
1. 手机已安装 **LSPosed**（Magisk + Zygisk 或 Riru 方案）。
2. 把编译出的模块 APK 装入手机，在 LSPosed 管理器中**勾选本模块**，
   并在**作用域里勾选目标应用 `com.mobilecad.app`**。
3. **重启目标应用**（或重启系统）。
4. 启动应用，PRO 应直接处于已激活状态（无需购买、无需联网到 127.0.0.1）。

## 验证
- LSPosed 日志（或 `adb logcat | grep ProUnlock`）应出现：
  `queryPurchasesAsync -> 注入 2 笔假订单`
- 应用内"升级 PRO / 解锁 Pro"页应显示已拥有。

## 已知边界（如失效请排查）
1. **服务端校验**：若应用把购买 token 发到自己的后端做二次校验，
   本地伪造订单拿不到合法 token，后端会判无效。届时需要额外 Hook 应用的
   校验/ entitlement 网络返回（在 `BillingHook.hookVerifySignature` 已尽力对
   `Purchase` 上的 `verifySignature/verifyPurchase/isSignatureValid` 做强制 true，
   但若校验在别处，需按实际情况补 hook）。
2. **商品 id 变化**：若某版本 sku 改名，改 `BillingHook.SKUS` 数组即可。
3. **非 Play 渠道版**：纯离线/其他渠道激活不走 Play Billing 的，本模块不适用
   （那种仍走本地状态类，需用 smali 补丁针对具体混淆适配）。

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
            ├── Main.java              # IXposedMod 入口
            ├── BillingHook.java       # 计费 Hook 核心
            └── MainActivity.java      # 占位 UI
```
