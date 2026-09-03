# Universal Billing Hook + 自动 VIP 拦截 (LSPosed)

面向 **任意 App** 的通用 LSPosed 模块，含两套互相独立的破解能力：

- **【A】Google Play Billing 通用解锁** —— 针对接入 Google Play Billing SDK 的 App；
- **【B】自动 VIP 拦截（SharedPreferences 方案）** —— 针对把会员/PRO/去广告状态存本地 `SharedPreferences` 的 App（**不要求走 Google 付费**，国内 App 也覆盖）。
- **【D】联网鉴权抗 HOOK 自测（NetLabHook，授权自测用）** —— 模拟针对服务端/联网鉴权型 App 的攻击面：OkHttp 响应篡改(T1)、SSL pinning 探测(T2)、WebView JS 注入/调用记录(T3)。默认 LOG_ONLY=只观测不改写；填规则关 LOG_ONLY 重建后测自己 App 能否被打穿。请勿用于破解他人服务。
- **【E】配置化精确返回值 Hook（MethodRuleHook，授权自测用）** —— 人工配置 `类.方法 -> 返回值`，强制改写目标 App 里某个具名业务方法（如 `CommonUtil.getLingPaiZuanShi()` 这类会员判定）的返回，返回值按方法真实返回类型自动转换。规则为空即跳过。仅自有/授权 App 自测。
- **【F】自动盲扫解锁（AutoVipProHook，授权自测用）** —— 遍历目标 App 里"类名含 vip/pro/premium/member" 的类与"方法名像会员判定"的方法，**按成员原有值类别**决定注入值（布尔解锁位→true、等级 int→高值、到期 long/日期→2099、档位 string→premium）。v16 起支持**按类名盲扫其字段**（含静态会员字段直接改写存储值）；v17 起支持**单例对象实例字段注入**（hook `getInstance()/get()/instance()` 等静态取实例方法，拿到会员单例后改写其会员实例字段）与**多 ClassLoader 深度枚举**（应用 loader + 非系统父链并集，覆盖分包/插件/壳延迟加载）；**v1.8 起并入原 C(ProActivator) 的「结构盲扫」**——不看类名/方法名，纯按"会员状态对象"构造器结构 `(boolean, Enum, long, boolean)`（激活位+档位枚举+到期戳）对每个已加载类自动探测并挂钩（内存对象型会员态、类名/方法名全混淆如指尖3D 也能命中），去白名单对所有勾选 App 生效；**v1.10 起为两级注入闸门**——第一级【恒注入，不读开关、近零误伤】＝结构盲扫 `(Z,Enum,J,Z)` + STRONG_BOOL 精确整词解锁位(isPro/isVip/isPremium…)，对指尖3D 这类内存对象会员态开箱即解锁；第二级【宽泛】由 `INJECT_WIDE` 控制（默认 false＝仅观测打 [UAuto] 不改值）＝inVipContext 放宽布尔/档位到期 getter/静态字段/单例实例字段，需连宽泛也注入时置 `AutoVipProHook.INJECT_WIDE=true` 重建（只勾选自己/获授权 App）。
- **【G】SQLite/DB 会员盲扫（DBSweeperHook，授权自测用）** —— 覆盖"会员态存本地 SQLite/Room 表、判定时 SELECT 出来比"的 App。hook `SQLiteDatabase.rawQuery/query` 出口 + `AbstractCursor` 的 `getString/getInt/getLong`，按**列名语义**把"布尔会员位列→true/1、等级列→顶级档"的读取改写成开通态；到期列因秒/毫秒二义只观测不强注入。默认 LOG_ONLY=只打 [UDB] 观测。
- **【I】网络层去广告（NetAdBlocker，v1.14 按 AdClose 思路重做）** —— 不再依赖"猜广告 SDK 类再屏蔽类"（拦不住广告联网、硬引用会崩）。改为**网络层域名拦截**：在本进程 hook `java.net.InetAddress` 的 DNS 解析出口（`getAllByName`/`getByName`），命中广告域名就让它"解析失败"（`getAllByName` 返回空数组 / 其它抛 `UnknownHostException`）。广告 SDK 无论用 OkHttp/HttpURLConnection/Socket，请求前都要解析广告域名——这一刀掐断，广告拿不到物料就无法渲染。**黑名单数据**：内置 AdClose 官方 17,475 条离线清单（`assets/adblock_domains.txt`，离线也有得拦）+ **可配置 URL 在线更新**（设置页填 `adblock_url`，异步拉取并入，失败回退内置）。匹配只拦命中域名、白域名/系统/业务正常解析不受影响；全逻辑 try/catch、只挂一次，面向加固目标也**绝不闪退**。UI 里 I/去广告 打勾仍作总开关。仅自有/授权 App 自测。

