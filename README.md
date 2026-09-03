# Universal Billing Hook (LSPosed)

针对 **任意接入 Google Play Billing SDK** 的应用做通用"已购回灌"的 LSPosed 模块。
与 `lsposed_pro_unlock`（只针对 `com.mobilecad.app` 的专版）不同，本模块：

- **无包名白名单**：只要 App 进程加载了 Billing SDK 就生效；
- **动态包名**：伪造订单的 `packageName` 自动取当前进程包名，不再写死；
- **自动探测 SKU**：拦截 `queryProductDetailsAsync` 读取 App 真正查询的商品 id，
  自动视为已购并打日志——无需预先知道目标 App 用哪个 SKU；
- **内置 SKU 表兜底**：见 `UniversalBillingHook.EXTRA_SKUS`，可自由增删。

## 破解逻辑

拦截 `BillingClient.queryPurchasesAsync`，阻断真实网络查询，直接回调一笔"已购"，
已购集合 = 探测到的 SKU ∪ 内置 SKU 表。同时拦截 `launchBillingFlow` 视为已购。

## 覆盖范围与局限（重要）

- ✅ 覆盖 **"查询到目标 SKU 已购即解锁/PRO/去广告"** 这类把购买状态存在本地的 App。
- ⚠️ 对 **服务端二次验签**、或 **购买后由自家服务器下发授权** 的 App，本模块只能让
  客户端看到"已购"，能否真正解锁取决于 App 是否信任本地查询结果。
- ⚠️ 国内多数 App 不走 Google Play Billing（走支付宝/微信/自研内购），对它们无效。

## 构建（GitHub Actions）

推到任意 GitHub 仓库的 `main` 分支即触发 `build.yml`，产物在
`app/build/outputs/apk/debug/app-debug.apk` 的 CI artifact 里。

## 使用

1. LSPosed 中启用本模块；
2. 作用域勾选希望作用的目标 App（或勾系统框架 + 目标 App）；
3. 重启目标 App；
4. 排查日志过滤 `[UBilling]`，重点看 `探测到 App 查询 SKU:` 行。

## 目录

```
app/src/main/java/com/example/ubilling/
├── Main.java                  # 入口：探测 Billing SDK，无白名单
├── UniversalBillingHook.java  # 核心通用 hook（回灌已购 + 探测 SKU）
└── MainActivity.java          # 占位 UI
```
