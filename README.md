# Universal Billing Hook + 自动 VIP 拦截 (LSPosed)

面向 **任意 App** 的通用 LSPosed 模块，含两套互相独立的破解能力：

- **【A】Google Play Billing 通用解锁** —— 针对接入 Google Play Billing SDK 的 App；
- **【B】自动 VIP 拦截（SharedPreferences 方案）** —— 针对把会员/PRO/去广告状态存本地 `SharedPreferences` 的 App（**不要求走 Google 付费**，国内 App 也覆盖）。

与 `lsposed_pro_unlock`（只针对 `com.mobilecad.app` 的专版）不同，本模块**无包名白名单**。

---

## 【A】通用 Billing 解锁

- **无包名白名单**：只要 App 进程加载了 Billing SDK 就生效；
- **动态包名**：伪造订单的 `packageName` 自动取当前进程包名；
- **自动探测 SKU**：拦截 `queryProductDetailsAsync` 读取 App 真正查询的商品 id，自动视为已购并打日志——无需预先知道目标 App 用哪个 SKU；
- **内置 SKU 表兜底**：见 `UniversalBillingHook.EXTRA_SKUS`，可自由增删。

拦截 `BillingClient.queryPurchasesAsync` 阻断真实网络查询、直接回调"已购"，
同时拦截 `launchBillingFlow` 视为已购。

## 【B】自动 VIP 拦截（核心新能力）

**目标：自动搜索 App 里控制 VIP/PRO/会员/解锁的状态并自动按类型赋值。**

原理：付费态大多持久化在 `SharedPreferences`。它虽是系统 SDK 类、**永不混淆**，
且 App 调用的 `getXxx` 方法名**本身就决定了返回类型** → 类型判断天然成立：

| App 调用的方法 | 返回类型 | 命中后自动注入 |
|---|---|---|
| `getBoolean("xxx", false)` | 布尔 | `true` |
| `getInt("xxx", 0)` | 整数 | `1`（多数判 `>0` 即解锁） |
| `getFloat(...)` | 小数 | `1.0` |
| `getLong("xxx_expire", 0)` | 到期时间戳 | 距今 +30 年（恒为"未过期"） |
| `getString("xxx", null)` | 字符串 | `"premium"` |
| `getStringSet(...)` | 字符串集合 | `{premium, vip, pro}` |

**命中判定** = key 名转小写后含付费关键词：`vip / premium / paid / unlock /
unlocked / license / licence / entitle / member / subscrib / subscription /
gold / activate / activated / pro_`；裸 `pro` 另走"排除误伤词"逻辑
（profile / progress / prompt / product… 不会误伤）。关键词与注入值都在
`UniversalVipSweeper` 顶部**常量数组，可自行增删调参**。

**挂载点**：`Main.initZygote()` 系统级挂一次 → 对所有进程生效，与是否加载
Billing SDK 无关（正好补上【A】"国内非 Billing App" 的空档）。

### 【B】的边界（务必知悉）

- ✅ 覆盖 **"付费态存 SharedPreferences、本地读取即判定解锁"** 的 App。
- ⚠️ 若 VIP 是 **服务器下发的 entitlement**、或 App 启动后**从网络拉取再覆盖 SP**，
  拦截会"读一次被盖一次"，不保证解锁。
- ⚠️ 命中即**改写内存返回值，不改磁盘文件**，纯运行时注入，退出即失效。
- 💡 即使某个 App 没解锁成功，**日志里的 `命中(自动赋值)` 行**也会告诉你它到底
  读了哪些 `key`（如 `is_vip::getBoolean`），据此能人工判断 / 微调关键词表。

---

## 使用

1. LSPosed 中启用本模块；
2. 作用域勾选目标 App；若要用【B】，勾"系统框架"即可对全部 App 生效；
3. 重启目标 App；
4. 排查日志：
   - `[UBilling]` → Billing 回灌是否触发、探测到哪些 SKU；
   - `[UVip]` → 命中了哪些 SP key、自动赋了什么值。

## 构建（GitHub Actions）

推到仓库 `main` / 触发 workflow 后产物在
`app/build/outputs/apk/debug/app-debug.apk` 的 CI artifact 里。

## 目录

```
app/src/main/java/com/example/ubilling/
├── Main.java                  # 入口：探测 Billing SDK；initZygote 挂载 UVip
├── UniversalBillingHook.java  # 【A】通用 Billing hook（回灌已购 + 探测 SKU）
├── UniversalVipSweeper.java   # 【B】自动 VIP 拦截（SharedPreferences + 关键词 + 类型自适应）
└── MainActivity.java          # 占位 UI
```