> ### 🆕 v1.12 更新：每通道打勾开关 + 广告护栏（防 VIP 注入把广告激活）
>
> **【每通道总开关】** 打开模块桌面入口（`kill vip` → MainActivity），为 A/Billing、
> B/UVip、D/NetLab、E/MethodRule、F/Auto、G/DB、I/去广告 各一个勾选框。打勾 → 该通道
> 对作用域勾选 App 生效；**不打勾 → 该通道完全不挂载**。勾选存模块自己的
> `SharedPreferences("ubilling_settings")`；被 hook 的 App 进程用 LSPosed 的
> `XSharedPreferences` 读到（`Settings.channelOn`，reload 节流）。改完勾选需【软重启 /
> 重启目标 App】。**默认全部开启**（不打开 UI 时行为与 v1.11 一致，向后兼容）。
> 依赖：`xposedminversion` 提到 **93** 并加 `xposedsharedprefs=true`，启用 LSPosed
> "New XSharedPreferences"，使 `MODE_WORLD_READABLE` 的勾选能跨进程读到。
>
> **【广告护栏 AdGuard】** 用户反馈"之前的通道会激活部分广告"。新增 `AdGuard.java`：
> 让 VIP 解锁通道（B/UVip、F/Auto、G/DB）在**注入前**判定候选是否属"广告服务控制/激活/
> 展示"上下文（广告 SDK 类、`banner_enable`/`ad_show`/`splashad`/`ad_load`/`cpm_init`/
> `ad_ready` 这类开关、方法、DB 列）——是则**跳过注入**（绝不把广告打开）并打一条
> `[UAdGuard]` 观测日志，便于实测是哪个通道把哪个广告相关对象判成了会员。
> 与【I】NetAdBlocker（网络层去广告）**互补**：AdGuard 管"别被 VIP 注入激活广告"，NetAdBlocker 管"掐断广告联网"。

> ### 🆕 v1.13 更新：去广告改"放行+观测"防闪退（HARD_BLOCK）〔v1.14 已被网络层方案取代，此段仅为历史记录〕
>
> **问题**：用户实测"勾了 I/去广告 后 App 闪退"。根因：旧版去广告用**整类屏蔽**——hook
> `ClassLoader.loadClass` 命中广告前缀就抛 `ClassNotFoundException`，让广告类加载不起来；
> 若目标 App 对广告 SDK 是**硬引用**(编译期直接 import/new)且无 try/catch，加载失败异常
> 抛到调用方栈 → **闪退**。
>
> **v1.13 修复**：`AdBlockHook.HARD_BLOCK` 默认 **false** —— loadClass 命中广告前缀
> **放行**（类正常加载），只打 `[UAd] 放行广告类(防闪退,未屏蔽): <类名>` 观测日志，**绝不闪退**。
> 确要认真屏蔽某家广告 SDK、且确认它非硬引用（懒加载/反射/带保护）时，才置 `HARD_BLOCK=true`
> 恢复整类屏蔽。
>
> **边界说明**：通用去广告在"硬引用即崩/自绘广告/聚合 SDK"下没有既彻底又不崩的银弹，
> 业界(AdClose 等)都是逐 App 适配。本通道先保证**不崩** + 用 `[UAd]` 观测告诉你目标 App
> 实际命中哪些广告域名；要精准拦某家请装后把 `[UAd] 拦截广告域名解析` 日志发我，我针对性补域名。

