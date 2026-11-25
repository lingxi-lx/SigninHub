# 签到中心 · SigninHub

**纯手机端**定时自动完成 **WorkBuddy、Trae Solo** 的每日签到打卡——装上安卓 App 就完事，**不需要电脑、不需要服务器、不需要一直开机挂机**。App 内置完整签到引擎：官方协议直连优先、接口模式跟上、无障碍自动点击兜底；（可选）也提供电脑端后端，给想在电脑上挂机的用户。

> **豆包签到已于 2026-11-20 下线。** 豆包服务端对签到写接口固定返回 `710010702`，官方客户端手动点签到同样失败，属平台侧风控封禁，客户端无修复空间。详见 [docs/DOUBAO.md](docs/DOUBAO.md)。

> 支持三种打法：
> 1. **WorkBuddy 官方协议直连**（已实测可用）：先查签到状态再领取，幂等、不会重复领；凭据自动从本机 DSH vault / 桌面端发现，也可手动填 accessToken。详见 [docs/WORKBUDDY.md](docs/WORKBUDDY.md)。
> 2. **接口模式**：用抓到的 Cookie / Token 直接请求签到接口（Trae Solo 走这条）。
> 3. **浏览器模式**：后端用 Playwright 打开网页自动点「签到」，接口变动时无需改代码。
> 4. **手机无障碍兜底**：在官方 App 里自动点「签到」按钮，免抓包（仅安卓）。

---

## 📦 成品下载

**不想自己编译？直接下载编译好的安卓 App（推荐）：**