> ### 🆕 v1.14 更新：去广告改为"网络层域名拦截"（NetAdBlocker，参考你提供的 Close_3.9.3.apk=AdClose）
>
> **为什么换**：用户实测旧 I 通道"拦截广告还是没效果"。旧做法（hook `ClassLoader.loadClass`
> 屏蔽广告 SDK 类）拦不住广告 SDK 的**联网物料下发**——广告是拉回来的，不是类加载出来的；
> 且 v1.13 默认放行后等于没拦。你提供 Close_3.9.3.apk 逆向结论：AdClose 是**独立去广告模块**，
> 靠 17,475 条广告域名黑名单 + hook `java.net.InetAddress`(DNS) + 网络请求层。我们取其**可干净复用的
> 网络层方案**写进模块（不搬其混淆/专有代码）。
>
> **实现**：
> - 删除 `AdBlockHook.java`（旧 loadClass 屏蔽广告类法）。
> - 新增 `NetAdBlocker.java`：hook `java.net.InetAddress.getAllByName/getByName`，命中黑名单域名
>   令其解析失败（安全，网络层本就允许该失败，绝不闪退）→ 掐断广告 SDK 联网。
> - 新增 `BlockDomainStore.java`：黑名单仓库。内置 `assets/adblock_domains.txt`（从 AdClose 官方清单
>   抽取 17,475 条作离线回退）+ **可配置 URL 在线更新**。普通域名用后缀 HashSet 高效匹配，通配域名
>   用正则小集合。归一化兼容裸域名 / hosts(0.0.0.0 x.com) / 带协议 path 的 URL。
> - `Settings` 增 `adblock_url`；`MainActivity` 增在线 URL 输入 + "保存并立即更新"。
> - `AdGuard.java` 保留（它另管"防 VIP 注入把广告激活"，与去广告互补）。
>
> **注意**：在线更新需网络；不填 URL 也照常能用内置 17K 清单去广告。装后把 `[UAd] 拦截广告域名解析`
> 日志发我可针对性补域名。仅自有/授权 App 自测。

与 `lsposed_pro_unlock`（只针对 `com.mobilecad.app` 的专版）不同，本模块 VIP/PRO 解锁通道**代码层面无包名白名单**——
但 **v14 起全部通道只作用于你在 LSPosed 作用域里勾选的 App**（借 LSPosed 的进程分发机制），
绝不对未勾选应用 / 系统进程做任何拦截或改写。
（例外：去广告【I】通道会按"广告域名黑名单"拦截这些域名在本进程的 DNS 解析——仅作用域勾选 App 的进程内生效，
不影响 VIP 解锁的无差别通用语义。）

---

## 【A】通用 Billing 解锁

- **无包名白名单**：只要 App 进程加载了 Billing SDK 就生效；
- **动态包名**：伪造订单的 `packageName` 自动取当前进程包名；
- **自动探测 SKU**：拦截 `queryProductDetailsAsync` 读取 App 真正查询的商品 id，自动视为已购并打日志——无需预先知道目标 App 用哪个 SKU；
- **内置 SKU 表兜底**：见 `UniversalBillingHook.EXTRA_SKUS`，可自由增删。

拦截 `BillingClient.queryPurchasesAsync` 阻断真实网络查询、直接回调"已购"，
同时拦截 `launchBillingFlow` 视为已购。

## 【B】全兼容自动 VIP 拦截（核心新能力）

**目标：把我所知道的 VIP/会员/PRO/解锁判定方式尽量全兼容；凡是“到期/有效期”读取，
一律让 App 认为已续费到 2099-01-01。**

原理：付费态大多持久化在 `SharedPreferences`。它虽是系统 SDK 类、**永不混淆**，
且 App 调用的 `getXxx` 方法名**本身就决定了返回类型** → 类型判断天然成立。
命中判定 = key 名转小写后匹配**多套语义关键词**，而不是单一列表：

| 语义 | 代表关键词（截取） | 命中后的意图 |
|---|---|---|
| 付费/会员/已购 | `vip premium paid purchase license entitlement member gold` | 判“已付费” |
| 升级档位/PRO | `pro_ _pro is_pro plus_ deluxe ultimate` | 判“高级版” |
| 解锁功能 | `unlock full_version registered full_access` | 判“已解锁” |
| 去广告 | `remove_ads no_ads ad_free adblock disable_ads` | 判“广告已去除” |
| 到期/有效期 | `expire expiry deadline valid_until end_time purchase_date` | 判“未过期”→ **回 2099-01-01** |

裸 `pro`（profile / progress / product …会误伤）单独走排除表，见 `PRO_FALSE_POSITIVES`。

**注入矩阵（关键：日期统一 2099-01-01）：**

| App 调用的方法 | 返回类型 | 命中后注入 |
|---|---|---|
| `getBoolean("is_vip")` | 布尔 | `true` |
| `getLong("vip_expire")` | 到期时间戳 | **2099-01-01**（毫秒 4070908800000，恒“未过期”） |
| `getInt("is_paid")` | 整数 | `1`（判 `>0` 即解锁） |
| `getInt("remaining_days")` | 到期/剩余类 int | `99999`（当作“剩余超多”，恒未过期） |
| `getFloat(...)` | 小数 | `1.0` |
| `getString("membership")` | 档位字符串 | `"premium"`；默认值已是 true/1/yes/on 则保留 |
| `getString("vip_expire")` | 日期字符串 | `"2099-01-01"`；默认值是毫秒/秒 epoch 则回 `4070908800000`/`4070908800` |
| `getStringSet("permissions")` | 集合 | `{premium, vip, pro}` |

> 日期统一规则（你硬性要求）：SP 里到期可能存毫秒 Long、秒 Long、或字符串
> `"yyyy-MM-dd"`/epoch 三种形态，拦截器分别回灌对应的 2099 形态，让
> `now < expire` 的“未过期”判断恒成立。

**挂载点（v14 起，只针对作用域勾选 App）**：在 `handleLoadPackage()` 于**被勾选 App 的进程内**
挂一次 SP 钩子 → **只拦截勾选 App 自己的 SharedPreferences**，绝不对其它应用/系统进程操作。
与是否加载 Billing SDK 无关（正好补上【A】"国内非 Billing App" 的空档）。
> ⚠️ v14 行为变更：此前 UVip 在 zygote 期挂载、对**全部进程**都拦截 SharedPreferences，
> 会对未勾选应用产生越权与误伤。v14 起一律**只作用域勾选的 App**，想对哪个 App 的 SP
> 型会员解锁，就把那个 App 勾进作用域并重启。

### 【B+】观测学习闭环（v5：只观测第一次，之后纯回灌不重扫）

词表是"拍脑袋"的，总会漏掉某 App 特有的 key（如混淆的 `a_b_c`、自造 `vip_token`）。
为补齐这块，运行时加了**一次性**的"观测-学习-回灌"闭环：

1. **磁盘扫描（仅首次）**：目标 App 第一次读 SP 时，后台扫它自己的
   `/data/data/<该App>/shared_prefs/*.xml`，把所有"名字像会员 / 值像 epoch 时间戳 /
   名字含会员词且值像日期"的条目找出来。
2. **运行读取观测（仅首次窗口）**：每次命中注入时，把 `key / 方法 / 形态`
   记入内存规则；只有 `hkrules.txt` 尚未生成时才追加 `hkhits.log`。