**👉 [从 GitHub Releases 下载 SigninHub-mobile.apk](https://github.com/Ayanguser/SigninHub/releases/latest)**

> 当前版本：**V2.63**（versionCode 291，正式签名包，约 1.4 MB）。本次更新内容见 [docs/CHANGELOG-v2.63.md](docs/CHANGELOG-v2.63.md)。

> Releases 页直链下载、带版本号与历史版本；若附件不可用，也可用仓库内备份：[`release/SigninHub-mobile.apk`](release/SigninHub-mobile.apk)（校验值见 [`release/SHA256SUMS.txt`](release/SHA256SUMS.txt)）。电脑/服务器端后端用 `backend/` 源码运行。
---


> 当前状态：**WorkBuddy 已可真签到**（实测「连续 20 天 · 今日 100 积分」）；Trae Solo 需按
> [docs/REAL_CHECKIN.md](docs/REAL_CHECKIN.md) 配好接口或浏览器模式；豆包已下线。


---

## 与常见部署方案的对比：为什么 SigninHub 能整体脱离电脑运行

GitHub 上的自动签到工具大多是**脚本 / 服务器 / 网关**形态：Python 脚本扔 VPS 上 cron 跑、
Docker 容器挂 NAS、Home Assistant 插件轮询、或桌面程序挂机。它们都能用，但共同前提是
**得先养一台一直开机的机器**。SigninHub 把整条链路搬进了安卓 App 本身：

| | SigninHub（本项目） | 脚本 + VPS | Docker / NAS | 桌面挂机程序 |
|---|---|---|---|---|
| 需要一直开机的设备 | 手机（本来就开着） | 一台 VPS | NAS/软路由 | 一台电脑 |
| 月度成本 | 0 | 约 30-60 元 | 有 NAS 才有 | 电费 + 折旧 |
| 部署门槛 | 装 APK → 登录 | SSH、Python、pip、cron | 写 compose.yml、映射目录 | 常驻后台、防休眠 |
| 公网暴露面 | 无（不出站连接） | 有（SSH 端口暴露） | 视配置，常见有 | 视配置 |
| 掉签风险 | 手机重启/杀后台已处理（开机自启 + 无障碍兜底） | VPS 宕机、进程被杀 | 容器崩溃 | 电脑休眠/断电/自动更新重启 |
| 凭据存放 | Android Keystore + AES/GCM 硬件级加密，只在本机 | 明文/半明文配置文件躺在服务器 | 配置文件随镜像/卷分发 | 配置文件在电脑上 |
| 多账号 | App 内多账号管理 | 自己改脚本 | 自己改脚本 | 视实现 |
| 通知推送 | 内置（服务号/邮件等） | 自己接 | 自己接 | 视实现 |

几个具体差异：

**1. 没有「最后一公里」依赖。** 脚本方案的电脑端只负责跑签到，控制、查看、重试都要再
SSH 回去或另开网页。SigninHub 的 App 里是**完整实现**（LocalApi 141KB 的 REST 引擎 +
LocalCheckin 104KB 的签到协议层），签到、查积分、历史记录、通知、多账号全部本地闭环，
没有「手机只是个遥控器、真正干活的是另一台机器」的问题。

**2. 不需要公网入口，也就没有被打面。** VPS 和 NAS 方案通常要开 SSH 端口或 Web 面板，
这些就是攻击面。SigninHub 只有 App 主动出站请求目标服务，不监听公网，没有可扫描的入口。

**3. 凭据不上云。** Cookie / token 用 Android Keystore（硬件安全模块）+ AES/GCM 加密后
存在手机本地，卸载 App 即销毁。服务器方案的配置文件通常明文躺在 VPS 上，机器一丢全交代。

**4. 电脑端后端仍然保留，但只是「可选项」。** 如果你就是想在电脑上跑（比如挂机党），
`backend/` 源码 + 浏览器 PWA 界面一切照旧同一套功能，两种形态，按需选。

> 一句话：别人的方案是「电脑养脚本，手机看一眼」；SigninHub 是「手机就是全部」。

---

## 界面：精细化交互动效（可安装到手机桌面）

后端自带移动端界面，**手机与电脑同一 WiFi，浏览器打开 `http://电脑IP:8787/` 即可**。
支持**安装到桌面**（PWA）：浏览器菜单点「添加到主屏幕 / 安装应用」，之后有独立图标、全屏无地址栏。

界面刻意做了 6 处细腻交互：

| # | 交互 | 在签到软件里的作用 |
|---|---|---|
| ① | 完成反馈（收缩→画勾→粒子迸发） | 每个服务卡的「签到」按钮，签到成功后多阶段反馈 |
| ② | 勾选联动（进度环/数字/顶部背景） | 顶部「今日进度」随已签服务数实时联动，满勤变绿并提示 |
| ③ | 叠卡展开（点击展开/整齐叠回） | WorkBuddy / Trae Solo 等服务卡可折叠成卡垛省空间 |
| ④ | 吸附卡 + 逐段画线 | 「最近 7 天」滑动松手自动居中吸附，停稳后逐段绘制时间线 |
| ⑤ | 惯性滚轮（惯性/吸附刻度/回弹） | 设置里选择**每日自动签到时间**，直连后端定时 |
| ⑥ | 圆形扩散转场 | 底部「签到 / 记录 / 设置」切换时以点击点为圆心扩散过渡 |

界面能力（不用再手改 config.json）：

- **签到配置**：设置页逐服务展开 —— 选「接口模式」填 URL / 请求头 / 请求体 / 成功判定；或选「浏览器模式」填页面地址与选择器。点「测试签到」会真跑一次（不写记录），并实时显示「就绪 / 未就绪」与缺失项。
- **访问口令**：设置页可设 / 清除口令，防止同一局域网其他人乱点你的签到。
- **服务开关**：每个服务可单独「参与每日签到」。
- **记录与统计**：按服务筛选、本月完成天数、成功数统计。
- **连接状态**：连不上会显示错误条 + 「重试」，不再静默失败。

截图：早期版本放在仓库根的 `preview-*.png`，V2.25 清理磁盘缓存时已移除，不再随仓库分发。

> 无障碍友好：系统开启「减少动态效果」或地址带 `#noanim` 时自动降级为静帧。

---

## 目录结构

```
signin-hub/
├─ start.bat                     # Windows 一键启动后端
├─ backend/
│  ├─ run.py                     # 启动入口
│  ├─ requirements.txt           # 仅浏览器模式需要 playwright
│  ├─ tools/
│  │  ├─ check.py                # 配置体检：哪些服务还不能真签到
│  │  ├─ login.py                # 浏览器模式：捕获登录态
│  │  ├─ tryone.py               # 单服务调试，打印真实请求结果
│  │  └─ wb_status.py            # WorkBuddy 只读状态探测（不签到）
│  ├─ signbot/
│  │  ├─ config.py               # 配置读写（data/config.json，兼容 BOM）
│  │  ├─ db.py                   # SQLite 记录 + 最近7天/连续天数/月度统计
│  │  ├─ provider.py             # 执行引擎（wb/接口/浏览器/演示）+ 就绪检测
│  │  ├─ workbuddy.py            # WorkBuddy 官方签到协议（状态/领取/刷新/凭据发现）
│  │  ├─ runner.py               # 编排 + 汇总 + 推送
│  │  ├─ notify.py               # 通知（钉钉/企业微信/飞书/通用 webhook）
│  │  ├─ scheduler.py            # 每日定时
│  │  └─ server.py               # REST API + 静态服务
│  └─ web/                       # 移动端界面（PWA）
│     ├─ index.html
│     ├─ manifest.webmanifest    # 可安装到桌面
│     ├─ sw.js                   # 离线壳（接口不缓存）
│     └─ static/ app.css · app.js · icon-*.png
├─ docs/REAL_CHECKIN.md          # 从演示 → 真签到（也可直接在界面里配）
├─ docs/WORKBUDDY.md             # ★ WorkBuddy 官方签到协议（已实测可用）
├─ docs/TRAE.md                  # ★ Trae Solo 官方签到协议
├─ docs/DOUBAO.md                # ⚠ 豆包签到（已下线 · 平台风控 710010702，留作存档）
├─ docs/ANDROID.md               # ★ APK 构建（界面 = 内置网页版）
├─ docs/CONNECT_OTHERS.md        # 其它服务怎么接（粘贴 cURL 或浏览器模式）
├─ docs/CHANGELOG-v2.63.md       # 版本更新日志（V2.63 正式发布）
├─ SigninHub-1.2-fix.apk         # 可直接安装的安卓包（本地签到，不依赖电脑）
└─ android/                      # 安卓工程（WebView 承载网页版 + Kotlin 本地 API）
```

---

## 一、启动电脑端（后端）

需要 Python 3.9+（核心用标准库，**无需安装任何依赖**即可跑通接口/演示模式）。

**Windows：** 双击 `start.bat`

**命令行：**
```bash
cd signin-hub/backend
python run.py
```

启动后：
- 控制台地址：`http://127.0.0.1:8787/` （浏览器打开即为可视化控制台，手机连同一 WiFi 用 `http://电脑IP:8787/` 也能打开）
- 数据目录：`backend/data/`（`config.json` 配置、`signbot.db` 记录）

首次启动会生成 `backend/data/config.json`，默认是**演示模式（dry-run）**：点击签到会模拟成功、不发起真实请求，用来先跑通全链路。填入真实接口后自动切换为真实签到。

---

## 二、配置三个服务的「接口模式」（核心）

打开 `backend/data/config.json`，把每个服务 `http` 段里的占位符换成你抓到的真实值。

### 1) 先抓包拿到 Cookie 和签到接口

用 **Charles / Fiddler / HTTP Toolkit** 或手机抓包 App，登录对应服务后：

1. 在官方 App / 网页里**手动点一次「签到」**。
2. 在抓包工具里找到那次请求：记录 **请求 URL、请求方法、请求头（重点是 `Cookie` 或 `Authorization`）、请求体、以及成功时的返回 JSON**。
3. 把返回 JSON 里表示「成功」的字段名与值记下来（例如 `{"code":0,"msg":"ok"}` 里 `success_expr="code"`, `success_value=0`）。

> 说明：各服务的签到接口地址与字段会随版本变化，因此这里用**占位符 + 你抓到的真实值**填写，不写死。抓一次即可长期使用（Cookie 过期后重新抓一次更新）。
>
> **豆包已下线（2026-11-20）**：服务端对签到写接口固定返回 `710010702`，官方客户端手动点也失败，属平台侧风控，配置里已置 `enabled: false`，示例仅保留结构。

### 2) 填进 config.json 示例

```json
{
  "providers": {
    "doubao": {
      "enabled": false,
      "retired": "2026-11-20 服务端风控 710010702（签到入口已下线）",
      "mode": "http",
      "http": {
        "method": "POST",
        "url": "https://实际抓到的签到接口",
        "headers": {
          "Content-Type": "application/json",
          "Cookie": "你抓到的完整 Cookie",
          "User-Agent": "你抓到的 UA"
        },
        "body": { "如抓包所示": "字段" },
        "body_type": "json",
        "success_expr": "code",
        "success_value": 0
      }
    },
    "workbuddy": { "...": "同上，按抓包填写" },
    "traesolo":  { "...": "同上，按抓包填写" }
  }
}
```

字段说明：
| 字段 | 含义 |
|---|---|
| `method` | `GET` / `POST` / `PUT` |
| `url` | 签到接口地址 |
| `headers` | 请求头，至少带 `Cookie` 或 `Authorization` |
| `body` / `body_type` | 请求体；`body_type` 为 `json` 或 `form` |
| `success_expr` / `success_value` | 从返回 JSON 取值判定成功，支持 `data.status` 这种点路径 |
| `body` 内可用 `{date}` 占位当前日期 | 例如 `{"day":"{date}"}` |

保存后无需重启即生效（每次签到都会重新读取 config.json）。

### 3) 浏览器模式（不想抓接口时）

把 `mode` 改成 `"browser"`，并设置：
```json
"browser": {
  "login_url": "登录页地址",
  "sign_url": "签到页地址",
  "click_selectors": ["text=签到", "text=每日签到", "button:has-text('签到')"],
  "headless": true,
  "storage_state": "doubao_state.json"
}
```
然后安装 Playwright（一次即可）：
```bash
pip install playwright
playwright install chromium
```
首次运行会打开页面，若需登录可临时把 `headless` 设为 `false` 手动登录一次，登录态会存进 `storage_state`，之后无头运行即可。

---

## 三、通知推送

在 Web 控制台「设置」里填 **Webhook**，或用 `config.json`：
```json
"notify": {
  "enabled": true,
  "kind": "generic",
  "webhook_url": "https://你的机器人webhook"
}
```
- `kind`：`generic`（通用/自建）、`dingtalk`（钉钉）、`wecom`（企业微信）、`feishu`（飞书）、`pushplus`（推个人微信）、`serverchan`（Server酱）、`bark`（推 iOS）、`wxpusher`（推微信服务号）。
- **渠道会自动识别**：填的地址 host 是 `qyapi.weixin.qq.com` / `dingtalk.com` / `feishu.cn` / `pushplus.plus` / `ftqq.com` / `api.day.app` / `wxpusher.zjiecode.com` 时，即使 `kind` 填错也会按真实域名发对应的 payload。
- **PushPlus（推荐给个人微信）**：pushplus.plus 微信扫码登录 → 复制 token → 地址填 `https://www.pushplus.plus/send/<token>`，也可以只填 token。会按 html 模板发（标题加粗、换行保留）。
- **Bark（iOS 最省事）**：iPhone 装 Bark App → 首页复制那串 key → 地址填 `https://api.day.app/<key>`（也可只填 key）。
- **WxPusher（微信服务号）**：wxpusher.zjiecode.com 扫码登录 → 建应用拿 appToken → 地址填 `https://wxpusher.zjiecode.com/api/send/message/<appToken>`，**「密钥」栏填你的 UID**（多个用英文逗号分隔）。
- 每天签到结束会推送「签到日报」，也可点控制台「测试通知」验证。日报末尾会自动附一行**积分过期预警**（如「⏳ 30 天内将过期 1853 积分」）。

定时：`config.json` 的 `scheduler.times` 支持多个时间点，例如 `["08:30","20:00"]`。

### 随机浮动（jitter）
```json
"scheduler": { "times": ["08:30"], "jitter_min": 30 }
```
实际执行时间 = 目标时间 + 当天随机 0~`jitter_min` 分钟。偏移在当天首次进入判定窗口时抽出并落盘到 `data/sched_jitter.json`，**重启不会重置**，同一天内多次轮询也不会抖动。`jitter_min: 0` 时就是准点（与旧行为一致）。控制台「每日自动签到」里有 0~240 分钟滑块。

---

## 三·五、积分资产与过期预警

控制台首页新增「积分资产」卡：**当前可用 / 累计已用**，以及「7 天内 / 30 天内将过期多少积分、最近一档哪天到期」。

- 数据源：`get-user-resource`（余额）+ `get-user-resource-free-packages`（赠送包到期）。
- **拿不到就留空，绝不显示 0**——避免「以为没积分要过期」或「以为还有积分」的误判。
- 注意：赠送包接口在 `www.codebuddy.cn` 上**只认不带 `/v2` 的** `/billing/meter/get-user-resource-free-packages`，带 `/v2` 会 404。代码里两个路径都试，谁 200 用谁。
- 缓存 300 秒，避免每次刷新都打上游。

### 积分从哪来（字段对照，2026-11 实测）

| 来源 | 接口 | 字段 |
|---|---|---|
| **当前可用积分**（卡片主数字） | `POST /v2/billing/meter/get-user-resource` | 逐包 `min(CapacityRemainPrecise, CycleCapacityRemainPrecise)` 求和 |
| 累计已用 | 同上 | `CapacityUsedPrecise` 求和 |
| 今日获得 | `POST /v2/billing/meter/checkin-activity-status` | `today_credit` |
| 连续签到天数 | 同上 | `streak_days` |

**两个必须避开的错误口径**：

1. `checkin-activity-status` 的 **`total_credits` 不是余额**，它是「活动累计发放」（签到 22 天 = 2200），与手上还剩多少无关。v1.17 曾用它做卡片主数字，结果用户实际只剩 531 却显示 2200。v1.18 起彻底弃用。
2. `CapacityRemainPrecise` **直接求和也偏高**——订阅套餐那一档（`CodeBuddy个人体验版`）`CapacityRemain=500` 但 `CycleCapacityRemain=0`，这 500 不属于可用额度。必须逐包取 `min(剩余, 本期剩余)` 再求和。

实测对照（账号 `e9334ef13785484d`，50 个包）：

| 算法 | 结果 | 是否可信 |
|---|---|---|
| `SUM CapacityRemainPrecise` | 1031.99 | ❌ 含用尽的订阅档 |
| `TotalDosage` | 1031 | ❌ 同上 |
| `SUM min(Remain, CycleRemain)` | **531.99** | ✅ 与官网一致 |
| `total_credits` | 2200 | ❌ 活动累计发放 |

**取整方式**：向下取整，不做四舍五入——531.99 显示 531（与官网「积分明细」一致），四舍五入会变 532 而让用户以为多了一分。

#### 官方汇总接口 `get-user-resource-summary`（v1.31 接入）

这是官方自己给的权威口径，比逐包累加更可信，还能拆「套餐自带」和「活动得的」：

```
POST {base}/billing/meter/get-user-resource-summary   body {}
```

**地址坑**：与赠送包接口一样，只认**不带 `/v2`** 的路径，带 `/v2` 返回 404 Route Not Found。代码里两个都试，谁 200 用谁。

响应关键字段（真实账号实测）：

```json
{"data":{
  "TotalCredits":"1888.6400009",
  "SubscriptionPackageName":"体验版",
  "IsPaidUser":false,
  "CreditGroups":[
    {"Kind":"plan_base","Name":"套餐基础积分","CycleRemainCapacity":"0","CycleTotalCapacity":"500","TotalCount":1},
    {"Kind":"plan_bonus","Name":"套餐赠送积分", ... 全 0, "TotalCount":0},
    {"Kind":"addon","Name":"购买积分", ... 全 0, "TotalCount":0},
    {"Kind":"benefit","Name":"平台奖励积分","CycleRemainCapacity":"1888.6400009","CycleTotalCapacity":"3404","TotalCount":18}
  ]}}
```

- `TotalCredits` 就是「当前可用」，与逐包 `min` 求和结果完全一致；有它时**覆盖**逐包结果。
- `CreditGroups` 的四项：`plan_base` 套餐基础 / `plan_bonus` 套餐赠送 / `addon` 购买 / `benefit` 平台奖励（签到与任务所得都在这里）。
- 卡片上「官方口径合计 · 体验版」下面的分组条就是它；`remain > 0` 的组会高亮成绿色。
- 拿不到就整块不显示，绝不编数字。

**历史坑**：
- v1.17 只找 `total_credit`（不带 s）/ `totalCredits` / `credit_total` / `sum_credit`，这四个键上游一个都不返回，`total_points` 恒为 -1（「已签到但首页永远不显示积分」）。v1.17 加了 `total_credits` 排第一。
- v1.17 又踩了 `total_credits` 的坑（2200 虚高）。v1.18 改为余额口径，并做了三层缓存作废：`HistoryStore` 升 v4 清空旧 `total_points`、`signhub_totals` 加 `__ver=2`、`wb_accounts` 加 `__ver=2`。

卡片右侧的数字口径：**显示账号当前持有积分**，当日增量（`+100`）作为下一行小字副标。缓存里没有值时 App 会在首页加载时后台补一次（`backfillWbTotal`），成功后写入缓存。

#### Trae Solo 的积分口径（v1.19）

Trae 的持有积分与 WorkBuddy 完全不同源，**签到接口给不了**：

| 用途 | 接口 | 字段 |
|---|---|---|
| **当前持有 Credits** | `POST /trae/api/v2/pay/ide_user_ent_usage` | 逐包 `max(credits_limit - usage.credits_amount, 0)` 求和 |
| 今日奖励 | `POST /trae/api/v2/ug/checkin_credits/status` | `credits` / `extra_credits` |

请求体 `{"require_usage": true, "req_source": 2}`，头里除了 `Authorization: Cloud-IDE-JWT` + `x-device-id` 还要 `Origin/Referer: https://work.trae.cn`。

`credits_limit <= 0` 的是免费订阅包与无限量包，不计入。**`usage_summary.total_amount` 是「历史获得总量」不是余额**，`total_amount - consumed_amount` 也会随套餐变化跳变，唯一稳定的是逐包 `remaining` 求和（与桌面客户端 `main.js` 的 `uJe()` 同口径）。

#### 「Trae 显示 0」的两个坑（v1.19 修）

1. **前端把 `null` 当 0**：后端读不到时 `put("total_points", JSONObject.NULL)`，JS 侧 `Number(null) === 0`、`isFinite(0) === true`、`0 >= 0 === true` → 命中「有值」分支，把「未知」渲染成了字面 `0`。现在 `svcPointsHTML` 与 `markSvcResult` 都先排除 `null` / `undefined` / 空串，再判数值。
2. **Trae 从来没查过余额**：v1.18 及以前只读签到响应里的 `credits`（当日奖励），从没调过 `ide_user_ent_usage`，所以 `totalPoints` 一直是 -1。现在 `LocalCheckin.traeHeldCredits()` 独立查余额，`LocalApi.backfillTraeTotal()` 在首页加载时后台补齐。

**原则**：拿不到就留空，绝不写 0。显示 `0` 和显示「未知」是两件完全不同的事，前者会让用户以为积分被清空了。

#### Trae token 8 小时过期怎么办（v1.21）

Trae 有两条凭据，作用完全不同：

| 凭据 | 有效期 | 用途 |
|---|---|---|
| `Cloud-IDE-Token`（JWT） | **约 8 小时** | 签到请求的 `Authorization: Cloud-IDE-JWT <token>` |
| `X-Cloudide-Session`（Cookie） | **约 14 天** | 用来把过期的 token 换成新的 |

所以正确做法是**存 session，不存 token**。换新接口：

```
POST https://api.trae.cn/cloudide/api/v3/common/GetUserToken
Cookie: X-Cloudide-Session=<session>
Referer: https://www.trae.cn/
Origin: https://www.trae.cn
→ {"Result":{"Token":"eyJ..."}}
```

签到流程变成三步：

1. **签到前先判**：解 JWT 的 `exp`，如果已过期或 60 秒内到期，先用 session 换一个新 token（省掉一次必然 401 的请求）。
2. **被拒时再换**：status / claim 返回 401、403，且有 session，就换新后重试一次。仍失败才报「session 可能已过期，请重新登录」。
3. **换到的新 token 立刻落盘**：写回 `traeToken`，否则下一轮定时任务又拿旧的 8 小时 JWT 去撞 401。

App 里「登录 Trae 账号」现在会同时抓 session 与 token；设置页两个输入框，session 在上、token 在下，「检查凭据」会显示 token 还剩几小时。

#### Trae 业务码对照（v1.31，参考 L0NE-6/Trae-AutoCheckin）

之前把 `9004` / `9095` 当成失败上报，这是真实 bug —— 它们其实是「今天已经签过了」。

| 业务码 | 真实含义 | 处理 |
|---|---|---|
| `0` / 无 `code` | 成功 | `success` |
| **`9004` / `9095`** | **今日已签到**（两种说法） | **`already`，不是失败** |
| `9074` | 设备号不对（不是「参与用户太多」） | `fail`，提示换真实设备号 |
| `20405` | 续期需要 ECDSA 设备签名（2026-09 之后加的） | `fail`，走 session 换 token 绕开 |
| `20101` / `10101` / `20403` | refreshToken 失效 / 与客户端不匹配 / token 与设备不匹配 | `fail`，需重新登录 |

- **`x-device-id` 必须是这台客户端在服务端注册过的那串十进制数字**（15/16 位都有），脚本自己生成的号会被直接拒成 `9074`。
- 请求体必须带 `{"req_source":1}` 和设备头，空请求体也会被拒成 `9074`。
- 一个真实设备号一天只能签一个账号。

### 「连续 N 天」的口径

- 优先用上游返回的 `streak_days`（真实连登天数，横跨多设备累加）。
- 没有才回落到本地记录统计，且**只统计真正被执行过的服务**：`idle`（例如浏览器模式里没找到签到按钮）既不算漏签，也不进分母。早期版本把 `idle` 当未完成，导致顶部恒显示「0 天连续」。

---

## 三·六、WorkBuddy 成长中心

控制台首页「WorkBuddy 成长中心」卡：查看任务 / 一键完成 / 领取奖励 / 每日任务全跑。

| 能力 | 说明 |
|---|---|
| 查看任务 | `GET /v2/activity/growth/tasks`，显示每个任务的进度、状态、可领状态 |
| 一键完成 | 先 accept 全部（**带前置依赖自动补救**），再按 task_code 下发对应的事件序列（`/v2/report` 桌面色），最后领奖 |
| 领取奖励 | 只领已达标的，领完**回读任务状态确认**，不发事件 |
| 每日任务全跑 | 签到（先补一条对话预热）→ 连登兑换 → 补登 → 抽奖 → 开盲盒 |

- `NOT_AUTO` 里的两个任务（关注公众号、公益捐赠）需要真实人工动作，程序会跳过并说明。
- `black_cat` 只在 23:00–08:00 可完成，其他时间会返回提示。
- 「每日任务全跑」里**签到前会先补一条极简对话**——WorkBuddy 要求「当天完成一次对话」才算签到，不补会因「当天无对话」被服务端拒绝（这是签到空白 / 连登断掉的根因）。

#### 前置依赖自动补救（v1.31）

`tasks/accept` 有时会返回 `prerequisite not met: <task_code>` —— 意思是「先把前置任务做了才让你接这个」。以前这种任务直接被跳过，永远做不了。现在：

1. accept 失败时用正则 `prerequisite\s+not\s+met[:\s]+([A-Za-z0-9_\-]+)` 从响应里抠出前置任务码（实测一次能匹配多个：`['chat_5','template_5']`）。
2. 先 `run_task` 跑完前置，再重试 accept，最多两层递归。
3. 前置补救的记录会作为一组 `skipped` 条目塞进结果列表，界面上能看到「为什么跳过了这个任务」。

#### 领取回读校验（v1.31）

`claim` 返回成功**不代表真到账**（实测出现过「已成功领取」但任务状态仍是 `completed` 的情况）。现在每条领取都多一步回读：

- 逐条 `claim_task` → 再 `list_tasks` 找同名任务，看 `accept_status` 是否变成 `claimed`。
- 判定：`ok = (应答成功 AND 回读确认) OR (应答成功 AND credit > 0)`，结果里带 `confirmed` 与「｜回读说明」。
- 回读仍失败时提示「回读仍是未领取」，而不是假装成功。

---

## 三·七、豆包「做任务领额度」（v1.27 · **已于 2026-11-20 下线**）

> **本节已成为历史存档。** 2026-11-20 实测确认：`landing/checkin` 写接口对任何途径都固定返回 `710010702`——
> 裸 HTTP 带真实 Cookie、页面内 `fetch`（浏览器自动带 msToken + a_bogus 全套签名）、**官方客户端里手动点「立即签到」**
> 三种全部失败。`daily_signin` 因此永远停在 `task_status=1` 且 `task_id` 为空，连带锁住 3 个 `points_milestone_*`
> 与 4 个 `pc_task_*`。这是平台侧风控封禁，客户端没有任何修复空间。
>
> 因此「豆包做任务领额度」卡片与「查看任务 / 一键领取」按钮已从前后端和 Android App 中移除；
> V2.25 清理时连 `backend/signbot/doubao_tasks.py`、`DoubaoWebActivity.kt`、`DoubaoTaskStore.kt`
> 与相关 API 路由一起删除了，原代码已不在仓库里。恢复方式见 [docs/DOUBAO.md](docs/DOUBAO.md) 顶部说明。

控制台首页「豆包做任务领额度」卡：查看任务 / 一键领取，外加一行「累计获得额度」。

### 接口（2026-11 实测）

| 用途 | 接口 | body |
|---|---|---|
| 任务列表 | `POST /alice/ug/campaign/v3/detail/` | `{"campaign_group_code":"pc_incentive_task_mvp_v3"}` |
| 奖励领取 | `POST /alice/ug/campaign/item/reward/claim/` | `{"task_id":"...","campaign_group_code":"pc_incentive_task_mvp_v3","AgwCommonParam":{}}` |
| 累计发放 | `POST /alice/ug/campaign/quota/records/` | `{"page_size":100}` |
| 签到（已废） | `POST /alice/ug/campaign/landing/checkin` | `{"scene":"pc_incentive_task_mvp_v3"}` |

`task_status`：0 未解锁 / 1 进行中 / 3 可领取。
**只有 `task_status=3` 的任务带 `task_id`**，也只有它调得动领取接口。

### 三条硬结论

1. **签到写接口已被服务端风控**（2026-11 起）。三种途径全部返回 `710010702 系统错误`：裸 HTTP 带真实 Cookie、页面内 `fetch`（浏览器自动带 msToken + a_bogus 全套签名）、**用户在官方客户端里手动点「立即签到」**。所以「补 a_bogus 签名」这条路是死路，等官方恢复。签到失败时界面会明确写「服务端拒绝，非网络问题」。
2. **领取接口必须带 `task_id`**。远端参考实现里那两处写法（用 `campaign_code` / 用 `campaign_item_code`）都不对，前者返回 `710010202`，后者返回 `biz_error.code=3`「领取结果确认中」。
3. **`quota/records` 求和是「累计获得」，不是余额**。官方只公开发放流水，没有任何消耗记录；扫过 2095 个前端 JS 包 + 63 个 commerce 接口确认，前端里连 consume/deduct/remain 类路径都是 0 个。所以卡片上标的是「累计获得 N 额度（不是余额）」。

### 取数通道（两种环境各一条）

| 环境 | 通道 | 原因 |
|---|---|---|
| 电脑端后端 | CDP 连豆包客户端 `127.0.0.1:9222` | 客户端运行时 Cookie 库被独占锁定，`read_cookies()` 返回空；CDP 在页面内 `fetch` 由浏览器自动补齐 Cookie 与签名 |
| 手机 App | 内置 `DoubaoWebActivity`（MODE_TASKS / MODE_CLAIM） | APK 里没有 Python，只能用 WebView 的页面上下文发请求；结果落进 `DoubaoTaskStore`，首页读缓存 |

启动豆包客户端时带 `--remote-debugging-port=9222` 才有 CDP 通道；没有则后端会退回 HTTP+Cookie（要求豆包已完全退出）。

实测样本（2026-11-20）：`select_work_fields +100`、`pc_task_generate_document_v2 +100` 两个可领取；`daily_signin +200`、`pc_task_generate_excel_v2 / ppt_v2 / create_schedule_v2 各 +100`、`pc_task_complete_work_task_daily +50` 进行中；`c2b_guide` 未解锁。累计获得 3300。

**领取的已知边界**：接口回 `biz_error.code=4`「已成功领取，请稍后查看」，但复查 `task_status` 仍为 3、`quota/records` 条数与金额都不变。这是服务端的幂等占位应答，实际发放要等后台结算（或该账号未真正满足完成条件）。界面上如实标注了这一点，不假装成功。

#### 领取后回读确认（v1.31）

「已成功领取」这句应答不可信。现在领取完会**重拉 `campaign/list/` 回读每个任务的 `task_status`**：

| 回读结果 | 判定 | 界面显示 |
|---|---|---|
| `task_status` 变成 `0` 或 `4` | 真到账 | `✓ 已到账` |
| 仍是 `3` | 服务端受理但没结算 | `… 服务端受理但未结算` |
| 其他 | 未知 | `…` |

判定逻辑：`ok` 初值 `false`、`pending` 初值 `true`，**只有回读确认才把 `ok` 置 true**。所以那一行打勾就是真到账，打省略号就是还没到账 —— 不再拿「服务端说了成功」当真。

#### 手机端没有独立签到页（v1.28 实测）

用三种设备头（`device_platform=web` / `android&os_version=14` / `ios&os_version=17.0`）分别打 `landing/get` 与 `campaign/list`：

| 设备头 | `landing/get` | `campaign/list` |
|---|---|---|
| 桌面客户端 | `code=0`，`cards=0 tasks=0` | `code=0`，**10 个任务** |
| Android | `code=0`，`cards=0 tasks=0` | `code=0`，**10 个任务** |
| iOS | `code=0`，`cards=0 tasks=0` | `code=0`，**10 个任务** |

三平台同一活动、同一批任务，`landing` 对任何平台都返回空卡片。又扫了 10 个候选活动组名（`android_incentive_task` / `app_incentive_task_mvp` / `mobile_task_center` / `app_daily_signin` …）→ 全部 `code=2`（活动不存在）。**所以手机端没有单独的签到入口，App 里的「每日签到」背后就是同一个 `landing/checkin`，同样受风控。**

#### `campaign/list` 比 `v3/detail` 多 3 个里程碑（v1.28）

这是之前漏掉的额度大头。`v3/detail/` 只返回 8 个任务，`campaign/list/` 返回 10 个，多出来的是：

| campaign_code | 额度 | 状态 |
|---|---|---|
| `points_milestone_2000`（累计获得 2000 点） | **+500** | 可领取 |
| `points_milestone_500`（累计获得 500 点） | **+200** | 可领取 |
| `points_milestone_5000`（累计获得 5000 点） | **+1300** | 进行中 |

可领取数从 2 个（+100/+100）变成 **4 个、合计 900 额度**。所以现在 `list_tasks()` 以 `campaign/list/` 为主数据源，再额外调一次 `v3/detail/` 补 `reward_summaries`（里程碑项里不带额度字段，只能从 `subtitle` 文案用正则 `额外领\s*(\d+)\s*点额度` 抠，或用 `每完成\s*1\s*个任务得\s*(\d+)\s*点` 兜底）。

#### `page_size` 上限是 100（v1.28 修）

`quota/records` 的 `page_size`：20/50/99/100 都正常返回 15 条；**101 及以上静默返回空列表**（不报错！），界面表现成「累计额度 = 0」。现在 `quota_total()` 开头就夹取 `ps = min(max(ps, 1), 100)`。

---

## 从源码构建安卓 App（正式签名包）

日常调试直接 `gradlew assembleDebug` 出 debug 包即可（无需任何签名配置）。要出**正式签名包**（assembleRelease），需自备签名身份：

1. `android/keystore.properties` 与 `android/keystore/*.jks` **不在仓库里**（.gitignore 已排除，那是作者的发布身份）。模板见 `android/keystore.properties.example`：
   复制一份、填入你自己生成的 keystore（`keytool -genkeypair -v -keystore android/keystore/my-release.jks -alias myalias -keyalg RSA -keysize 4096 -validity 10950`），改名 `keystore.properties` 放到 `android/` 根目录。
2. 有 `keystore.properties` 时，`gradlew assembleRelease` 自动挂上你的签名并开 R8 混淆；没有时 release 变体仍是未签名包，装不上。
3. R8 保留规则在 `android/app/proguard-rules.pro`：WebView JS 桥（`@JavascriptInterface`）、WorkManager Worker、广播接收器等反射入口已硬编码保留。给代码新增 JS 桥方法或 Worker 时记得补 keep 规则，否则 release 包运行时会 `undefined is not a function` 或 Worker 不跑（debug 包完全正常这是最常见的「只有 release 崩」类 issue 来源）。
4. **普通用户请直接从 Releases 页下载 APK，不要自己 assembleDebug**debug 包用公开的调试证书签名（debuggable），任何应用都能同签名覆盖安装，且部分 ROM 会拦截。

---

## 四、安卓 App（控制台 + 提醒 + 无障碍兜底）

`android/` 是标准 Gradle 工程，用 **Android Studio** 打开即可编译安装（首次会自动下载依赖）。命令行：
```bash
cd signin-hub/android
./gradlew assembleDebug     # Windows: gradlew.bat assembleDebug
```
产物：`android/app/build/outputs/apk/debug/app-debug.apk`

> `local.properties` 里的 `sdk.dir` 改成你本机 Android SDK 路径；用 Android Studio 打开会自动生成。

App 功能：
- **控制台**：查看三个服务今日签到状态 / 积分，一键全部签到，单独补签任一服务。
- **记录**：历史签到日志。
- **设置**：
  - 填电脑端地址（如 `http://192.168.1.100:8787`）与 Token，点「保存并连接」。**手机需与电脑在同一局域网**（电脑防火墙放行 8787 端口）。
  - 每日提醒（到点本地通知「该去签到了」）。
  - **无障碍自动点击**：点「打开无障碍设置」开启本 App 服务后，可在官方 App 页面内自动点「签到」。

### WorkBuddy 登录：主账号 vs 多账号

App 首页 WorkBuddy 卡片上有两个按钮，走的是**同一条官方 OAuth 链路**（`/v2/plugin/auth/state` → 轮询 `/v2/plugin/auth/token`），只是结果写到不同的位置：

| 按钮 | 颜色 | 结果写到 | 用途 |
|---|---|---|---|
| 登录主账号 | 绿 | 设置里的 `wbToken` / `wbRefreshToken` / `wbUid` | 积分资产、成长中心、每日签到默认作用对象 |
| 多账号管理 | 蓝 | `wb_accounts` 本地账号列表 | 多号同时签到、增删账号 |

流程都是：点按钮 → App 申请 state → 跳到浏览器用手机号登录 → App 每 2 秒轮询 → 拿到 accessToken 自动保存并关页。
**未授权时服务端返回 `{"code":11217,"msg":"login ing..."}`，属于正常等待，不是报错。**

> 不要用「网页登录后从 localStorage 抓 accessToken」那条老路：手机号登录后 token 通常落在 HttpOnly Cookie 或 IndexedDB，`document.cookie` 和 `localStorage` 都读不到，必然抓空。若接口临时异常，点「粘贴」可手动填 accessToken 兜底。

### 无障碍兜底注意
`AutoClickService.kt` 里的 `TARGET_PACKAGES` 是三个 App 的包名，请按你手机上实际的包名修改（用 `adb shell pm list packages | findstr 关键词` 查）。它只在目标包内、找到「签到/打卡/领取」等文字按钮时才点击。

---

## 五、接口速览（手机 App / 第三方调用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ping` | 探活 |
| GET | `/api/status` | 今日各服务状态 + 统计 + 积分缓存 + 通知摘要 |
| GET | `/api/logs?provider=&limit=` | 签到记录 |
| POST | `/api/checkin/all` | 全部签到 |
| POST | `/api/checkin/{workbuddy\|traesolo}` | 单个签到（`doubao` 已下线，调用返回 `retired`） |
| GET/POST | `/api/config` | 读取/保存配置 |
| POST | `/api/test_notify` | 测试通知（返回实际识别到的 `kind`） |
| GET | `/api/wb_assets[?refresh=1]` | 积分余额 + 赠送包过期汇总 |
| GET | `/api/growth` | 成长中心任务总览 |
| POST | `/api/growth/room` | 一键完成成长任务 |
| POST | `/api/growth/claim` | 领取已达标奖励 |
| GET | `/api/growth/daily` | 连登/补登/抽奖状态 |
| POST | `/api/growth/daily/run` | 每日任务全跑 |
| ~~GET~~ | ~~`/api/doubao_tasks`~~ | **已下线**：V2.25 清理时路由与实现一起删除（现为 404） |
| ~~GET~~ | ~~`/api/doubao_quota`~~ | **已下线**：同上 |
| ~~POST~~ | ~~`/api/doubao_tasks/claim`~~ | **已下线**：同上；`/api/doubao_probe`、`/api/doubao_login` 同 |
| GET | `/api/heat` | 官网连登热力图（26 周） |
| POST | `/api/refresh_totals` | 强制重查四平台当前持有积分（只接受 POST，GET 回 405） |

如设置了 `server.api_token`，请求需带请求头 `X-API-Token: <token>`（或 URL 加 `?token=`）。

### 接口的安全约束（V2.26 起）

- **Host 白名单**：`Host` 头不是 `127.0.0.1` / `localhost` / `::1` / 本机局域网 IP 的请求一律 403（挡 DNS rebinding）。
- **写操作来源校验**：`POST` 带 `Origin`/`Referer` 且不属于本机控制台 → 403（挡跨站 CSRF）。不带这两个头的 curl / 脚本 / 手机原生请求照常放行。
- **CORS 只回显白名单**：不再发 `Access-Control-Allow-Origin: *`，只有本机来源才回显并附 `Vary: Origin`。
- **`/api/local_creds/*`**：只允许本机来源 + 本机 Origin，跨站请求拿不到任何凭据。
- **请求体上限 1MB**：超过直接 413，不再按 `Content-Length` 分配内存。

---

## 六、常见问题
- **手机连不上电脑**：确认同一 WiFi；电脑防火墙放行 8787；`server.host` 为 `0.0.0.0`；地址用电脑**局域网 IP**（`ipconfig` 查）。
- **显示「演示(dry-run)」**：说明该服务 http 段里还是占位符，填真实 URL/Cookie 后即真实请求。
- **签到失败**：看控制台该条「信息」与 `/api/logs` 里的 `detail`（原始返回），多半是 Cookie 过期或接口字段变了，重新抓包更新即可。
- **Cookie 多久过期**：各服务不同，失效时重新抓一次覆盖 config.json 即可；浏览器模式则删除 `*_state.json` 重新登录。




##  开源声明 / License

本项目采用 **CC BY-NC-SA 4.0（署名-非商用-相同方式共享 4.0 国际）** 许可证开源。

-  允许个人学习、研究、教育用途自由使用
-  允许修改和二创，但必须：
  - 署名原作者并附本仓库链接
  - 衍生作品以**相同的 CC BY-NC-SA 4.0** 许可证开源
-  **禁止任何形式的商业用途**：包括但不限于出售、付费分发、集成进商业产品、企业内部商用、以此收费提供服务等
- 商业授权请联系作者（GitHub Issue 或仓库主页联系方式）
- QQ邮箱：1740558827@qq.com
- OutLook邮箱：ayanguser23@outlook.com
- Google邮箱:ayanguser23@gmail.com
- GitHub:Ayanguser
- 未经授权的商用行为将依法追究著作权侵权责任

完整许可证文本见根目录 [`LICENSE`](LICENSE)，第三方素材说明见 [`NOTICE.txt`](NOTICE.txt)。