3. **规则回灌（每次启动都生效）**：被确认的会员/日期 key 进入规则表并落盘；
   之后每次启动**先读回规则**再回灌，即使 key 完全不含词表关键词也能命中。

**只观测第一次（性能收敛）**：观测文件每个 App 都用固定名
`hkrules.txt` / `hkrecords.txt` / `hkhits.log`。扫描前先看 `hkrules.txt` 是否存在：
- **不存在** → 执行首次三段式闭环（扫 + 写记录 + 生成规则）；
- **已存在** → 只把规则热加载进内存回灌，**跳过重扫/重写**。

这样只有第一次观测有扫描开销，后续启动零 XML 扫描、零规则重写，
避免反复扫描造成的性能影响与卡顿。

观测记录写到**被观测 App 自己**的目录（观测跑在被勾选 App 自己的进程内、
同 uid 才能写它自己的沙箱；写模块自己的目录会跨 uid 被拒）：

```
/data/data/<该App>/files/uvip/
├── hkrules.txt    # 学习规则 (KEY<TAB>shape)：存在=已完成首次学习；下次启动热加载回灌；可手工增删
├── hkrecords.txt  # 首次磁盘扫描出的会员/日期 key + 形态，供人工挑字段
└── hkhits.log     # 首次学习窗口内的运行命中 key::方法::形态
```

> 诚实边界：观测只能看到"当前(未付费)读取/存储"的形态，看不到"付费该返回什么"，
> 因此具体注入值仍靠类型自适应（getXxx 已决定类型 + 日期统一 2099）。观测的真正
> 增益是**自动锁定"哪些 key 是会员字段"**，即使它们是混淆 key 也能命中。

### 【B】的边界（务必知悉）

- ✅ 覆盖 **"付费态存 SharedPreferences、本地读取即判定解锁"** 的 App。
- ⚠️ 若 VIP 是 **服务器下发的 entitlement**、或 App 启动后**从网络拉取再覆盖 SP**，
  拦截会"读一次被盖一次"，不保证解锁。
- ⚠️ 命中即**改写内存返回值，不改磁盘文件**，纯运行时注入，退出即失效。
- 💡 即使某个 App 没解锁成功，**日志里的 `命中(自动赋值)` 行**会告诉你它读了哪些
  `key`；**观测记录**（`/data/data/<该App>/files/uvip/*`）会把磁盘里真正的会员/日期
  key 也列出来，据此能人工判断 / 微调关键词表。

---

## 使用

1. LSPosed 中启用本模块；
2. 作用域（按通道勾选，见下表）；
3. 重启系统/软重启（zygote 钩子才生效）；
4. 排查日志：
   - `[UBilling]` → Billing 回灌是否触发、探测到哪些 SKU；
   - `[UVip]` → 命中了哪些 SP key、自动赋了什么值。
   - `[UNet]` → (联网自测) 是否命中 OkHttp 响应面 / SSL pinning / WebView JS 调用。

### 作用域速查（v14 起全部通道都只作用域勾选的 App）

| 想要的效果 | 需要勾选 |
|---|---|
| 【B】SP/VIP 对某个 App 生效 | 勾**那个 App**（不再需要系统框架） |
| 【A】Billing 回灌对某个 App 生效 | 勾**那个 App** |
| 【D】【E】【F】【G】对目标 App 生效 | 勾**那个 App** |

> v14 起**不勾系统框架也正常**：UVip(SP) 走 handleLoadPackage 按进程触发，只操作你
> 勾选的 App，绝不对未勾选应用 / 系统进程拦截 SharedPreferences。想解锁哪个 App，
> 就勾哪个 App，改完作用域需重启。

### 联网型 App 怎么自测抗 hook（【D】NetLabHook）

服务端/联网鉴权型 App（如 DCloud/H5 + 云函数发卡）不属于【A】【B】等解锁范围，
但若要验证“自己的 App 防不防得住 hook”，勾选它后看 `[UNet]` 日志：

| 日志 | 含义 | 说明你的 App |
|---|---|---|
| `命中 OkHttp 响应面` | 攻击者可 hook 你的网络响应 | 关键放行必须在服务端判；响应可加签名/加密 |
| `★CertificatePinner.check 被调用` | 你启用了证书固定 | 防抓包/防中间人改包，建议保留 |
| `[T3] evaluateJavascript / javascript:` | 攻击者可在 WebView 注入 JS | 关键判定不要放前端；JS 桥收紧 |

默认 LOG_ONLY=true 只观测。要“实战改写”测到底：编辑 `NetLabHook.REPLACEMENTS`
(响应 JSON 字段替换) 与 `NEEDLE_JS`(注入脚本)，LOG_ONLY=false 后重建再测。
若改完服务端仍不放行 = 防住了（服务端不可信客户端的设计生效）。

### kill vip 的边界（哪类 App 无效）

- 只对"**付费态存本地(SP/内部对象) + 本地判断即解锁**"的 App 有效。
- **服务端账号 entitlement**（登录后服务器发卡、功能放行在服务端、本地只是展示缓存）
  的 App 无效——例：DCloud/H5 壳 App，VIP 状态存在 `plus.storage['userdata']`(JSON) 且
  由腾讯云函数下发、逻辑远程 JS 动态加载，客户端无 SP/Billing/内部激活消费点可 hook。

## 构建（GitHub Actions）

推到仓库 `main` / 触发 workflow 后产物在
`app/build/outputs/apk/debug/app-debug.apk` 的 CI artifact 里。

## 目录

```
app/src/main/java/com/example/ubilling/
├── Main.java                  # 入口：按作用域进程 + 每通道勾选开关挂载 A/B/D/E/F/G/I
├── UniversalBillingHook.java  # 【A】通用 Billing hook（回灌已购 + 探测 SKU）
├── UniversalVipSweeper.java   # 【B】自动 VIP 拦截 + 观测学习闭环（SP + 词表 + 类型自适应 + 规则回灌）——只作用域勾选 App
├── NetLabHook.java            # 【D】联网抗hook自测: 响应篡改/pinning探测/WebView JS面
├── MethodRuleHook.java        # 【E】配置化精确返回值 Hook：类.方法 -> 返回值(按返回类型自动转换)
├── AutoVipProHook.java        # 【F】自动盲扫: 方法名/类名强词 + 字段按原值类别 + 单例实例字段注入 + 结构盲扫(内存对象构造器签名) v1.10两级闸门
├── NetAdBlocker.java          # 【I】网络层去广告(v1.14, AdClose思路): hook InetAddress DNS, 命中广告域名令解析失败掐断广告联网; 全try/catch绝不闪退
├── BlockDomainStore.java      # 【I】去广告域名黑名单仓库: 内置assets 17,475条(AdClose清单) + 可配置URL在线更新; 后缀HashSet+通配正则匹配
├── DBSweeperHook.java         # 【G】SQLite/DB 会员盲扫: hook query出口+Cursor读取, 按列名语义改写
├── AdGuard.java               # v1.12 广告护栏: 判断广告SDK类/广告服务控制(SP key/方法/列/类), B/F/G 注入前跳过, 防"VIP激活广告", 打 [UAdGuard]
├── Settings.java              # v1.12 运行期配置读取: XSharedPreferences 读 MainActivity 勾选, Settings.channelOn 决定各通道是否挂载; adblock_url 在线黑名单URL
└── MainActivity.java          # v1.12 UI: 每通道打勾总开关 + v1.14 去广告在线URL输入/立即更新(存 ubilling_settings, MODE_WORLD_READABLE)
```
`app/src/main/assets/adblock_domains.txt` —— 去广告内置离线黑名单（AdClose 官方 17,475 条）。
