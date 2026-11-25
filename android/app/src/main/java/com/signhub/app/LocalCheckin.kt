package com.signhub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 本地签到：不依赖电脑，手机直接打官方接口。
 *
 * WorkBuddy（腾讯 CodeBuddy）
 *   POST {billing}/v2/billing/meter/checkin-activity-status   body {}
 *   POST {billing}/v2/billing/meter/daily-checkin             body {}
 *   头：Authorization: Bearer <token> + X-User-Id / X-Enterprise-Id / X-Tenant-Id / X-Domain
 *   UA：CLI/2.63.2 CodeBuddy/2.63.2
 *   信封 {code,msg,data}，code==0 成功
 *
 * Trae Solo
 *   POST {host}/trae/api/v2/ug/checkin_credits/status         body {}
 *   POST {host}/trae/api/v2/ug/checkin_credits/claim          body {}
 *   头：Authorization: Cloud-IDE-JWT <token> + X-User-Region + x-device-id（16 位数字）
 *   code==0 成功；9004/9095 = 今日已签到；9074 = 设备号风控
 *
 */
data class CheckinResult(
    val provider: String,
    val status: String,   // success | already | fail
    val message: String,
    val points: String = "",
    val totalPoints: Int = -1,   // 账号累计积分（读不到 = -1）
    val streakDays: Int = -1,    // 上游返回的连续签到天数（读不到 = -1）
    val usedPoints: Int = -1,    // 账号累计已用（读不到 = -1），资产卡翻面用
    /**
     * 这次失败是不是**登录态问题**（BUGS-v2.48 P2-2）。
     *
     * 早先 `HwCheckin.run` 靠 `message.contains("登录态已失效") || contains("未登录")`
     * 判断要不要把「未登录」结论记下来，但 `hwApi` 在不同失败形态下给的文案不一样，
     * 其中两条属于登录态问题却不含这两个子串：
     * - 空响应 → 「华为接口返回空响应（HTTP $code）：多半是登录态不完整」（没有「已失效」）
     * - 非 JSON → 「…头组不完整或登录态失效」（是「失效」不是「已失效」）
     * 于是 `HwVerified` 不会被置 false，`hwUsable` 继续为真，下一轮照旧派 job、照旧 401，
     * 而华为会话本就 30~60 分钟过期，这会加速耗损。
     *
     * 加这个字段而不是继续匹配文案：文案是给人看的，随时可能改措辞；
     * 判据必须是结构化的。**不进 JSON 序列化**（`LocalApi.toJson` 不读它），
     * 所以对前后端契约零影响。
     */
    val authFailed: Boolean = false,
)

object LocalCheckin {

    private const val WB_CN_CHAT = "https://copilot.tencent.com"
    private const val WB_CN_BILLING = "https://www.codebuddy.cn"
    private const val WB_GLOBAL = "https://www.workbuddy.ai"
    private const val WB_UA = "CLI/2.63.2 CodeBuddy/2.63.2"

    private const val TRAE_CN = "https://api.trae.cn"
    private const val TRAE_INTL = "https://api.trae.ai"
    private const val TRAE_USAGE_PATH = "/trae/api/v2/pay/ide_user_ent_usage"
    private const val TRAE_TOKEN_PATH = "/cloudide/api/v3/common/GetUserToken"

    /** 今日已签到的业务码。Trae 用 9004 / 9095 两种说法，都不是失败。 */
    private val TRAE_ALREADY_CODES = setOf(9004, 9095)

    /**
     * Trae 的「凭据失效」业务码。
     *
     * **1001 必须在这里**：实测 `/trae/api/v2/ug/checkin_credits/status` 在 token
     * 过期时返回的是 **HTTP 200 + body `{"code":1001,"message":"We're sorry, but we
     * are not able to authenticate you..."}`**，不是 401/403。原来的代码只判
     * `stCode == 401 || stCode == 403` 才去用 session 换新，于是 1001 直接落到
     * 「上游失败 code=1001」——用户看到的是莫名其妙的英文报错，而真实语义是
     * 「登录已过期，请重新登录」（用户 m08833 / m10393 报的就是这个）。
     */
    private val TRAE_AUTH_CODES = setOf(1001, 20405, 20101, 10101, 20403)

    /**
     * Trae 的「设备号不匹配」业务码（v2.6 更正语义）。
     *
     * 上游文案「当前参与用户太多，请稍后再试」是**误导性文案**。实测与同类开源项目
     * （L0NE-6/Trae-AutoCheckin，2026-10-09 更正）一致指向：**9074 = 这个 x-device-id
     * 在该账号下没有注册记录**，与出口 IP、请求头、请求频率无关 —— 等几分钟再试永远等不到。
     *
     * 两条硬规则：
     *   ① 同一个设备号只能给一个账号用（第二个账号拿它签 = 用别人的注册记录 → 9074）；
     *   ② 设备号必须永久稳定（每次重新生成 = 每天换新设备 → 永远未注册 → 9074）。
     * 所以设备号现在由 [DeviceId.derive] 按账号凭据确定性派生，一次算好终身不变，
     * 并且**任何路径都不再自动换号**（v2.4/v2.5 的自动换号造成了死循环）。
     */
    private const val TRAE_DEVICE_CODE = 9074

    /**
     * Trae 签到接口的请求体。
     *
     * 不能发 `{}`：实测（同类开源项目一致）空请求体会被拒成 9074，
     * 桌面端发的是 `{"req_source":1}`。
     */
    private const val TRAE_STATUS_BODY = "{\"req_source\":1}"

    /**
     * Solo 版的请求体（v2.18）。
     *
     * 桌面端 `mb()` 里是 `const u = ii(this.X) ? 2 : 1`，`ii()` 判的是
     * `packageType ∈ {SOLO_CN, SOLO_I18N, SOLO_CN_ENTERPRISE}`。本机客户端日志里
     * `package_type` 就是 `"SOLO"` —— 也就是说这个应用对应的产品形态应当发 **2**。
     * v2.7 当时发 1 也能过，所以不再二选一：**先发 1，遇 9074 再发 2 重试一次**，
     * 两种形态都能覆盖，也不会因为猜错把本来能签的账号签坏。
     */
    private const val TRAE_STATUS_BODY_SOLO = "{\"req_source\":2}"

    /**
     * 桌面端 Trae 的版本号，用于 `x-app-version` 头。
     *
     * 来源：本机 `E:\Trae CN\resources\app\out\main.js` 里的 `IdeVersion = '1.107.1'`。
     * 同类实测项目（L0NE-6/Trae-AutoCheckin）的 `ug_headers()` 也是 `Trae/1.107.1`。
     */
    private const val TRAE_IDE_VERSION = "1.107.1"

    /**
     * 设备头的三个取值来源（v2.18，来自本机客户端日志的 `customTeaHeaderParams`）。
     *
     * v2.7 当时把这三个写死成 `Windows` / `10.0.19045` / `trae`，都是**猜的**：
     *   · `x-device-type` 官方取 `os_name`，真机值是 `windows`（全小写）；
     *   · `x-os-version`  官方取 `os_version`（人类可读串），`10.0.19045` 其实是 `os_release`；
     *   · `x-device-brand` 官方取 `device_model`（机型码），根本不是产品名。
     * 三处取值与真机全不一致，风控完全可以据此判「伪造客户端」→ 9074。
     */
    private const val TRAE_OS_NAME = "windows"
    private const val TRAE_OS_VERSION = "Windows 10 Pro for Workstations"
    private const val TRAE_DEVICE_MODEL = "82RF"

    /**
     * 桌面端 User-Agent（v2.19 补回，v2.18 删掉它是本轮回归的元凶）。
     *
     * **这是本轮最关键的一条**：v2.18 为了「只发官方那 7 个头」把 UA 整个删了，结果
     * OkHttp 自动补上 `okhttp/4.x` —— 这是比缺任何设备头都更刺眼的「非官方客户端」特征。
     * 官方错误码枚举里 9074 = `ErrWorkRiskControlBanned`（风控封禁），完全对得上。
     *
     * 取值用 `Trae/1.107.1` 而不是日志里 TTNet 栈的 `TraeClient/TTNet`：前者是 v2.7
     * 唯一被用户实测确认能签成功的版本用的值（m12822「trea 终于可以多账号登录了」），
     * 后者只出现在内部 TTNet 日志里、没被验证过。不拿未验证的值去替换已验证的值。
     */
    private const val TRAE_UA = "Trae/1.107.1"

    /**
     * 9074 的统一文案（status 与 claim 两处共用，避免两段文案漂移）。
     *
     * **v2.19 语义纠正（决定性）**：9074 不是「设备号未注册」，而是
     * `ErrWorkRiskControlBanned` —— 官方错误码枚举（`www.trae.cn` 的 JS chunk
     * `32486.*.js`）里逐条写着：
     *   9073 ErrWorkDeviceAlreadyGranted
     *   9074 ErrWorkRiskControlBanned      ← 风控封禁
     *   9095 ErrCheckinCreditsDeviceAlreadyUsed  ← 设备已被用过，**这才是设备维度的码**
     * 也就是说「设备未注册 / 一个号一天只能签一个账号」对应的是 9095，跟 9074 无关。
     * v2.6~v2.18 一直按「设备未注册」解释，让用户去填真机号，方向是错的。
     *
     * v2.19 的两处真实修复：
     *   ① 补回 UA（v2.18 删掉后 OkHttp 发 `okhttp/4.x`，直接被判爬虫 → 风控封禁）；
     *   ② 设备号一次写定不再重算（旧实现每次启动按 `session.ifBlank { token }` 重算，
     *      凭据一换号就换，服务端看到「同一账号天天换设备」）。
     */
    private fun traeDeviceFailMsg(dev: String): String =
        "被 Trae 风控拦了（code=9074 = ErrWorkRiskControlBanned，不是「设备没注册」——" +
            "那是 9095）。上游文案「当前参与用户太多」是套话，等几分钟没用。" +
            "当前设备号 $dev。" +
            "最常见的原因是使用顺序：账号在官网刚注册、还没正常用过，就先被本软件拿来签到。" +
            "建议：① 先到 trae.cn 官网正常登录用一会儿（写几行代码、开一次会话都算），" +
            "隔几小时（最好隔一天）再回来签；" +
            "② 已经报 9074 的，等 12~24 小时再试；" +
            "③ 期间不要改设备号、也不要反复点测试（每次调用都会被记一次）。"

    /**
     * Trae 请求头（桌面端同款 5 个设备头）。
     *
     * @param desktopUa true = 用桌面客户端 UA（`Trae/1.107.1`），签到/领取接口用这个；
     *        false = 用浏览器 UA，**余额查询必须用这个**：远端实测
     *        `ide_user_ent_usage` 用 `TraeCheckin/1.0` 这类客户端 UA 会拿不到完整
     *        资源包列表，换浏览器 UA 后与网页「用量管理」一致。
     *        两条路径的设备头完全一致，只有 UA 不同。
     */
    private fun traeHeaders(
        tok: String,
        dev: String,
        global: Boolean,
        desktopUa: Boolean = true,
    ): Map<String, String> {
        // 桌面端签到请求的头集合。这里有两次教训，别再来第三次：
        //
        // 【v2.7 实测成功】12 个头（Content-Type / Accept / Authorization / X-User-Region /
        //   5 个设备头 / User-Agent / Origin / Referer）。用户 m12822 确认
        //   「trea 终于可以多账号登录了」—— 这是**唯一被实测确认能签成**的组合。
        //
        // 【v2.18 实测失败】为了「只发官方 main.js 逆向出的 7 个头」把 Accept /
        //   X-User-Region / Origin / Referer / **User-Agent** 全删了。删 UA 是致命的：
        //   OkHttp 会自动补 `okhttp/4.x`，这是比缺任何设备头都更刺眼的爬虫特征。
        //   结果 9074 照旧 —— 而官方错误码枚举里 9074 就是
        //   `ErrWorkRiskControlBanned`（风控封禁），完全对得上「被判成非官方客户端」。
        //
        // 所以 v2.19 回到 v2.7 的 12 头，只保留 v2.18 里**唯一正确**的那部分改进：
        // 4 个设备头的取值（main.js 的 commonParams，h = getSystemInformation()）：
        //   x-device-brand = device_model        （真机 "82RF"，Lenovo 机型码；不是 "trae"）
        //   x-device-type  = os_name             （真机 "windows"，小写；不是 "Windows"）
        //   x-os-version   = os_version          （"Windows 10 Pro for Workstations"；
        //                                          "10.0.19045" 是 os_release，字段选错了）
        //   x-app-version  = appVersion          （"1.107.1"，这条本来就对）
        if (desktopUa) {
            return mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Cloud-IDE-JWT $tok",
                "X-User-Region" to (if (global) "global" else "cn"),
                "User-Agent" to TRAE_UA,
                "Origin" to "https://work.trae.cn",
                "Referer" to "https://work.trae.cn/",
                "x-device-id" to dev,
                "x-device-type" to TRAE_OS_NAME,
                "x-os-version" to TRAE_OS_VERSION,
                "x-app-version" to TRAE_IDE_VERSION,
                "x-device-brand" to TRAE_DEVICE_MODEL,
            )
        }
        // 余额查询（ide_user_ent_usage）走网页口径：浏览器 UA 才拿得到完整资源包列表，
        // 这时带上 Origin/Referer/X-User-Region 是**合理**的（网页请求本来就有）。
        return mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Cloud-IDE-JWT $tok",
            "X-User-Region" to (if (global) "global" else "cn"),
            "x-device-id" to dev,
            "x-device-type" to TRAE_OS_NAME,
            "x-os-version" to TRAE_OS_VERSION,
            "x-app-version" to TRAE_IDE_VERSION,
            "x-device-brand" to TRAE_DEVICE_MODEL,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Origin" to "https://work.trae.cn",
            "Referer" to "https://work.trae.cn/",
        )
    }

    // MiniMax Code 桌面端签名常量（从 app.asar 逆出，见 signbot/minimax.py）
    private const val MM_APP_VERSION_CODE = "22201"
    private const val MM_DESKTOP_VERSION = "3.1.16"
    private const val MM_SIGNATURE_SALT = "I*7Cf%WZ#S&%1RlZJ&C2"
    private const val MM_YY_SUFFIX = "ooui"
    private const val MM_UA =
        "MiniMaxAgent Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) MiniMax/3.1.16 Chrome/148.0.7778.280 Safari/537.36"

    /**
     * M4（BUGS-v2.61）：证书固定。原来全仓 0 处 CertificatePinner，而
     * `clientFor(proxy)` 允许用户填任意 HTTP 代理（`provider.*.proxy`）——
     * 一个被改过的代理就能用自签证书 MITM 掉所有签到请求，把
     * `Cloud-IDE-JWT` / `drt-` / `token` 全部拿走。
     *
     * 只对**已知上游域名**固定；其它 host（含用户自填的自定义 provider、
     * webhook）不受影响，不会误伤。每个域名同时 pin 叶子证书和中间 CA，
     * OkHttp 只要有一条匹配就放行 —— 叶子到期换发时中间 CA 那条仍然有效。
     *
     * 维护：上游换 CA（不是换叶子）时这里必须更新，否则对应 provider 会
     * 报 `SSLPeerUnverifiedException`。取值脚本见 docs/PINS.md。
     */
    private val PINNER: CertificatePinner = CertificatePinner.Builder()
        // Trae（api/work/www.trae.cn 同一张 *.trae.cn 证书）
        .add(
            "**.trae.cn",
            "sha256/TPHUxm8Mczeiq1ovvtYFvBRjjgFMV2PA+IG8om10L18=",
            "sha256/IsBGRWLpt/3tNcYciM0PhSfcIZE0JMAm3QrTFcZRCL4=",
            "sha256/E3tYcwo9CiqATmKtpMLW5V+pzIq+ZoDmpXSiJlXGmTo=",
            "sha256/SDG5orEv8iX6MNenIAxa8nQFNpROB/6+llsZdXHZNqs="
        )
        // Trae 国际版
        .add(
            "**.trae.ai",
            "sha256/c+av7z7Px+fweK8UTTbrleY/yaPHF6g3qUK8Hanbe3Q=",
            "sha256/SDG5orEv8iX6MNenIAxa8nQFNpROB/6+llsZdXHZNqs="
        )
        // Qoder CN
        .add(
            "**.qoder.com.cn",
            "sha256/1C2rDiaZqjPg1wnVUB6vrzaiVt2hcjLG+uRItuGggFw=",
            "sha256/Sc7wL4FfOw8ued5w3JOSzu5MzB471PfqdyN4hnMYbX4=",
            "sha256/cGuxAXyFXFkWm61cF4HPWX8S0srS9j0aSqN0k4AP+4A="
        )
        // Qoder 国际版
        .add(
            "**.qoder.sh",
            "sha256/0NvFGU2W1cU8b3M5TXpZ+NnbiBIneileT/7g9IQp1qA=",
            "sha256/SDG5orEv8iX6MNenIAxa8nQFNpROB/6+llsZdXHZNqs="
        )
        // MiniMax Code
        .add(
            "**.minimax.cn",
            "sha256/jPKgT6AspJvQsQqn/dNll6Bi9UHH75O+VnHXVN5HftQ=",
            "sha256/3Vk1Rs5ICzrrvS5Tdw6FhJy0d53xmGah2+kDRvgHkPU=",
            "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="
        )
        // WorkBuddy / CodeBuddy（腾讯）
        .add(
            "**.codebuddy.cn",
            "sha256/nwczc/O0exRKQ/u++21QOmSMfs9qV7OgIpJRdRUq+aE=",
            "sha256/731AZkcNdxw9L154ZN3auYetdnXwhN81UrZZTmrgK8M=",
            "sha256/ape1HIIZ6T5d7GS61YBs3rD4NVvkfnVwELcCRW4Bqv0="
        )
        .add(
            "**.workbuddy.ai",
            "sha256/PZz+QZ3xleKsVZbvfzHGfmCPYtYyZlnbMmESC0ThUrQ=",
            "sha256/731AZkcNdxw9L154ZN3auYetdnXwhN81UrZZTmrgK8M=",
            "sha256/ape1HIIZ6T5d7GS61YBs3rD4NVvkfnVwELcCRW4Bqv0="
        )
        .add(
            "copilot.tencent.com",
            "sha256/msf4AsD2EfdPpQLf6h3td+B1/1V0xJllDZxa+Gdh4yY=",
            "sha256/iRVt6vFAG0+gB4h7KFntBfxhDjw2OHuU0tsCfL5BCW8=",
            "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="
        )
        // 华为码道（devcloud 与 codearts 同属 huaweicloud，叶子不同、中间 CA 相同）
        .add(
            "**.huaweicloud.com",
            "sha256/+R5dERLFt66s7Bof7N0z2jLMLblOjffkKXeWWHDrTCQ=",
            "sha256/wOWNjm5D6jCrROkWzSwgdF5PWvff8qG4kh0XKgIVnAY=",
            "sha256/hETpgVvaLC0bvcGG3t0cuqiHvr4XyP2MTwCiqhgRWwU="
        )
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // P2-3（BUGS-v1.63）：connect+read 之和最坏是 45 秒，而 LocalApi.parallel 的
        // TASK_TIMEOUT_SEC 是 60 秒 —— 中间那 15 秒没有任何保护。更关键的是
        // `f.cancel(true)` 对阻塞在 `Call.execute()` 的线程**无效**（不响应中断），
        // 只能靠 callTimeout 让 OkHttp 自己抛 InterruptedIOException 结束调用、
        // 把线程还给 6 线程的 NET_POOL。35 秒 < 60 秒，保证线程先自己退出。
        .callTimeout(35, TimeUnit.SECONDS)
        // M4：固定上游证书，代理也换不掉
        .certificatePinner(PINNER)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun post(url: String, headers: Map<String, String>, body: String = "{}",
                     c: OkHttpClient = client): Pair<Int, String> {
        val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
        c.newCall(b.build()).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    private fun get(url: String, headers: Map<String, String>,
                    c: OkHttpClient = client): Pair<Int, String> {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
        c.newCall(b.build()).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    /** 站点被墙时可填代理；空串走系统默认网络。 */
    private fun clientFor(proxy: String): OkHttpClient {
        val p = proxy.trim()
        if (p.isBlank()) return client
        return try {
            val u = java.net.URI(if (p.contains("://")) p else "http://$p")
            val port = if (u.port > 0) u.port else 7890
            client.newBuilder().proxy(
                java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(u.host, port))
            ).build()
        } catch (e: Exception) {
            client
        }
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        JSONObject().put("code", -1).put("msg", text.take(300))
    }

    /** 依次尝试多个字段名，返回第一个 ≥0 的整数值；都没有返回 -1。 */
    private fun intOfAny(o: JSONObject, vararg keys: String): Int {
        keys.forEach { k ->
            val v = when (val x = o.opt(k)) {
                is Number -> x.toInt()
                is String -> x.toIntOrNull() ?: -1
                else -> -1
            }
            if (v >= 0) return v
        }
        return -1
    }

    /**
     * MiniMax 设备指纹。
     *
     * 以前 uuid / device_id 的兜底值写死在两处（签到 + 查余额），全局同一个
     * "<旧版本写死的 uuid>" / "<旧版本写死的 deviceId>"。所有用户共用一套指纹，
     * 上游按 uuid 聚合就把所有人看成一家，风控一打死就是整批。
     * 这里改成按账号稳定派生：同一账号每次同一个值（保住「设备不变」的语义），
     * 不同账号不会撞。用户显式填了 uuid / deviceId 时仍然优先用填的。
     */
    internal object MmFingerprint {
        private fun seed(userId: String, token: String): String =
            userId.trim().ifBlank { token.trim().take(32) }.ifBlank { "minimax" }

        fun uuid(userId: String, token: String): String {
            val s = seed(userId, token)
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest("signhub-minimax:$s".toByteArray(Charsets.UTF_8))
            // 手工拼 v5 风格的 UUID（8-4-4-4-12），避免依赖 java.util.UUID.nameUUIDFromBytes
            // 那个是 MD5-based v3，这里用 SHA-256 更稳。
            //
            // 必须按 RFC 4122 写版本位与变体位：第 7 字节高 4 位 = 版本号(0101 = 5)、
            // 第 9 字节高 2 位 = 变体(10)。以前直接切片，生成的是「随机 128 位」，
            // 上游若用 uuid 库校验格式（UUID.fromString 后比对 version()）会被当异常指纹。
            val b = bytes.copyOf(16)
            b[6] = ((b[6].toInt() and 0x0F) or 0x50).toByte()
            b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
            val h = b.joinToString("") { "%02x".format(it) }
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
                "${h.substring(16, 20)}-${h.substring(20, 32)}"
        }

        fun deviceId(explicit: String, userId: String, token: String): String {
            val d = explicit.trim()
            if (d.isNotEmpty()) return d
            val s = seed(userId, token)
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest("signhub-device:$s".toByteArray(Charsets.UTF_8))
            var n = 0L
            for (i in 0 until 8) n = (n shl 8) or (bytes[i].toLong() and 0xFF)
            return (n % 10_000_000_000_000_000L).toString().padStart(16, '0')
        }
    }

    // ── WorkBuddy ──────────────────────────────────────────────────────
    /**
     * 「该账号没有可领的签到活动」的统一文案。
     *
     * 国际版（workbuddy.ai）实测**根本没有签到活动**：国内客户端在同一个进程里对国际版
     * 账号打印 `[Checkin] refreshStatus -> inactive {"hasData":false}`，对国内账号打印
     * `active:true`。桌面端之所以能签，是因为它额外带了 `X-Device-Token`
     * （腾讯 Turing 风控 SDK 的 `v3:` 设备令牌，见 `app.asar` 的
     * `DesktopAccountRepo.getCheckinRequestHeaders`），Android 端无法复刻。
     * 所以国际版只能「读余额」，不能「签到」—— 文案要说清楚，别让用户以为是自己配错了。
     */
    private fun noActivityText(global: Boolean): String =
        if (global) "国际版账号没有签到活动（只能读余额）" else "该账号当前没有可领取的签到活动"

    suspend fun workbuddy(token: String, uid: String, enterpriseId: String,
                           domain: String, global: Boolean): CheckinResult =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext CheckinResult(
                "workbuddy", "fail", "缺少 accessToken：请在设置里填写")

            val base = if (global) WB_GLOBAL else WB_CN_BILLING
            val ref = if (global) WB_GLOBAL else WB_CN_BILLING
            val headers = mutableMapOf(
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to ref,
                "Referer" to "$ref/",
                "User-Agent" to WB_UA,
                "Authorization" to "Bearer $token",
            )
            if (uid.isNotBlank()) headers["X-User-Id"] = uid
            if (enterpriseId.isNotBlank()) {
                headers["X-Enterprise-Id"] = enterpriseId
                headers["X-Tenant-Id"] = enterpriseId
            }
            if (domain.isNotBlank()) headers["X-Domain"] = domain

            try {
                val (stCode, stText) = post("$base/v2/billing/meter/checkin-activity-status", headers)
                val st = parse(stText)
                if (stCode == 401 || stCode == 403) return@withContext CheckinResult(
                    "workbuddy", "fail", "凭据被拒绝（HTTP $stCode），token 可能过期")
                val code = st.optInt("code", -1)
                val dataOrNull = st.optJSONObject("data")
                // 官方客户端（`DesktopAccountRepo.checkinStatus`）对「code != 0 或没有 data」
                // 的处理是 **直接当作没有活动**（`if(e?.code!==0||!e.data)return;`），不是报错。
                // 国际版账号实测就是走这条路：国内客户端日志对国际版 accountUid 打印
                // `[Checkin] refreshStatus -> inactive {"hasData":false}`。
                // 所以这里必须收敛成「无活动」而不是「失败」，否则用户每次签到都看到红条。
                if (code != 0 || dataOrNull == null) {
                    val heldNo = heldCredits(token, uid, enterpriseId, domain, global)
                    val why = st.optString("msg").take(80).ifBlank { "上游未返回签到活动" }
                    return@withContext CheckinResult(
                        "workbuddy", "already",
                        noActivityText(global) + " · " + why +
                            (if (heldNo >= 0) " · 当前积分 $heldNo" else ""),
                        "", heldNo, -1)
                }

                val data = dataOrNull
                // `active=false` 同样等于「本期没有活动」（活动未开窗 / 已结束 / 该区域未上线）
                if (!data.optBoolean("active", true)) {
                    val heldNo = heldCredits(token, uid, enterpriseId, domain, global)
                    return@withContext CheckinResult(
                        "workbuddy", "already",
                        noActivityText(global) + " · 活动未开启或已结束" +
                            (if (heldNo >= 0) " · 当前积分 $heldNo" else ""),
                        "", heldNo, -1)
                }
                val checked = data.optBoolean("today_checked_in", false)
                val streak = data.optInt("streak_days", 0)
                val credit = data.optInt("today_credit", data.optInt("daily_credit", 0))
                // 账号**当前持有**积分：必须走 billing/get-user-resource 的余额口径。
                // 不要用 total_credits —— 那是「活动累计发放」（实测某账号 2200，
                // 而真实可用只有 531.99），拿它当余额会严重虚高。
                var held = heldCredits(token, uid, enterpriseId, domain, global)

                if (checked) return@withContext CheckinResult(
                    "workbuddy", "already",
                    "今日已签到" + (if (streak > 0) " · 连续 $streak 天" else "") +
                        (if (credit > 0) " · 今日 $credit 积分" else ""),
                    if (credit > 0) "+$credit" else "",
                    held, streak)

                val (cCode, cText) = post("$base/v2/billing/meter/daily-checkin", headers)
                val cl = parse(cText)
                if (cCode == 401 || cCode == 403) return@withContext CheckinResult(
                    "workbuddy", "fail", "领取时凭据被拒绝（HTTP $cCode）")
                val c2 = cl.optInt("code", -1)
                if (c2 != 0) {
                    // 10001 = 风控/网关层拒绝（不是活动业务码：官方 mapCheckinStatus 只认 1001/1002/1003）。
                    // 桌面端签到会额外带 `X-Device-Token`（腾讯 Turing 风控 SDK 的 `v3:` 设备令牌），
                    // Android 端拿不到这个令牌 —— 所以国际版/无活动账号必然落到这里。
                    // 与其让用户每次看到「领取失败 code=10001」，不如如实说明「该账号没有可领的活动」。
                    val heldNo = heldCredits(token, uid, enterpriseId, domain, global)
                    val why = cl.optString("msg").take(80).ifBlank { "上游拒绝（code=$c2）" }
                    return@withContext CheckinResult(
                        "workbuddy", "already",
                        noActivityText(global) + " · " + why +
                            (if (heldNo >= 0) " · 当前积分 $heldNo" else ""),
                        "", heldNo, streak)
                }
                val cd = cl.optJSONObject("data") ?: JSONObject()
                val gained = cd.optInt("today_credit", cd.optInt("daily_credit", credit))
                val streakAfter = cd.optInt("streak_days", streak)
                // 领完再查一次真实余额；查不到就沿用领之前的值（绝不拿 total_credits 顶）
                val heldAfter = heldCredits(token, uid, enterpriseId, domain, global)
                if (heldAfter >= 0) held = heldAfter
                CheckinResult(
                    "workbuddy", "success",
                    "签到成功" + (if (gained > 0) " · +$gained 积分" else "") +
                        (if (streakAfter > 0) " · 连续 $streakAfter 天" else ""),
                    if (gained > 0) "+$gained" else "",
                    held, streakAfter)
            } catch (e: Exception) {
                CheckinResult("workbuddy", "fail", "网络错误：${e.message}")
            }
        }

    // ── WorkBuddy 真实持有积分 ─────────────────────────────────────────
    /**
     * 账号**当前持有**的可用积分（与官网「积分明细」口径一致）。
     *
     * 口径：逐个资源包取 min(CapacityRemainPrecise, CycleCapacityRemainPrecise) 求和。
     * 实测某账号 total_credits=2200（活动累计发放）而该口径只有 531.99，
     * 差的是「已用尽的订阅额度」——所以**绝对不要**用 total_credits 当余额。
     * 查询失败返回 -1，调用方保持原值，绝不臆测。
     */
    private suspend fun heldCredits(
        token: String, uid: String, enterpriseId: String, domain: String, global: Boolean
    ): Int = try {
        val acc = WbAccount(
            id = "default",
            uid = uid,
            accessToken = token,
            domain = domain,
            enterpriseId = enterpriseId,
            variant = if (global) "ai" else "cn",
        )
        val j = WbAssets.balance(acc)
        val v = j.optDouble("remaining", -1.0)
        if (v >= 0) v.toInt() else -1
    } catch (e: Exception) {
        -1
    }

    // ── Trae Solo ──────────────────────────────────────────────────────
    /**
     * Trae 账号**当前持有**的 Credits（与客户端「用量管理 → 奖励积分」同口径）。
     *
     * 数据源：POST /trae/api/v2/pay/ide_user_ent_usage {"require_usage":true,"req_source":2}
     * 口径对齐桌面客户端 main.js 的 uJe()：
     *   逐个资源包 remaining = max(credits_limit - credits_amount, 0)，再求和。
     * `credits_limit <= 0` 的是免费订阅包 / 无限量包，不计入。
     *
     * 注意：`usage_summary.total_amount` 是「历史获得总量」不是余额，别拿它当可用。
     * 查询失败返回 -1，调用方保持原值，绝不写 0 误导。
     */
    private suspend fun traeHeldCredits(token: String, deviceId: String, global: Boolean): Int =
        traeUsageSums(token, deviceId, global).first

    /**
     * 一次请求同时拿到 Trae 的 (剩余, 累计已用)；查不到返回 (-1, -1)。
     *
     * 口径与 Python 侧 trae.usage_sums 完全一致：
     *   remaining = Σ max(credits_limit - credits_amount, 0)
     *   used      = Σ credits_amount
     * credits_limit <= 0 的是免费包 / 无限量包，两个数都不计入。
     */
    suspend fun traeUsageSums(token: String, deviceId: String, global: Boolean): Pair<Int, Int> {
        return try {
            val base = if (global) TRAE_INTL else TRAE_CN
            // 余额查询用浏览器 UA（desktopUa = false）：见 traeHeaders 的注释
            val headers = traeHeaders(token, deviceId, global, desktopUa = false)
            val (code, text) = post(
                "$base$TRAE_USAGE_PATH", headers,
                """{"require_usage":true,"req_source":2}"""
            )
            if (code != 200) {
                Pair(-1, -1)
            } else {
                val doc = parse(text)
                // ide_user_ent_usage 的响应体是裸的 {user_entitlement_pack_list:[...]}，
                // 根本没有 code 字段。原来这里写死 `optInt("code",-1) == 0` →
                // 缺字段时得到 -1 ≠ 0 → 直接判失败返回 -1，积分永远显示不出来。
                // 现在与 Python 侧 held_credits 对齐：code 缺失视为成功，
                // 只有明确给了非 0 的 code（例如 -10002 未登录）才算失败。
                val codeVal = if (doc.has("code")) doc.optInt("code", -1) else 0
                val packs = if (codeVal == 0)
                    doc.optJSONArray("user_entitlement_pack_list") else null
                if (packs == null) {
                    Pair(-1, -1)
                } else {
                    var sum = 0.0
                    var usedSum = 0.0
                    var seen = 0
                    for (i in 0 until packs.length()) {
                        val p = packs.optJSONObject(i) ?: continue
                        val b = p.optJSONObject("entitlement_base_info") ?: continue
                        val q = b.optJSONObject("quota") ?: continue
                        val limit = q.optDouble("credits_limit", 0.0)
                        if (limit <= 0) continue          // 免费包 / 无限量包不计入
                        val used = p.optJSONObject("usage")?.optDouble("credits_amount", 0.0) ?: 0.0
                        sum += (limit - used).coerceAtLeast(0.0)
                        usedSum += used
                        seen++
                    }
                    if (seen == 0) Pair(-1, -1)          // 向下取整，与官网一致
                    else Pair(sum.toInt(), usedSum.toInt())
                }
            }
        } catch (e: Exception) {
            Pair(-1, -1)
        }
    }

    suspend fun traeHeld(token: String, deviceId: String, global: Boolean): Int =
        withContext(Dispatchers.IO) { traeHeldCredits(token, deviceId, global) }

    /**
     * 查 Trae 当前持有积分，token 缺失/过期时先用 session 换一枚新的。
     *
     * 这是「Trae 积分一直不显示」的根因修复：后台补数原来直接拿 8 小时前就过期的
     * JWT 去请求 ide_user_ent_usage，必然 401 → 返回 -1 → 前端只能显示「积分稍后刷新」。
     * 这里和签到走同一条续期链路，换到的新 token 通过 onRenewed 交给调用方落盘。
     *
     * @return 持有积分；查不到返回 -1（调用方保持原值，绝不写 0）
     */
    suspend fun traeHeldAuto(
        token: String,
        deviceId: String,
        global: Boolean,
        session: String,
        onRenewed: ((String) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) return@withContext -1
        var tok = token
        val exp = if (tok.isBlank()) -1L else jwtExpiresAt(tok)
        val nowSec = System.currentTimeMillis() / 1000
        if (session.isNotBlank() && (tok.isBlank() || (exp > 0 && exp <= nowSec + 60))) {
            val fresh = traeExchangeToken(session, global)
            if (!fresh.isNullOrBlank()) {
                tok = fresh
                onRenewed?.invoke(fresh)
            }
        }
        if (tok.isBlank()) return@withContext -1
        var v = traeHeldCredits(tok, deviceId, global)
        // 走到这里还失败，多半是这枚 token 被服务端提前废弃了，有 session 就再换一次
        if (v < 0 && session.isNotBlank()) {
            val fresh = traeExchangeToken(session, global)
            if (!fresh.isNullOrBlank() && fresh != tok) {
                onRenewed?.invoke(fresh)
                v = traeHeldCredits(fresh, deviceId, global)
            }
        }
        v
    }

    /**
     * 用 X-Cloudide-Session（Cookie，约 14 天）换一枚新的 Cloud-IDE-Token（JWT，约 8 小时）。
     *
     * 这是 Trae 唯一稳定的续期通道：注册/登录后 session 存在 Cookie 里，长期有效；
     * 而签到要的 Authorization: Cloud-IDE-JWT 是短效 JWT，8 小时后必然 401。
     * 保存 session 之后，token 过期只要换一次就行，不需要重新扫码。
     *
     * 成功返回新 token，失败返回 null（调用方保留原 token，不覆盖）。
     */
    suspend fun traeExchangeToken(session: String, global: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (session.isBlank()) return@withContext null
            val base = if (global) TRAE_INTL else TRAE_CN
            val refHost = if (global) "https://www.trae.ai" else "https://www.trae.cn"
            val headers = mapOf(
                "Cookie" to "X-Cloudide-Session=$session",
                "Referer" to "$refHost/",
                "Origin" to refHost,
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            )
            try {
                val (code, text) = post("$base$TRAE_TOKEN_PATH", headers)
                if (code !in 200..299) return@withContext null
                val doc = parse(text)
                val result = doc.optJSONObject("Result") ?: doc.optJSONObject("result") ?: doc
                val tok = result.optString("Token", result.optString("token", ""))
                if (tok.length > 40) tok else null
            } catch (e: Exception) {
                null
            }
        }

    /** JWT 的 payload 解成 JSONObject；解不开返回 null。 */
    private fun jwtPayload(token: String): JSONObject? {
        try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            val raw = android.util.Base64.decode(p, android.util.Base64.NO_WRAP)
            return JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
    }

    /** JWT 里的 exp（秒）；解析不出来返回 -1。用于判断 token 是否已过期。 */
    fun jwtExpiresAt(token: String): Long {
        val exp = jwtPayload(token)?.optLong("exp", -1) ?: -1
        return if (exp > 0) exp else -1
    }

    /**
     * JWT 里的**上游账号 uid**（用户 m18704「每个账号积分都一致」的根因修复）。
     *
     * 为什么需要它：`sameTrae()` 原来只比 token / session，而**同一个 Trae 账号在网页上
     * 重新登录一次就会得到一枚全新 token + 全新 session** —— 于是同一个号被登录三次就变成
     * 三条「不同账号」的记录，三张卡各自拿着同一账号的三枚 token 去查余额，当然全是同一个数
     * （截图里三个 Trae 都是 8207）。token 会变、session 会变，只有 JWT 里的 uid 不变，
     * 它才是「这是同一个人」的稳定判据。
     *
     * 取不到就返回空串，调用方退回原来的 token/session 比对（不会误合并）。
     */
    fun jwtUid(token: String): String {
        val o = jwtPayload(token) ?: return ""
        // Trae 的 Cloud-IDE-JWT 实测 payload 里是 `sub`（数字字符串），
        // 另外两种是兜底：不同版本/不同端可能换字段名，多试两个不吃亏。
        for (k in listOf("sub", "uid", "user_id", "userId")) {
            val v = o.optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    /**
     * Trae claim 的**全局节流**（用户 m18704「一键全部签到用时很长」的修复）。
     *
     * 原来的做法是把同一 provider 的所有账号收进一个 job、账号之间 `Thread.sleep(12~25 秒)`
     * 硬串行 —— 3 个账号光等待就 24~50 秒，用户体感「一键签到要等半天」。
     *
     * 关键事实：**Trae 的 status 查询不会触发 9074，只有 claim 会**（9074 =
     * ErrWorkRiskControlBanned，是领取接口的风控）。所以查询可以全部并行，只需要让
     * claim 排队。这里把节流粒度从「整个账号」收到「claim 这一次请求」：
     * 两个账号的 status 并行查完，claim 依次放行，间隔 [TRAE_CLAIM_GAP_MS]。
     *
     * 间隔取 3 秒而不是 12~25 秒：12~25 秒是同类项目为「账号全部串行」定的经验值，
     * 它要覆盖的不只是 claim 间隔、还有 status 与连接建立；这里已经并行了，
     * claim 之间只隔一次 HTTP 往返的时间，3 秒足够让上游把两次领取分到不同时间窗口，
     * 又不至于让用户干等。
     */
    private val traeClaimLock = Any()
    private var traeClaimLastAt = 0L
    private const val TRAE_CLAIM_GAP_MS = 3_000L

    private fun throttleTraeClaim() {
        synchronized(traeClaimLock) {
            val wait = TRAE_CLAIM_GAP_MS - (System.currentTimeMillis() - traeClaimLastAt)
            if (wait > 0) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) {}
            }
            traeClaimLastAt = System.currentTimeMillis()
        }
    }

    suspend fun trae(token: String, deviceId: String, global: Boolean): CheckinResult =
        trae(token, deviceId, global, "", null, null)

    /**
     * @param session X-Cloudide-Session；非空时 token 被拒会自动换新并重试一次
     * @param onRenewed 换到新 token 时回调（调用方负责写回设置，否则下次还是旧的）
     * @param onRenewedDevice 设备号从「空/非法」被补成确定性派生值时回调（必须写回，
     *        否则下次又从空开始算，虽然结果一样，但界面一直显示为空）
     */
    suspend fun trae(
        token: String,
        deviceId: String,
        global: Boolean,
        session: String,
        onRenewed: ((String) -> Unit)? = null,
        onRenewedDevice: ((String) -> Unit)? = null,
    ): CheckinResult =
        withContext(Dispatchers.IO) {
            // 设备号：合法就原样用；空/非法则按账号凭据**确定性派生**（v2.6）。
            // 绝不能随机生成 —— 随机号等于每天换一台新设备，服务端永远认不出，只会一直 9074。
            val seed = session.ifBlank { token }
            var dev0 = deviceId.trim()
            if (!DeviceId.isValid(dev0)) {
                val derived = DeviceId.derive(seed)
                if (derived != dev0) {
                    dev0 = derived
                    try { onRenewedDevice?.invoke(derived) } catch (_: Exception) {}
                }
            }
            if (!DeviceId.isValid(dev0)) return@withContext CheckinResult(
                "traesolo", "fail", "缺少 x-device-id（16 位数字）")

            // 进来先看 token 是否已过期：过期但手上有 session 就直接换，省一次必然失败的请求
            var tok = token
            var tokenFrom = "保存的 token"
            if (session.isNotBlank()) {
                val exp = jwtExpiresAt(tok)
                val now = System.currentTimeMillis() / 1000
                if (tok.isBlank() || (exp > 0 && exp <= now + 60)) {
                    val fresh = traeExchangeToken(session, global)
                    if (fresh != null) {
                        tok = fresh; tokenFrom = "session 换新"
                        try { onRenewed?.invoke(fresh) } catch (_: Exception) {}
                    }
                }
            }
            if (tok.isBlank()) return@withContext CheckinResult(
                "traesolo", "fail", "缺少 Cloud-IDE-Token：请点「登录 Trae 账号」，或在设置里填 session")

            val base = if (global) TRAE_INTL else TRAE_CN
            var dev = dev0
            var headers = traeHeaders(tok, dev, global)

            // 一次签到接口调用：先按 IDE 口径发 req_source=1（v2.7 起一直这么发），
            // 若回 9074 再用 SOLO 口径 req_source=2 重发一次。
            //
            // 依据：桌面端 `const u = ii(this.X) ? 2 : 1`，`ii()` 判 packageType 是否属于
            // SOLO 系列；本机客户端日志里 `package_type` 就是 `"SOLO"`。v2.7 恒发 1 能过是
            // 因为服务端两种都收，但账号/设备形态不同时可能只有一种被接受 —— 各试一次
            // 的成本只是一次 HTTP，比让用户反复点「重试」划算。
            fun traeCall(path: String): Pair<Int, String> {
                val a = post("$base$path", headers, TRAE_STATUS_BODY)
                if (parse(a.second).optInt("code", -1) != TRAE_DEVICE_CODE) return a
                val b = post("$base$path", headers, TRAE_STATUS_BODY_SOLO)
                return if (parse(b.second).optInt("code", -1) == TRAE_DEVICE_CODE) a else b
            }

            try {
                var (stCode, stText) = traeCall("/trae/api/v2/ug/checkin_credits/status")

                // 凭据被拒有两种表现，都要处理：
                //   ① HTTP 401/403；
                //   ② **HTTP 200 + body code=1001**（Trae 的实测行为，见 TRAE_AUTH_CODES）。
                // 只判 ① 会让 1001 落到「上游失败 code=1001」，用户看不懂也不知道该重登。
                fun needsRenew(http: Int, body: String): Boolean {
                    if (http == 401 || http == 403) return true
                    if (http !in 200..299) return false
                    return parse(body).optInt("code", 0) in TRAE_AUTH_CODES
                }

                if (needsRenew(stCode, stText)) {
                    if (session.isBlank()) return@withContext CheckinResult(
                        "traesolo", "fail",
                        "登录已过期（Trae 拒绝了这个 token），请重新登录 Trae 账号；" +
                            "或在设置里填 X-Cloudide-Session 让它自动续期")
                    val fresh = traeExchangeToken(session, global)
                    if (fresh == null) return@withContext CheckinResult(
                        "traesolo", "fail",
                        "登录已过期，且 session 也换不到新 token（session 约 14 天，可能已失效），请重新登录 Trae")
                    tok = fresh; tokenFrom = "session 换新"
                    try { onRenewed?.invoke(fresh) } catch (_: Exception) {}
                    headers = headers + ("Authorization" to "Cloud-IDE-JWT $tok")
                    val r2 = traeCall("/trae/api/v2/ug/checkin_credits/status")
                    stCode = r2.first; stText = r2.second
                    if (needsRenew(stCode, stText)) return@withContext CheckinResult(
                        "traesolo", "fail", "换新后仍被拒绝，session 可能已过期，请重新登录 Trae")
                }
                if (stCode == 401 || stCode == 403) return@withContext CheckinResult(
                    "traesolo", "fail", "凭据被拒绝（HTTP $stCode），token 可能过期；" +
                        "在设置里填 X-Cloudide-Session 可自动续期")

                val st = parse(stText)
                var code = st.optInt("code", -1)

                // 9074 的真实语义（v2.6 更正，前三个版本都判错了）：
                //
                // 上游文案「当前参与用户太多，请稍后再试」是**误导性文案**。实测与同类开源项目
                // （L0NE-6/Trae-AutoCheckin，2026-10-09 更正）都指向同一件事：
                //   **9074 = 这个 x-device-id 在该账号下没有注册记录**，与出口 IP、请求头、
                //   请求频率都无关 —— 「等几分钟再试」永远等不到。
                //
                // 由此推出两条硬规则（已写进 DeviceId 的 KDoc）：
                //   ① 同一个设备号只能给一个账号用（第二个账号拿它签 = 用别人的注册记录 → 9074）；
                //   ② 设备号必须永久稳定（每次重新生成 = 每天换新设备 → 永远未注册 → 9074）。
                //
                // 因此这里**不再自动换设备号**。历史教训：
                //   v2.4 每次登录 DeviceId.generate()、v2.5 遇 9074 就换号并写回设置，
                //   结果是主账号的号被反复改（5250250676249340 → 5252260219850605 →
                //   5256668681776575），换完下次又 9074，形成死循环。
                //   设备号现在由 DeviceId.derive(账号凭据) 确定性派生，一次算好终身不变。
                //
                // 仍保留「token 失效在部分节点回 9074 而非 1001」的兜底：用 session 换新重试一次。
                if (code == TRAE_DEVICE_CODE && session.isNotBlank() && tokenFrom != "session 换新") {
                    val fresh = traeExchangeToken(session, global)
                    if (fresh != null) {
                        tok = fresh; tokenFrom = "session 换新"
                        try { onRenewed?.invoke(fresh) } catch (_: Exception) {}
                        headers = headers + ("Authorization" to "Cloud-IDE-JWT $tok")
                        val r2 = traeCall("/trae/api/v2/ug/checkin_credits/status")
                        stCode = r2.first; stText = r2.second
                        code = parse(stText).optInt("code", -1)
                    }
                }

                // 9004 / 9095 是「今日已签到」，不是失败（以前会误判成 fail）
                if (code != 0 && code !in TRAE_ALREADY_CODES) {
                    // 凭据类错误给「该怎么做」，而不是把上游英文原文甩给用户
                    if (code in TRAE_AUTH_CODES) return@withContext CheckinResult(
                        "traesolo", "fail", "登录已过期（code=$code），请重新登录 Trae 账号")
                    // 9074：设备校验没过。给出可执行的出路，而不是让用户干等。
                    if (code == TRAE_DEVICE_CODE) return@withContext CheckinResult(
                        "traesolo", "fail", traeDeviceFailMsg(dev))
                    return@withContext CheckinResult(
                        "traesolo", "fail",
                        "上游失败 code=$code ${st.optString("message", st.optString("msg")).take(120)}")
                }
                val data = st.optJSONObject("data") ?: st
                if (code in TRAE_ALREADY_CODES || data.optBoolean("checked_in", false)) {
                    // 当前持有积分走 ide_user_ent_usage 余额口径；签到响应里的 credits 只是当日奖励
                    val sums = traeUsageSums(tok, dev, global)
                    val held = sums.first
                    return@withContext CheckinResult(
                        "traesolo", "already",
                        "今日已签到" + (if (held >= 0) " · 当前积分 $held" else "") +
                            (if (tokenFrom == "session 换新") " · token 已自动续期" else ""),
                        totalPoints = held, usedPoints = sums.second)
                }
                if (!data.optBoolean("enable", true)) return@withContext CheckinResult(
                    "traesolo", "fail", "该账号签到活动未开启（enable=false）")

                // 只有真正要 claim 的账号才排队（见 throttleTraeClaim 的注释：
                // status 查询不触发 9074，并行是安全的）。
                throttleTraeClaim()
                var (cCode, cText) = traeCall("/trae/api/v2/ug/checkin_credits/claim")
                if (needsRenew(cCode, cText)) {
                    if (session.isNotBlank()) {
                        val fresh = traeExchangeToken(session, global)
                        if (fresh != null) {
                            tok = fresh
                            try { onRenewed?.invoke(fresh) } catch (_: Exception) {}
                            headers = headers + ("Authorization" to "Cloud-IDE-JWT $tok")
                            throttleTraeClaim()
                            val r3 = traeCall("/trae/api/v2/ug/checkin_credits/claim")
                            cCode = r3.first; cText = r3.second
                        }
                    }
                }
                if (needsRenew(cCode, cText)) return@withContext CheckinResult(
                    "traesolo", "fail", "领取时登录已过期，请重新登录 Trae 账号")

                val cl = parse(cText)
                val cd = cl.optJSONObject("data") ?: cl
                val cCodeV = cl.optInt("code", 0)
                // 领取阶段同样可能回「今日已签到」
                if (cCodeV != 0 && cCodeV !in TRAE_ALREADY_CODES) {
                    // 领取阶段的 9074 与 status 阶段同源：设备号在该账号下没有注册记录。
                    // 以前这里只甩「领取失败 code=9074 当前参与用户太多，请稍后再试」，
                    // 用户看到的就是截图里那行，完全不知道该怎么办。两边文案必须一致。
                    if (cCodeV == TRAE_DEVICE_CODE) return@withContext CheckinResult(
                        "traesolo", "fail", traeDeviceFailMsg(dev))
                    return@withContext CheckinResult(
                        "traesolo", "fail",
                        "领取失败 code=$cCodeV ${cl.optString("message", cl.optString("msg")).take(120)}")
                }
                val extra = cd.optDouble("extra_credits", 0.0)
                val credits = cd.optDouble("credits", 0.0)
                val gained = if (extra > 0) extra else credits
                // 领完再查一次真实余额
                val sumsAfter = traeUsageSums(tok, dev, global)
                val heldAfter = sumsAfter.first
                CheckinResult(
                    "traesolo", "success",
                    "签到成功" + (if (gained > 0) " · +${gained.toInt()} 积分" else "") +
                        (if (heldAfter >= 0) " · 当前积分 $heldAfter" else "") +
                        (if (tokenFrom == "session 换新") " · token 已自动续期" else ""),
                    if (gained > 0) "+${gained.toInt()}" else "",
                    heldAfter, usedPoints = sumsAfter.second)
            } catch (e: Exception) {
                CheckinResult("traesolo", "fail", "网络错误：${e.message}")
            }
        }

    // ── Qoder CN（每日积分活动）─────────────────────────────────────────
    /**
     * Qoder 国内版每日积分活动：
     *   GET  https://openapi.qoder.com.cn/sash/api/v1/me/campaigns?clientType=10
     *        → { uid, claimable, campaigns:[{campaignId, actionType, claimStatus,
     *            benefit:{kind:"CREDITS", amount, validity}}] }
     *   POST https://openapi.qoder.com.cn/sash/api/v1/me/campaigns/{id}/claim
     *        → { benefit:{amount} }
     * 头：Authorization: Bearer <dt- token> + Cosy-ClientType: 10 + Cosy-Version: 0.3.3
     *     + Cosy-MachineOS: windows + Cosy-MachineId: <auth.machine-id> + User-Agent: Qoder
     * 判定：actionType == "CLAIM_BENEFIT" 才是积分活动；claimStatus == "CLAIMABLE" 可领，
     *       "CLAIMED" 即今日已领（already）。国际版还要 Cosy-MachineToken/Code/Type，本函数只做国内版。
     */
    suspend fun qoder(
        region: String, token: String, uid: String, machineId: String, proxy: String,
        refreshToken: String = "",
        onRenewed: ((token: String, refresh: String) -> Unit)? = null,
    ): CheckinResult = withContext(Dispatchers.IO) {
        if (token.isBlank() && refreshToken.isBlank()) return@withContext CheckinResult(
            "qoder", "fail", "缺少凭据：填 dt- 的 accessToken，或 drt- 的 refreshToken（推荐，有效期一年）")
        val isIntl = region.trim().lowercase() == "intl"
        val base = if (isIntl) "https://openapi.qoder.sh" else "https://openapi.qoder.com.cn"
        val c = clientFor(proxy)

        // 续期：POST /api/v1/deviceToken/refresh {"refresh_token":"drt-..."}
        // 实测同一个 drt- 可反复换（不是一次性），有效期一年，所以手机上只填它就能长期签。
        fun renew(rt: String): Pair<String, String>? {
            if (rt.isBlank()) return null
            return try {
                val (code, text) = post(
                    "$base/api/v1/deviceToken/refresh",
                    mapOf("Content-Type" to "application/json", "Accept" to "application/json",
                          "User-Agent" to "Qoder"),
                    JSONObject().put("refresh_token", rt.trim()).toString(), c)
                if (code !in 200..299) return null
                val d = parse(text)
                val nt = d.optString("device_token").ifBlank { d.optString("token") }
                if (nt.isBlank()) null
                else Pair(nt, d.optString("refresh_token").ifBlank { rt.trim() })
            } catch (e: Exception) {
                null
            }
        }

        var tok = token.trim()
        if (tok.isBlank()) {
            val r = renew(refreshToken)
                ?: return@withContext CheckinResult(
                    "qoder", "fail", "refreshToken 续期失败：drt- 可能已失效，请重新登录 Qoder CN 客户端")
            tok = r.first
            // BUGS-v2.63-287 P3-1：新 refresh_token 一旦变了就必须回写，否则下一次
            // 还在用旧的换 access。上游当前不轮换（AGENTS.md §6），所以这里平时不触发；
            // 一旦上游改成轮换，不回写就表现为「某天起天天提示 refreshToken 续期失败」——
            // 静默退化，没有日志线索。用回调而不是改返回值，是为了不动另外几处解构形状。
            if (r.second.isNotBlank() && r.second != refreshToken) {
                runCatching { onRenewed?.invoke(r.first, r.second) }
            }
        }

        fun headers(t: String): MutableMap<String, String> {
            val h = mutableMapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer " + t,
                "Cosy-ClientType" to "10",
                "Cosy-Version" to "0.3.3",
                "Cosy-MachineOS" to "windows",
                "User-Agent" to "Qoder",
            )
            if (machineId.isNotBlank()) h["Cosy-MachineId"] = machineId.trim()
            return h
        }

        fun credits(doc: JSONObject): List<JSONObject> {
            val arr = doc.optJSONArray("campaigns") ?: return emptyList()
            val out = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("actionType") == "CLAIM_BENEFIT") out.add(o)
            }
            return out
        }
        fun statusOf(o: JSONObject) = o.optString("claimStatus").uppercase()
        fun amountOf(o: JSONObject) =
            o.optJSONObject("benefit")?.optInt("amount", 0) ?: 0

        try {
            var (code, text) = get("$base/sash/api/v1/me/campaigns?clientType=10", headers(tok), c)
            if ((code == 401 || code == 403) && refreshToken.isNotBlank()) {
                // token 过期但手里有 drt-：续期后重试一次
                val r = renew(refreshToken)
                if (r != null) {
                    tok = r.first
                    // BUGS-v2.63-287 P3-1：第二处续期同样要回写新 refresh_token
                    if (r.second.isNotBlank() && r.second != refreshToken) {
                        runCatching { onRenewed?.invoke(r.first, r.second) }
                    }
                    val again = get("$base/sash/api/v1/me/campaigns?clientType=10", headers(tok), c)
                    code = again.first
                    text = again.second
                }
            }
            if (code == 401 || code == 403) return@withContext CheckinResult(
                "qoder", "fail", "凭据被拒绝（HTTP $code）：token 已过期且续期不成功，请重填 refreshToken")
            if (code != 200) return@withContext CheckinResult(
                "qoder", "fail", "读取活动失败 HTTP $code：${text.take(160)}")
            val doc = parse(text)
            val list = credits(doc)
            val claimable = list.filter { statusOf(it) == "CLAIMABLE" }
            if (claimable.isEmpty()) {
                if (list.isEmpty()) return@withContext CheckinResult(
                    // 「今天没有活动」不是失败：以前回 fail 会让卡片每天亮红点。
                    "qoder", "already", "当前没有可领取的积分活动（活动未开窗或已结束）")
                val total = list.sumOf { amountOf(it) }
                // 持有额度走 /api/v1/me/usage（与卡片口径一致），活动面额只写进 message
                val sums = qoderHeldAndUsed(region, tok, uid, machineId, proxy, refreshToken, onRenewed)
                return@withContext CheckinResult(
                    // Qoder 不是「签到」：每日 10:00（UTC+8）刷新一次积分活动（用户 m08264 第 ② 条）
                    "qoder", "already", "今日已领取（本期 $total Credits）· 每日 10:00（UTC+8）刷新", "",
                    sums.first, usedPoints = sums.second)
            }
            var gained = 0
            val failed = ArrayList<String>()
            for (cmp in claimable) {
                val cid = cmp.optString("campaignId")
                if (cid.isBlank()) continue
                val (cc, ct) = post(
                    "$base/sash/api/v1/me/campaigns/$cid/claim", headers(tok), "{}", c)
                // 只看 HTTP 2xx 不够：业务失败也是 200，body 里带非 0 的 code。
                // 以前一律当成功，于是「领取失败」被记成「已领 +N 积分」。
                val body = parse(ct)
                val bizCode = body.opt("code")
                val bizOk = (bizCode == null || bizCode == 0 || bizCode == "0") &&
                    body.optString("error").isBlank()
                if (cc in 200..299 && bizOk) {
                    val amt = body.optJSONObject("benefit")?.optInt("amount", 0) ?: 0
                    gained += if (amt > 0) amt else amountOf(cmp)
                } else if (cc in 200..299) {
                    failed.add("$cid:业务失败 code=$bizCode")
                } else {
                    failed.add("$cid:HTTP $cc")
                }
            }
            if (gained > 0) {
                val sums = qoderHeldAndUsed(region, tok, uid, machineId, proxy, refreshToken, onRenewed)
                CheckinResult(
                    "qoder", "success", "已领取 +$gained Credits · 每日 10:00（UTC+8）刷新",
                    "+$gained", sums.first, usedPoints = sums.second)
            } else CheckinResult(
                "qoder", "fail", "领取失败：${failed.joinToString("；").ifBlank { "上游未返回积分" }}")
        } catch (e: Exception) {
            CheckinResult("qoder", "fail", "网络错误：${e.message}")
        }
    }

    // ── MiniMax Code（每日签到，7 天一轮）───────────────────────────────
    /**
     * GET  https://agent.minimax.cn/minimax-cloud/api/v1/signin/status
     * POST https://agent.minimax.cn/minimax-cloud/api/v1/signin/claim  body {}
     * 信封 {data:{scene, days:[{day_no, points, status, is_today}]}, base_resp:{status_code,status_msg}}
     * status: 1=Upcoming 2=Claimable 3=Claimed 4=Disabled；claim_result: 1=Claimed 2=AlreadyClaimed
     * 必须带签名（算法从 app.asar 里逆出）：
     *   x-timestamp = 秒级 unix
     *   x-signature = md5(str(second) + SIGNATURE_SALT + body)
     *   yy          = md5(encodeURIComponent(path + "?" + query) + "_" + (body ?: "{}")
     *                     + md5(str(nowMs)) + "ooui")
     */
    suspend fun minimax(
        token: String, userId: String, deviceId: String, proxy: String,
    ): CheckinResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext CheckinResult(
            "minimax", "fail", "缺少 accessToken：填网页端 localStorage['_token'] 的值，或在本机登录 MiniMax Code 后点「读取本机凭据」")
        val c = clientFor(proxy)

        fun signed(path: String, body: String = "", isPost: Boolean = false): Pair<Int, String> {
            val (query, nowMs) = mmParams(userId, token, deviceId)
            val h = mmSignHeaders(token, path, query, nowMs, body)
            val url = "https://agent.minimax.cn$path?$query"
            return if (isPost) post(url, h, body.ifBlank { "{}" }, c) else get(url, h, c)
        }

        fun unwrap(text: String, code: Int): Pair<JSONObject?, String> {
            // 认证失败时服务端返回 HTTP 401 + {"error":"invalid access token"}，
            // 但**有的节点直接回 HTML / 空 body**（用户 m11566 截图：多账号页显示
            // 「响应非 JSON（HTTP 401）」）。原来的顺序是先 JSONObject(text)，
            // 解析失败就 return「响应非 JSON」—— 401 的语义被解析错误吞掉，
            // 用户只看到一句技术黑话，不知道该重新登录。
            // 所以先判 HTTP 状态码，再判 JSON。
            val doc = try { JSONObject(text) } catch (e: Exception) {
                if (code == 401 || code == 403) return Pair(null,
                    "token 无效或已过期（HTTP $code）：请重新「网页登录 MiniMax」取一份新的 " +
                        "localStorage['_token']，或在客户端里重新登录后点「读取本机凭据」")
                if (code >= 400) return Pair(null, "HTTP $code：${text.take(160)}")
                return Pair(null, "响应非 JSON（HTTP $code）：${text.take(160)}")
            }
            val br = doc.optJSONObject("base_resp")
            val sc = br?.optInt("status_code", 0) ?: 0
            if (sc != 0) return Pair(
                null, "接口返回错误 $sc：${br?.optString("status_msg", "") ?: ""}".trim())
            val data = doc.optJSONObject("data")
            if (data == null) {
                // 认证失败时服务端返回 HTTP 401 + {"error":"invalid access token"}，
                // 既没有 base_resp 也没有 data —— 不能笼统报「响应缺少 data」。
                val errText = (doc.optString("error", "")
                    .ifBlank { doc.optString("message", "") }).trim()
                if (code == 401 || code == 403) {
                    val hint = if (errText.isNotBlank()) "（$errText）" else ""
                    return Pair(null,
                        "token 无效或已过期$hint：请重新「网页登录 MiniMax」取一份新的 " +
                            "localStorage['_token']，或在客户端里重新登录后点「读取本机凭据」")
                }
                if (code >= 400) return Pair(null, "HTTP $code：${errText.ifBlank { text.take(160) }}")
                return Pair(null, "响应缺少 data" + (if (errText.isNotBlank()) "（$errText）" else ""))
            }
            return Pair(data, "")
        }

        /** 连续签到天数：从 is_today 往前数 status==3 的项。 */
        fun streakOf(panel: JSONObject): Int {
            val arr = panel.optJSONArray("days") ?: return 0
            val days = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { days.add(it) }
            days.sortBy { it.optInt("day_no", 0) }
            val idx = days.indexOfFirst { it.optBoolean("is_today", false) }
            if (idx < 0) return 0
            var start = idx
            val cur = days[idx].optInt("status", 0)
            if (cur != 3) start = if (cur == 2) idx - 1 else -1
            var n = 0
            while (start >= 0 && days[start].optInt("status", 0) == 3) {
                n++; start--
            }
            return n
        }

        fun todayOf(panel: JSONObject): JSONObject? {
            val arr = panel.optJSONArray("days") ?: return null
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                if (d.optBoolean("is_today", false)) return d
            }
            return null
        }

        try {
            val (sc, st) = signed("/minimax-cloud/api/v1/signin/status")
            val (data, err) = unwrap(st, sc)
            if (data == null) return@withContext CheckinResult("minimax", "fail", err)
            val today = todayOf(data)
            if (today != null && today.optInt("status", 0) == 3) {
                val n = streakOf(data)
                val (held, used) = minimaxHeldAndUsed(token, userId, deviceId, proxy)
                return@withContext CheckinResult(
                    "minimax", "already",
                    "今日已签到（第 ${today.optInt("day_no")} 天，${today.optInt("points")} 分）" +
                        (if (n > 0) " · 连签 $n 天" else ""),
                    "", held, n, usedPoints = used)
            }

            val (cc, ct) = signed("/minimax-cloud/api/v1/signin/claim", "{}", true)
            val (cd, cerr) = unwrap(ct, cc)
            if (cd == null) return@withContext CheckinResult("minimax", "fail", cerr)
            val result = cd.optInt("claim_result", -1)
            val points = cd.optInt("points", 0)
            val panel = cd.optJSONObject("panel") ?: JSONObject()
            val n = if (panel.length() > 0) streakOf(panel) else 0
            // 一次请求同时拿「当前可用 / 累计已用」，签到结果里没有这两个数
            val (held, used) = minimaxHeldAndUsed(token, userId, deviceId, proxy)
            when (result) {
                1 -> CheckinResult(
                    "minimax", "success",
                    "签到成功 +$points 分（第 ${cd.optInt("day_no")} 天）" +
                        (if (n > 0) " · 连签 $n 天" else ""),
                    if (points > 0) "+$points" else "", held, n, usedPoints = used)
                2 -> CheckinResult(
                    "minimax", "already",
                    "今日已签到（第 ${cd.optInt("day_no")} 天，$points 分）" +
                        (if (n > 0) " · 连签 $n 天" else ""),
                    "", held, n, usedPoints = used)
                else -> CheckinResult(
                    "minimax", "fail", "签到未成功（claim_result=$result）")
            }
        } catch (e: Exception) {
            CheckinResult("minimax", "fail", "网络错误：${e.message}")
        }
    }

    /**
     * Qoder 当前持有额度（只读，不签到）。
     * GET {base}/api/v1/me/usage → {"user_quota":{"remaining":300,"total":300},
     *                               "add_on_quota":{"remaining":100,"total":100},
     *                               "plan_tier_name":"Pro Trial"}
     * 返回 user_quota.remaining + add_on_quota.remaining；读不到返回 -1（0 是「真用完了」）。
     */
    suspend fun qoderHeld(
        region: String, token: String, uid: String, machineId: String, proxy: String,
        refreshToken: String = "",
    ): Int = qoderHeldAndUsed(region, token, uid, machineId, proxy, refreshToken).first

    /**
     * 一次请求同时拿 Qoder 的 (剩余, 累计已用)；读不到返回 (-1, -1)。
     *
     * remaining = user_quota.remaining + add_on_quota.remaining
     * used      = user_quota.used      + add_on_quota.used
     */
    suspend fun qoderHeldAndUsed(
        region: String, token: String, uid: String, machineId: String, proxy: String,
        refreshToken: String = "",
        onRenewed: ((token: String, refresh: String) -> Unit)? = null,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (token.isBlank() && refreshToken.isBlank()) return@withContext Pair(-1, -1)
        val base = if (region.trim().lowercase() == "intl") "https://openapi.qoder.sh"
        else "https://openapi.qoder.com.cn"
        val c = clientFor(proxy)

        // BUGS-v2.63-287 P3-2：这份 renew 原来只返回 `String?`，`refresh_token` 连读都没读。
        // 与 [qoder] 里那份（返回 `Pair<String, String>`）逐字同构，只有返回类型不同 ——
        // 余额路径也在续期，所以同样会把新 refresh_token 丢掉。改成同样的 Pair。
        fun renew(rt: String): Pair<String, String>? {
            if (rt.isBlank()) return null
            return try {
                val (code, text) = post(
                    "$base/api/v1/deviceToken/refresh",
                    mapOf("Content-Type" to "application/json", "Accept" to "application/json",
                          "User-Agent" to "Qoder"),
                    JSONObject().put("refresh_token", rt.trim()).toString(), c)
                if (code !in 200..299) return null
                val d = parse(text)
                val nt = d.optString("device_token").ifBlank { d.optString("token") }
                if (nt.isBlank()) null
                else Pair(nt, d.optString("refresh_token").ifBlank { rt.trim() })
            } catch (e: Exception) {
                null
            }
        }

        fun headers(t: String): MutableMap<String, String> {
            val h = mutableMapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer " + t,
                "Cosy-ClientType" to "10",
                "Cosy-Version" to "0.3.3",
                "Cosy-MachineOS" to "windows",
                "User-Agent" to "Qoder",
            )
            if (machineId.isNotBlank()) h["Cosy-MachineId"] = machineId.trim()
            return h
        }

        fun sums(doc: JSONObject): Pair<Int, Int> {
            fun rem(key: String): Int =
                doc.optJSONObject(key)?.optInt("remaining", 0) ?: 0
            fun used(key: String): Int =
                doc.optJSONObject(key)?.optInt("used", 0) ?: 0
            return Pair(rem("user_quota") + rem("add_on_quota"),
                        used("user_quota") + used("add_on_quota"))
        }

        try {
            var tok = token.trim()
            if (tok.isBlank()) {
                val r = renew(refreshToken) ?: return@withContext Pair(-1, -1)
                tok = r.first
                // BUGS-v2.63-287 P3-2：余额路径同样回写新 refresh_token
                if (r.second.isNotBlank() && r.second != refreshToken) {
                    runCatching { onRenewed?.invoke(r.first, r.second) }
                }
            }
            var (code, text) = get("$base/api/v1/me/usage", headers(tok), c)
            if ((code == 401 || code == 403) && refreshToken.isNotBlank()) {
                val r = renew(refreshToken)
                if (r != null) {
                    tok = r.first
                    if (r.second.isNotBlank() && r.second != refreshToken) {
                        runCatching { onRenewed?.invoke(r.first, r.second) }
                    }
                    val again = get("$base/api/v1/me/usage", headers(tok), c)
                    code = again.first
                    text = again.second
                }
            }
            if (code !in 200..299) return@withContext Pair(-1, -1)
            sums(parse(text))
        } catch (e: Exception) {
            Pair(-1, -1)
        }
    }

    /**
     * Qoder 账号昵称（用户 m16091）。读不到返回空串。
     *
     * GET /api/v1/collaboration/users/me → {"success":true,"data":{"nickname":"Dylan",
     *   "displayName":"AYANG23","id":"01a1f7d2-...","avatarUrl":"..."}}
     *
     * 实测要点：
     * · **只能打 openapi.qoder.com.cn**。换 qoder.cn / qoder.com.cn 都返
     *   401 {"code":"UNAUTHORIZED","message":"missing cookie header"} —— 那两个域只认 cookie。
     * · 头与 /api/v1/me/usage 完全一致（Bearer + Cosy-ClientType:10 + UA:Qoder）。
     * · 昵称优先取 nickname（用户自己起的），没有再取 displayName（注册时生成的），
     *   都空就返回空串，由界面回落到「账号 N」。
     */
    suspend fun qoderNickname(
        region: String, token: String, machineId: String, proxy: String,
        refreshToken: String = "",
    ): String = withContext(Dispatchers.IO) {
        if (token.isBlank() && refreshToken.isBlank()) return@withContext ""
        val base = if (region.trim().lowercase() == "intl") "https://openapi.qoder.sh"
        else "https://openapi.qoder.com.cn"
        val c = clientFor(proxy)
        fun headers(t: String): MutableMap<String, String> {
            val h = mutableMapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer " + t,
                "Cosy-ClientType" to "10",
                "Cosy-Version" to "0.3.3",
                "Cosy-MachineOS" to "windows",
                "User-Agent" to "Qoder",
            )
            if (machineId.isNotBlank()) h["Cosy-MachineId"] = machineId.trim()
            return h
        }
        fun pick(text: String): String {
            val doc = parse(text)
            val d = doc.optJSONObject("data") ?: return ""
            val nk = d.optString("nickname", "").trim()
            if (nk.isNotBlank()) return nk
            return d.optString("displayName", "").trim()
        }
        try {
            var tok = token.trim()
            if (tok.isBlank()) {
                // 只有 refreshToken：先换一次 dt-
                val (rc, rt) = post(
                    "$base/api/v1/deviceToken/refresh",
                    mapOf("Content-Type" to "application/json", "Accept" to "application/json",
                          "User-Agent" to "Qoder"),
                    JSONObject().put("refresh_token", refreshToken.trim()).toString(), c)
                if (rc !in 200..299) return@withContext ""
                val d = parse(rt)
                tok = d.optString("device_token").ifBlank { d.optString("token") }
                if (tok.isBlank()) return@withContext ""
            }
            val path = "/api/v1/collaboration/users/me"
            var (code, text) = get("$base$path", headers(tok), c)
            if ((code == 401 || code == 403) && refreshToken.isNotBlank()) {
                val (rc, rt) = post(
                    "$base/api/v1/deviceToken/refresh",
                    mapOf("Content-Type" to "application/json", "Accept" to "application/json",
                          "User-Agent" to "Qoder"),
                    JSONObject().put("refresh_token", refreshToken.trim()).toString(), c)
                if (rc in 200..299) {
                    val d = parse(rt)
                    val nt = d.optString("device_token").ifBlank { d.optString("token") }
                    if (nt.isNotBlank()) {
                        val again = get("$base$path", headers(nt), c)
                        code = again.first
                        text = again.second
                    }
                }
            }
            if (code !in 200..299) return@withContext ""
            pick(text)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Trae 账号资料（用户 m16091 昵称 / m16555 手机号）。读不到的字段留空串。
     *
     * POST /cloudide/api/v3/trae/GetUserInfo
     *   头：Content-Type: application/json + **x-cloudide-token: <token>**
     *   体：{"ReqSource":"IDE","IDEVersion":"1.107.1","GetUIDFusionInfo":true}
     *   → {"Result":{"ScreenName":"用户157791","NonPlainTextMobile":"166******71",
     *                "UserID":"190628166637546","AvatarUrl":"...",...}}
     *
     * 三个实测要点（都踩过）：
     * · **只认 `x-cloudide-token`**：带 `Authorization: Cloud-IDE-JWT` 返
     *   401 {"Error":{"Code":"20310","message":"The user is not logged in"}}；
     *   带假 token 的 `x-cloudide-token` 返 20101「Token 无效」—— 说明这个头是被读取的。
     *   即签到（Cloud-IDE-JWT）与取用户信息是两套凭据。
     * · 昵称字段叫 `ScreenName`，不是 nickname/displayName。
     * · 手机号字段叫 `NonPlainTextMobile`，**上游只给打码串**（166******71），
     *   中间 6 位用星号替换 —— 这是官方行为，不是我们处理出来的。用户要的就是
     *   「带星号一起显示」，所以原样存、原样显示。
     */
    suspend fun traeProfile(token: String, deviceId: String, global: Boolean): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext Pair("", "")
            val base = if (global) TRAE_INTL else TRAE_CN
            val c = client
            val body = JSONObject().apply {
                put("ReqSource", "IDE")
                put("IDEVersion", TRAE_IDE_VERSION)
                put("GetUIDFusionInfo", true)
            }.toString()
            val h = mutableMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "x-cloudide-token" to token.trim(),
                "User-Agent" to TRAE_UA,
            )
            if (deviceId.isNotBlank()) h["x-device-id"] = deviceId.trim()
            try {
                val (code, text) = post("$base/cloudide/api/v3/trae/GetUserInfo", h, body, c)
                if (code !in 200..299) return@withContext Pair("", "")
                val r = parse(text).optJSONObject("Result") ?: return@withContext Pair("", "")
                Pair(
                    r.optString("ScreenName", "").trim(),
                    r.optString("NonPlainTextMobile", "").trim(),
                )
            } catch (e: Exception) {
                Pair("", "")
            }
        }

    /**
     * MiniMax 当前持有积分（只读，不签到）。
     * GET /minimax-cloud/api/v1/credit/details → {"details":[{"remaining_amount":"388.50",...}],
     *                                              "total_count":1,"base_resp":{"status_code":0}}
     * 注意：这个接口没有 data 信封，也不能带 page/page_size（实测带参数反而 400）。
     */
    suspend fun minimaxHeld(
        token: String, userId: String, deviceId: String, proxy: String,
    ): Int = minimaxHeldAndUsed(token, userId, deviceId, proxy).first

    /**
     * 一次请求同时拿 MiniMax 的 (剩余, 累计已用)；读不到返回 (-1, -1)。
     * remaining = Σ remaining_amount，used = Σ consumed_amount。
     */
    suspend fun minimaxHeldAndUsed(
        token: String, userId: String, deviceId: String, proxy: String,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Pair(-1, -1)
        val c = clientFor(proxy)
        try {
            val (sb, now) = mmParams(userId, token, deviceId)
            val path = "/minimax-cloud/api/v1/credit/details"
            val h = mmSignHeaders(token, path, sb, now, "")
            val (code, text) = get("https://agent.minimax.cn$path?$sb", h, c)
            if (code !in 200..299) return@withContext Pair(-1, -1)
            val doc = parse(text)
            val arr = doc.optJSONArray("details") ?: return@withContext Pair(-1, -1)
            var sum = 0.0
            var usedSum = 0.0
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                sum += d.optString("remaining_amount").toDoubleOrNull() ?: 0.0
                usedSum += d.optString("consumed_amount").toDoubleOrNull() ?: 0.0
            }
            Pair(sum.toInt(), usedSum.toInt())
        } catch (e: Exception) {
            Pair(-1, -1)
        }
    }

    /**
     * MiniMax 签名请求的公共 query。
     *
     * 每个请求都要重新算 `unix`，所以不能缓存；`uuid` / `device_id` 由
     * [MmFingerprint] 从账号凭据确定性派生（同一账号永远同一串，换机不变）。
     *
     * 抽成对象级函数是因为 `minimax()` / `minimaxHeldAndUsed()` / `minimaxProbe()`
     * 三处都要发同一套签名 —— 以前各自抄一份，改一处忘一处就会出现
     * 「签到能过、查余额 401」这种诡异现象。
     */
    private fun mmParams(userId: String, token: String, deviceId: String): Pair<String, Long> {
        val now = System.currentTimeMillis()
        val q = linkedMapOf(
            "device_platform" to "web", "biz_id" to "3", "app_id" to "3001",
            "version_code" to MM_APP_VERSION_CODE, "unix" to now.toString(),
            "timezone_offset" to "28800", "is_desktop" to "1",
            "desktop_version" to MM_DESKTOP_VERSION, "sys_language" to "zh", "lang" to "zh",
            "uuid" to MmFingerprint.uuid(userId, token),
            "device_id" to MmFingerprint.deviceId(deviceId, userId, token),
            "os_name" to "Windows", "browser_name" to "Chrome", "device_memory" to "16",
            "cpu_core_num" to "20", "browser_language" to "zh-CN",
            "browser_platform" to "Win32", "user_id" to userId.ifBlank { "0" },
            "screen_width" to "1920", "screen_height" to "1080", "client" to "desktop",
        )
        val sb = StringBuilder()
        q.forEach { (k, v) ->
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(java.net.URLEncoder.encode(k, "UTF-8")).append('=')
                .append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        return Pair(sb.toString(), now)
    }

    /**
     * MiniMax 签名头：`yy` + `x-timestamp` + `x-signature` + token 双通道。
     *
     * 认证头按 token 形态二选一：
     *  · 桌面客户端 token（`mmoat_` 开头）→ 服务端认 `Authorization: Bearer`
     *  · 网页端 `localStorage['_token']`（裸串，无前缀）→ 只认裸 `token` 头
     * 以前无条件两个都发，对网页 token 来说 Bearer 是无效值，有的节点直接按 401 处理。
     */
    private fun mmSignHeaders(
        token: String, path: String, query: String, nowMs: Long, body: String,
    ): Map<String, String> {
        val second = nowMs / 1000
        val full = "$path?$query"
        val enc = java.net.URLEncoder.encode(full, "UTF-8").replace("+", "%20")
        val yy = md5(enc + "_" + body.ifBlank { "{}" } + md5(nowMs.toString()) + MM_YY_SUFFIX)
        return mapOf(
            "User-Agent" to MM_UA,
            "Origin" to "https://agent.minimax.cn",
            "Referer" to "https://agent.minimax.cn/",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "yy" to yy,
            "x-timestamp" to second.toString(),
            "x-signature" to md5(second.toString() + MM_SIGNATURE_SALT + body),
            "token" to token.trim(),
        ) + if (token.trim().startsWith("mmoat_")) {
            mapOf("Authorization" to "Bearer " + token.trim())
        } else emptyMap()
    }

    /**
     * 只读校验：这份 token 到底是不是 MiniMax 认的 accessToken。
     *
     * 返回三态（实测依据见下）：
     *  · `true`  —— 服务端认（HTTP 2xx + `base_resp.status_code == 0` + 有 `data`）
     *  · `false` —— 服务端明确不认（HTTP 401）
     *  · `null`  —— 网络不通 / 5xx / 其它，问不出来
     *
     * **实测判据（2026-11-21，直接打接口）**：
     * ```
     * 签名正确 + 假 token   → 401 （body 可能为空）
     * 签名算错             → 400 {"error":"invalid signature"}
     * yy 算错              → 401 （该端点不严格校验 yy）
     * ```
     * 所以 **401 就是「token 无效」**，而 400 是「我的签名错了」——
     * 两者绝不能混为一谈，否则签名 bug 会被误报成「token 过期」，用户反复重登也没用。
     *
     * 为什么需要它（用户 m13107）：`LoginWebActivity` 在**登录页**上扫 localStorage，
     * 会把 csrf / 埋点长串当成 token 存下来。`looksLikeMmToken()` 只能判「长得像」，
     * 判不了「服务端认不认」—— 于是用户刚点完「获取验证码」、人机验证还没出来，
     * 页面就被 `finish()` 关掉了，之后签到必然 401（截图里 accessToken 显示
     * `119***98`，那是个半截值）。
     *
     * 只读接口（`signin/status`），不会触发签到，可以放心在登录流程里反复调。
     */
    suspend fun minimaxProbe(
        token: String, userId: String, deviceId: String, proxy: String,
    ): Boolean? = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        val c = clientFor(proxy)
        try {
            val (query, nowMs) = mmParams(userId, token, deviceId)
            val path = "/minimax-cloud/api/v1/signin/status"
            val h = mmSignHeaders(token, path, query, nowMs, "")
            val (code, text) = get("https://agent.minimax.cn$path?$query", h, c)
            // 400 = 我们自己的签名算错了，不是 token 的问题 —— 报「问不出来」，
            // 让上层别把用户的真 token 当垃圾扔掉。
            if (code == 401 || code == 403) return@withContext false
            if (code !in 200..299) return@withContext null
            val doc = parse(text)
            val sc = doc.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
            if (sc == 0 && doc.optJSONObject("data") != null) return@withContext true
            // 200 但业务码非 0 / 没有 data：401 的节点有时回 200 + {"error":"invalid access token"}
            val err = doc.optString("error", "")
            if (err.contains("token", ignoreCase = true)) return@withContext false
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 小写十六进制 MD5，MiniMax 的 x-signature / yy 都用它。 */
    private fun md5(s: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val dig = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(dig.size * 2)
        dig.forEach { b -> sb.append(String.format("%02x", b)) }
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 华为码道（DevCloud PromptCenter）
    //
    // 协议三步，缺一步都不算签完：
    //   GET  {HW_BASE}/v1/ops/delivery?channel=WEB   查活动（campaignId 每次实时取）
    //   POST {HW_BASE}/v1/ops/claim                  {"campaignId":cid,"idempotentKey":"claim_<cid>_<ts>","channel":"WEB"}
    //   POST {HW_BASE}/v1/ops/confirm                {"campaignId":cid}
    //
    // 头组是硬要求：缺任何一个，华为就把请求当普通页面访问、回 SPA 的 HTML 而不是
    // JSON —— 表现是「解析失败」而不是 401，排查时极具误导性。`cftk` 是 CSRF 令牌，
    // 值来自 cookie `devclouddevuibjtcftk`。
    // ═══════════════════════════════════════════════════════════════════════

    private const val HW_BASE = "https://devcloud.cn-north-4.huaweicloud.com/chat/PromptCenterService"
    private const val HW_HOME = "https://devcloud.cn-north-4.huaweicloud.com/chat/home"
    private const val HW_DELIVERY = "/v1/ops/delivery?channel=WEB"
    private const val HW_CLAIM = "/v1/ops/claim"
    private const val HW_CONFIRM = "/v1/ops/confirm"

    // 积分余额：**就在签到同域**，用同一份 cookie。
    // 口径来自码道前端 bundle（index-B1jcQCDE.js）里的 packageInfoStore：
    //
    //   async function Soe(){
    //     const e = await Gt.get({url:"/snap-manager/v1/statistics/plugin"})...;
    //     let t; try { t = await Gt.get({url:"/snap-manager/v1/package/overview"})... } catch {}
    //     return Vkt(e, t)
    //   }
    //   function Vkt(e,t){ const n=e.package, r=Hkt(e.metrics); ... }
    //
    // 关键：metrics[] 来自 **statistics/plugin**（第一个请求）；package/overview
    // 只提供 model_package（按需 token 包）。只打 overview 永远读不到
    // usageTotalPackageCredit —— 那个响应里根本没有 metrics 字段。
    //
    // metrics[] 里 name == "usageTotalPackageCredit" 的那一项：
    //   package_credit_remain           可用积分
    //   package_credit_used / _amount   已用 / 总额
    //   package_credit_expiring_amount  未来 7 天内到期
    //
    // 响应是扁平结构（没有 data 信封）：{package:{...}, metrics:[...], show:{...}}。
    // 注意 `codearts.huaweicloud.com/portal` 只是「打开个人用量页」的跳转链接，
    // 不是接口 —— 之前绕道去采门户 cookie 是白跑一趟（门户未登录时只回 200+0 字节）。
    private const val HW_CHAT_BASE = "https://devcloud.cn-north-4.huaweicloud.com/chat"
    private const val HW_SNAP_STATS = "/snap-manager/v1/statistics/plugin"
    private const val HW_SNAP_OVERVIEW = "/snap-manager/v1/package/overview"
    private const val HW_CREDIT_METRIC = "usageTotalPackageCredit"

    // 旧的门户口径，仅作兜底（需要单独一份 codearts cookie）
    private const val HW_PORTAL_BASE = "https://codearts.huaweicloud.com"
    private const val HW_PORTAL_OVERVIEW = "/portal/snap-manager/v1/package/overview"
    private const val HW_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0"
    // 状态里算「今天已经签过了」的那几个值。码道前端有两套写法，都要认：
    //   YMt = ["CLAIMED","CONFIRMED","CONSUMED"]           （活动卡片判据 jMt）
    //   __(I) => I==="CLAIMED"||I==="CONFIRM"||I==="CONSUME"（Workbench 判据，单数）
    // 只认复数写法时，若服务端返单数就永远判不出「已签」，
    // 症状是「明明签了，软件还显示签到入口」。
    private val HW_DONE_STATES = listOf("CLAIMED", "CONFIRMED", "CONSUMED", "CONFIRM", "CONSUME")
    // 活动已结束（卖完 / 窗口关闭）—— 竞答类活动的终态。
    private val HW_ENDED_STATES = listOf("SOLD_OUT", "WINDOW_ENDED")
    // 任务型活动（要做任务才可领）的 taskMode 取值。
    private val HW_TASK_MODES = listOf("CONTINUOUS", "NON_CONTINUOUS")
    // 每日签到活动的 type。前端 w_() 同时认大写与 snake 写法。
    //
    // ⚠️ **USER_LOGIN 就是「每日签到领积分」本身**（v2.50 纠正 v2.42 的误判）。
    // 用户 m25808 的实机红字是 `type=USER_LOGIN、campaignId=34`，而码道客户端里
    // 这一项的名字正是「每日签到领 10…积分」。v2.42 时我把它当成「新用户登录礼」
    // 并刻意降级 —— 那个结论来自营销文案 `loginPromptText`（「新用户登录即送 4000
    // 积分，每日签到可领取 1000 积分」）的猜测，不是证据。华为是把「登录」当成
    // 签到任务的动作来登记活动的，type 叫什么与「能不能天天领」无关。
    private val HW_DAILY_TYPES = listOf(
        "DAILY_CLAIM", "DAILY_CHECKIN", "DAILY_CHECK_IN", "DAILY_SIGN_IN",
        "USER_LOGIN", "USER_LOGIN_DAILY"
    )
    // 单次最多领几个 benefit（防 claimActions 异常长时打爆上游）
    private const val HW_MAX_BENEFITS = 6
    // 事件上报端点。码道前端进站第一件事就是 o7t()：
    //   POST /ops/events/trigger {triggerEvent:"user.login", channel:"WEB",
    //                             idempotentKey:"login-<ymd>-<rand>"}
    // 用 localStorage["lastLoginReportDate"] 保证一天只报一次。
    // 服务端靠这个事件把 USER_LOGIN 类活动推到 ELIGIBLE —— 我们从来不发，
    // 所以这类活动在我们眼里可能一直不可领（或领了不翻状态）。
    private const val HW_EVENTS = "/v1/ops/events/trigger"
    private const val HW_LOGIN_EVENT = "user.login"

    /**
     * `campaign.read` 事件（对齐码道前端 `QPt()`）。
     *
     * 前端在活动列表**被渲染出来**时发（RedDot chunk 的 `markRead()`）：
     *
     * ```js
     * body: { triggerEvent: "campaign.read", readCampaignIds: a,
     *         idempotentKey: `read_${a.join("_")}`, channel: t }
     * ```
     *
     * 码道的任务型活动靠「读过」推进 `taskStatus`，不发这个事件时 `taskStatus`
     * 可能一直停在空/IN_PROGRESS，claim 就成了空操作 —— 用户 m26306 那条
     * 「status=ELIGIBLE taskStatus=- claimable=true」正是这个形态。
     * `idempotentKey` 只由 cids 决定（不含时间戳），重复发是幂等的。
     */
    private const val HW_READ_EVENT = "campaign.read"

    /**
     * 任务进度端点。
     *
     * 码道前端的 `taskMode` / `taskStatus` / `taskCurrent` / `taskThreshold` /
     * `benefitStatus` **不在 delivery 里**，而是从
     * `GET /v1/ops/tasks/conversation?channel=WEB` 单独取回，再按 `campaignId`
     * 合并进活动对象（前端 `p_` 那个 computed）：
     *
     * ```js
     * const p_ = computed(() => u.value.map(I => {
     *   const F = y_(I), j = p.value[F.id];
     *   return j && (F.taskCurrent = j.current, F.taskThreshold = j.threshold,
     *                F.taskMode = j.mode, F.taskBenefitStatus = j.benefitStatus,
     *                F.taskNeedRemindToday = j.needRemindToday,
     *                F.taskStatus = j.taskStatus), F
     * }));
     * ```
     *
     * 只读 delivery 的话 `taskStatus` 恒空，[hwClaimed] 的任务型分支永远为假
     * —— 用户 m26306 那条「taskStatus=- claimable=true」就是这么来的。
     */
    private const val HW_TASKS = "/v1/ops/tasks/conversation"

    /** confirm 之后回读状态的重试次数与间隔（码道任务状态是异步结算的）。 */
    private const val HW_READBACK_RETRIES = 3
    private const val HW_READBACK_GAP_MS = 1500L
    private val HW_SESSION_NAMES = listOf("devclouddevuibjJ_SESSION_ID", "J_SESSION_ID")
    // CSRF 令牌的 cookie 名 —— 逐字对齐首页 HTML 里硬写的那个：
    //
    //   window['cftk_cookie_key_cf2'] = 'devclouddevuibjtcftk';
    //
    // v2.57 及之前写成 devclouddevuibtcftk（**少一个 j**），于是 cftk 头恒空、
    // 服务端回 csrf validate fail.（用户 m27543）。这个前缀与
    // devclouddevuibjJ_SESSION_ID 相同，少一个字母肉眼极难发现。
    private const val HW_CFTK_COOKIE = "devclouddevuibjtcftk"

    // 兜底候选：按序找，第一个非空的胜出（老部署可能用旧写法或裸 cftk）
    private val HW_CFTK_CANDIDATES = listOf(
        "devclouddevuibjtcftk",
        "devclouddevuibtcftk",
        "cftk",
    )

    /**
     * cookie 里是否带了华为会话标识。
     *
     * **只代表「华为发过一份会话」，不代表「已经登录」。**
     * 华为对未登录访客也会下发匿名 `J_SESSION_ID`（用户 m22649：刚打开码道首页、
     * 连手机号都没输，就被判成「已检测到登录，正在采集」并写进了配置）。
     * 要判断「真的登录了」必须用 [hwProbe] 打一次业务接口。
     */
    fun hwHasSession(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        return HW_SESSION_NAMES.any { cookie.contains("$it=") }
    }

    /**
     * 用真实业务接口确认这份 cookie 是否已登录。
     *
     * 三态返回：
     *   · `true`  —— 已登录
     *   · `false` —— 未登录 / 会话失效
     *   · `null`  —— 网络异常，判断不了（既不能当成已登录，也不能当成未登录）
     *
     * 为什么不能只看 cookie：华为对未认证访客也会下发**匿名** `J_SESSION_ID`，
     * 所以「cookie 里有会话名」≠「已登录」（用户 m22649：连手机号都没输就提示
     * 「已采集」并写进了配置）。
     *
     * 判定顺序（两条正向证据任一成立就算已登录，避免误伤真登录的用户）：
     *   1. `agencyID` —— 华为只在真正登录后补发这个附属 cookie（参考实现
     *      `huawei_login.js` 同样拿它当「会话收全」的判据）；
     *   2. 打一次 `/v1/ops/delivery`，回的是 JSON（`code`/`data`/`message` 任一）
     *      —— 未登录时华为只会回 401/403、200+0 字节、或 SPA 的 HTML。
     *
     * 注意判据**不是**「code == 0」：`code != 0` 也可能是「已登录但活动下线/换轮次」，
     * 把它当成未登录会逼用户反复重登。
     */
    suspend fun hwProbe(cookie: String): Boolean? = withContext(Dispatchers.IO) {
        if (!hwHasSession(cookie)) return@withContext false
        if (cookie.contains("agencyID=")) return@withContext true
        val (code, text) = hwRequest(HW_BASE + HW_DELIVERY + "&_=" + System.currentTimeMillis(), cookie)
        if (code == 0) return@withContext null
        if (code == 401 || code == 403) return@withContext false
        val t = text.trim()
        if (t.isEmpty()) return@withContext false
        if (!t.startsWith("{")) return@withContext false
        try {
            val doc = JSONObject(t)
            doc.has("code") || doc.has("data") || doc.has("message")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 门户 Cookie 里是否有真正的会话（`SessionID`）。
     *
     * 不能只看「cookie 非空」：未登录访问 `codearts.huaweicloud.com` 也会下发
     * `HWWAFSESID` / `HWWAFSESTIME`（WAF 层的），而门户对未认证请求返回
     * 200 + 0 字节（不是 401）—— 空壳 cookie 会被误判成「会话可用」，
     * 症状是 `portal_ok=true` 但积分恒为 -1（用户 m21642）。
     * 参考实现 `workbuddy-signin-master/huawei_login.js` 的判据同样是
     * `ca.some(c => c.name === 'SessionID')`。
     */
    fun hwHasPortalSession(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        cookie.split(";").forEach { raw ->
            if (raw.trim().split("=", limit = 2)[0].trim() == "SessionID") return true
        }
        return false
    }

    /**
     * Cookie 里有没有 CSRF 令牌（`cftk`）。
     *
     * 供 `LocalApi` 的体检面板用 —— 以前它自己写死了 `contains("devclouddevuibtcftk=")`，
     * 那个名字少一个 `j`（v2.57 及之前的错误），于是体检永远报「缺 CSRF 令牌」，
     * 而实际签到那边也一样取不到值，症状是 `csrf validate fail.`。
     * 现在统一走这里，只有一处真相。
     */
    fun hwHasCftk(cookie: String): Boolean = hwCftk(cookie).isNotBlank()

    /**
     * 从 cookie 串里取 CSRF 令牌（`cftk` 头的值）。
     *
     * 两条都必须照抄前端，否则服务端回 `csrf validate fail.`：
     *
     * 1. **cookie 名逐字对齐** —— 前端 `tae.getItem(t)` 按名精确比对，
     *    首页 HTML 里写的是 `devclouddevuibjtcftk`（前缀 `devclouddevuibj`，
     *    少一个 `j` 就永远取不到）。v2.57 及之前写成了 `devclouddevuibtcftk`，
     *    于是 `cftk` 头恒空 → 服务端直接 `csrf validate fail.`（用户 m27543）。
     * 2. **值要 URL 解码** —— 前端写 cookie 时 `encodeURIComponent`，读回来
     *    `decodeURIComponent`。令牌里常含 `%2B`(`+`)、`%2F`(`/`)，不解码等于发错令牌。
     */
    private fun hwCftk(cookie: String): String {
        if (cookie.isBlank()) return ""
        val pairs = LinkedHashMap<String, String>()
        cookie.split(";").forEach { raw ->
            val part = raw.trim()
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            pairs[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        for (name in HW_CFTK_CANDIDATES) {
            val raw = pairs[name] ?: ""
            if (raw.isBlank()) continue
            val out = hwPctDecode(raw)
            if (out.isNotBlank()) return out
        }
        return ""
    }

    /**
     * 只解 `%XX`，**不把 `+` 当空格** —— 等价于 JS 的 `decodeURIComponent`。
     *
     * 不能用 `java.net.URLDecoder.decode`：那是 `application/x-www-form-urlencoded`
     * 的解码器，会把 `+` 转成空格。CSRF 令牌是 base64 风格的字符串，`+` 是
     * 合法字符，一转空格就等于发了个错令牌，服务端照样回 `csrf validate fail.`
     * 而症状和「取不到令牌」完全一样，极难查。
     */
    private fun hwPctDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    out.append(v.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        // 多字节 UTF-8 会被拼成一串字节，这里按 UTF-8 重新组装；
        // 令牌通常是 ASCII，走不到这条分支。
        val bytes = out.toString().toByteArray(Charsets.ISO_8859_1)
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            out.toString()
        }
    }

    /**
     * PromptCenterService（签到）的头组 —— 逐字对齐码道前端的 `lo()`：
     *
     *   const xs = {"Content-Type":"application/json",
     *               "x-requested-with":"XMLHttpRequest",
     *               "x-codearts-doer-scenario-type":"web"};
     *   function lo(){ return {...xs, "Agent-Type":"PromptCenter", "X-Language": t}; }
     *
     * 以前漏了 `x-codearts-doer-scenario-type: web`，且头名写成小写 `agent-type`。
     * HTTP 头名本不区分大小写，但华为网关会按原样参与风控打分，照抄前端最稳。
     */
    private fun hwHeaders(cookie: String, withBody: Boolean): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to HW_UA,
            "Agent-Type" to "PromptCenter",
            "x-language" to "zh-cn",
            "language" to "zh-cn",
            "X-Language" to "zh-cn",
            "x-requested-with" to "XMLHttpRequest",
            "x-codearts-doer-scenario-type" to "web",
            "accept" to "application/json, text/plain, */*",
            "referer" to HW_HOME,
            "Cookie" to cookie,
        )
        val tk = hwCftk(cookie)
        if (tk.isNotBlank()) h["cftk"] = tk
        if (withBody) h["Content-Type"] = "application/json"
        return h
    }

    /** 调一次华为接口。返回 (HTTP 码, 响应文本)；网络异常返 (0, 错误说明)。 */
    private suspend fun hwRequest(
        url: String,
        cookie: String,
        method: String = "GET",
        body: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url).apply {
                hwHeaders(cookie, body != null).forEach { (k, v) -> header(k, v) }
            }
            if (method == "POST") {
                b.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
            }
            client.newCall(b.build()).execute().use { r ->
                Pair(r.code, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Pair(0, "网络错误：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 调一次华为接口并解析 JSON。
     *
     * 返回三元组 `(doc, err, authFailed)`：`authFailed` 表示「这次失败是登录态问题」
     * （BUGS-v2.48 P2-2）。早先靠 `message.contains("登录态已失效")` 判，但空响应
     * （「多半是登录态不完整」）与非 JSON（「头组不完整或登录态失效」）两条路径
     * 不含那个子串，于是 `HwVerified` 不会被置 false，下一轮照旧派 job、照旧 401。
     * 判据必须是结构化的，不能匹配给人看的文案。
     *
     * **网络错误（code == 0）不算 authFailed** —— 判断不了不等于没登录。
     */
    private suspend fun hwApi(
        path: String,
        cookie: String,
        method: String = "GET",
        body: String? = null,
    ): Triple<JSONObject?, String, Boolean> {
        var url = HW_BASE + path
        if (method == "GET") {
            val sep = if (url.contains("?")) "&" else "?"
            url = url + sep + "_=" + System.currentTimeMillis()
        }
        val (code, text) = hwRequest(url, cookie, method, body)
        if (code == 0) return Triple(null, text, false)
        if (code == 401 || code == 403) {
            return Triple(
                null,
                "华为登录态已失效（HTTP $code）：会话 Cookie 静置 30~60 分钟就会过期，请重新「登录华为码道」抓一次",
                true,
            )
        }
        val t = text.trim()
        if (t.isEmpty()) {
            return Triple(null, "华为接口返回空响应（HTTP $code）：多半是登录态不完整", true)
        }
        if (!t.startsWith("{") && !t.startsWith("[")) {
            return Triple(
                null,
                "华为接口返回的不是 JSON（HTTP $code）：头组不完整或登录态失效。响应前 80 字符：${t.take(80)}",
                true,
            )
        }
        return try {
            val doc = JSONObject(t)
            // CSRF 失败是最容易误判的一种：HTTP 200、JSON 合法、只有一句英文。
            // 单看它像「业务失败」，实际是「我们没把 cftk 头带对」——
            // 翻译成人话，否则用户只看到 csrf validate fail. 以为是华为封号了。
            val errText = doc.optString("error", "") + " " + doc.optString("message", "")
            if (errText.lowercase().contains("csrf")) {
                val tk = hwCftk(cookie)
                val why = if (tk.isBlank()) {
                    "CSRF 令牌没取到（csrf validate fail.）：采集到的 Cookie 里没有 $HW_CFTK_COOKIE。" +
                        "华为把 CSRF 令牌放在这个 cookie 里，缺它任何写操作都会被拒。" +
                        "请重新「登录华为码道」采集一次；若反复出现请把这条发给我"
                } else {
                    "CSRF 校验失败（csrf validate fail.）：Cookie 里的 $HW_CFTK_COOKIE 已失效或与会话不匹配。" +
                        "华为的 CSRF 令牌跟会话一起过期，请重新「登录华为码道」采集一次"
                }
                return Triple(null, why, false)
            }
            Triple(doc, "", false)
        } catch (e: Exception) {
            Triple(null, "华为接口返回的 JSON 解析失败：${e.message}（前 80 字符：${t.take(80)}）", false)
        }
    }

    /**
     * 这个响应算不算成功 —— 逐字对齐码道前端的 `$s()`（v2.54 修正）。
     *
     * ```js
     * function $s(e){
     *   return e.code===0 || e.code===200 || (e.data!==undefined && e.data!==null)
     *     ? {success:!0, data:e.data}
     *     : {success:!1, error:e.message||("error_code="+e.code), code:e.code};
     * }
     * ```
     *
     * ⚠️ v2.53 及之前判的是 `optInt("code", 0) == 0` —— `optInt` 在字段缺失时
     * 返回默认值 0，**等于「没有 code 字段就一律当成功」**。码道业务失败时经常返回
     * `{"success": false, "error": "..."}` 这种没有 `code` 的形状，于是我们把
     * 「领取失败」当成「领取成功」，接着去 confirm、去回读，状态当然一动不动，
     * 最后报出一句「领取接口返回成功，但活动仍未翻状态」—— 用户 m26896 那条文案。
     *
     * 现在三条都要求：`code` 显式为 0 / 200，或者 `data` 非空；
     * `success is False` 直接判失败（比前端更严，因为我们要据此决定是否写今日标记）。
     */
    private fun hwRespOk(doc: JSONObject?): Boolean {
        if (doc == null) return false
        if (doc.has("success") && !doc.optBoolean("success", true)) return false
        if (!doc.has("code")) return !doc.isNull("data") && doc.opt("data") != null
        val code = doc.optInt("code", -1)
        if (code == 0 || code == 200) return true
        return false
    }

    /** 把失败响应压成一句给人看的原因。 */
    private fun hwRespErr(doc: JSONObject?): String {
        if (doc == null) return "响应为空"
        val code = if (doc.has("code")) doc.opt("code")?.toString() else null
        var msg = doc.optString("message", "")
        if (msg.isBlank()) msg = doc.optString("msg", "")
        if (msg.isBlank()) {
            val err = doc.opt("error")
            msg = when (err) {
                is JSONObject -> err.optString("message", err.optString("code", ""))
                is String -> err
                else -> ""
            }
        }
        msg = msg.take(140)
        return when {
            code == null -> msg.ifBlank { "业务失败（响应没有 code 字段）" }
            msg.isBlank() -> "code=$code"
            else -> "code=$code $msg"
        }
    }

    /**
     * 这个活动**还能不能领**？—— 逐字对齐码道前端 `GPt()`。
     *
     * ```js
     * const ELIGIBLE="ELIGIBLE", DONE=["CLAIMED","CONFIRMED","CONSUMED"], ENDED=["SOLD_OUT","WINDOW_ENDED"];
     * function Uoe(e){ return (e??[]).some(t=>t.status==="ELIGIBLE") }   // 子项还有可领
     * function GPt(e){                                                   // true = 已领过 / 不可领
     *   return Uoe(e.claimActions) ? !1
     *     : $oe(e) ? (!!e.status && ENDED.includes(e.status))
     *     : (e.taskMode==="CONTINUOUS"||e.taskMode==="NON_CONTINUOUS")
     *         ? (e.taskStatus==="COMPLETED" && e.claimable===!1)
     *         : (e.status!==null && e.status!==void 0 && e.status!=="ELIGIBLE");
     * }
     * ```
     *
     * 这是 v2.50 修的第二个根因：以前只看 `status in HW_DONE_STATES`，
     * 而 `status` 会一直停在 `ELIGIBLE`（用户 m25808 的截图就是这样），
     * 真正的「已领」证据藏在另外两个地方：
     *
     * 1. **有 claimActions** —— 只要子项里还有一个 ELIGIBLE 就没领完，全部非
     *    ELIGIBLE 才算领完。这种情况下父活动的 `status` 不可信。
     * 2. **任务型（taskMode）** —— 看 `taskStatus == COMPLETED` 且 `claimable === false`。
     * 3. 其余（普通活动）才回落到 `status` 判据。
     *
     * 返回 true 表示「已领完 / 不可再领」。
     */
    private fun hwClaimed(camp: JSONObject?): Boolean {
        if (camp == null) return false
        val acts = camp.optJSONArray("claimActions")
        if (acts != null && acts.length() > 0) {
            for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                if (a.optString("status", "").uppercase() == "ELIGIBLE") return false
            }
            return true
        }
        val mode = camp.optString("taskMode", "").uppercase()
        if (HW_TASK_MODES.contains(mode)) {
            return camp.optString("taskStatus", "").uppercase() == "COMPLETED" &&
                camp.has("claimable") && !camp.optBoolean("claimable", true)
        }
        val state = camp.optString("status", "").uppercase()
        if (state.isBlank()) return false
        return state != "ELIGIBLE"
    }

    /** 能不能领 —— [hwClaimed] 的否定，外加「活动已结束」也排除。 */
    private fun hwClaimable(camp: JSONObject?): Boolean {
        if (camp == null) return false
        if (hwClaimed(camp)) return false
        return !HW_ENDED_STATES.contains(camp.optString("status", "").uppercase())
    }

    /**
     * 从活动列表里挑出**每日签到**活动。
     *
     * 判据（对齐码道前端 `GPt()` / `Uoe()` 与用户实机看到的列表）：
     *
     * 1. `type` 属于 [HW_DAILY_TYPES]（已含 USER_LOGIN）的优先；
     * 2. 同一优先级内优先**还能领**的（[hwClaimable]），而不是只比 status == ELIGIBLE；
     * 3. 再退到「全表唯一还能领的那个」；
     * 4. 最后 `campaignId == 1`（2026-10 之前的老轮次写法）。
     */
    private fun hwFindDaily(items: List<JSONObject>): JSONObject? {
        fun pick(pool: List<JSONObject>): JSONObject? =
            pool.firstOrNull { hwClaimable(it) } ?: pool.firstOrNull()

        pick(items.filter { HW_DAILY_TYPES.contains(it.optString("type", "").uppercase()) })
            ?.let { return it }
        val alive = items.filter { hwClaimable(it) }
        if (alive.size == 1) return alive[0]
        items.firstOrNull { it.optInt("campaignId", -1) == 1 }?.let { return it }
        return null
    }

    /**
     * 按 campaignId 从列表里取回**同一个**活动。
     *
     * 回读必须按 id 定位，不能重新跑 [hwFindDaily]：签完之后原活动变成 CLAIMED，
     * 而列表里另一个同类活动（登录礼 / 学生认证）仍是 ELIGIBLE，重挑就会挑到
     * 别人身上 —— 症状正是「明明签成了，却报状态仍是 ELIGIBLE」。
     */
    private fun hwFindById(items: List<JSONObject>, cid: Int): JSONObject? =
        items.firstOrNull { it.optInt("campaignId", -1) == cid }

    /**
     * `hwDeliveryAll` 的结果。
     *
     * 早先返回 `Triple<活动, 全量列表, 错误>`，加不进第四个字段，于是「这次失败是不是
     * 登录态问题」（BUGS-v2.48 P2-2）在往上传递时丢掉了。单独一个类比 `Triple` 清楚，
     * 也免得以后再塞字段时又改一遍所有调用点。
     */
    private data class HwDelivery(
        val camp: JSONObject?,
        val all: List<JSONObject>,
        val err: String,
        /** 这次失败是登录态问题（401/403 / 空响应 / 非 JSON），可以据此把账号判成未登录。 */
        val authFailed: Boolean = false,
    )

    /** 查活动列表。 */
    private suspend fun hwDeliveryAll(cookie: String): HwDelivery {
        if (!hwHasSession(cookie)) {
            return HwDelivery(null, emptyList(), "未配置华为会话 Cookie（含 devclouddevuibjJ_SESSION_ID）", true)
        }
        val (doc, err, authFail) = hwApi(HW_DELIVERY, cookie)
        if (doc == null) return HwDelivery(null, emptyList(), err, authFail)
        if (!hwRespOk(doc)) {
            return HwDelivery(
                null, emptyList(),
                "华为活动查询失败 ${hwRespErr(doc)}",
                // 业务码非 0：可能是活动下线，也可能是登录态被服务端判无效。
                // 不当作登录态问题 —— 免得把「活动换轮次」误记成「未登录」。
                false,
            )
        }
        val items = doc.optJSONObject("data")?.optJSONArray("items")
        val list = mutableListOf<JSONObject>()
        if (items != null) for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { list.add(it) }
        }
        hwMergeTasks(list, hwFetchTasks(cookie))
        val camp = hwFindDaily(list)
            ?: return HwDelivery(null, list, "未找到每日签到活动（活动可能已下线或换轮次）", false)
        return HwDelivery(camp, list, "")
    }

    /**
     * 取回任务进度，按 campaignId 建索引。
     *
     * 对齐码道前端 `rFt()`（`GET /v1/ops/tasks/conversation?channel=WEB`）。
     * 失败一律返空 Map —— 任务进度只是**补充证据**，拿不到不该阻断签到
     * （delivery 里的 `claimActions` / `status` 仍然可用）。
     */
    private suspend fun hwFetchTasks(cookie: String): Map<String, JSONObject> {
        if (!hwHasSession(cookie)) return emptyMap()
        val (doc, _err, _af) = hwApi(HW_TASKS + "?channel=WEB", cookie)
        if (doc == null) return emptyMap()
        val data = doc.optJSONArray("data")
            ?: doc.optJSONObject("data")?.optJSONArray("items")
            ?: return emptyMap()
        val out = HashMap<String, JSONObject>()
        for (i in 0 until data.length()) {
            val t = data.optJSONObject(i) ?: continue
            val cid = t.opt("campaignId") ?: continue
            out[cid.toString()] = t
        }
        return out
    }

    /**
     * 把任务进度合并进活动对象（原地改）。
     *
     * 逐字对齐码道前端 `p_`：
     * `F.taskCurrent = j.current; F.taskThreshold = j.threshold; F.taskMode = j.mode;
     *  F.taskBenefitStatus = j.benefitStatus; F.taskNeedRemindToday = j.needRemindToday;
     *  F.taskStatus = j.taskStatus;`
     *
     * ⚠️ 最后一个字段是 `j.taskStatus`，**不是 `j.status`**。
     * v2.53 这里写成了 `"status" to "taskStatus"`，而 tasks 接口的对象里
     * 这个字段名就叫 `taskStatus` —— 于是 `t.has("status")` 恒为假，
     * 合并完 `taskStatus` 仍是空，症状与「根本没合并」完全一样
     * （用户 m26896 截图：`taskStatus=-`）。前五个字段名恰好两边同名，
     * 只有这一个不同名，所以肉眼扫过去很难发现。
     */
    private fun hwMergeTasks(items: List<JSONObject>, tasks: Map<String, JSONObject>) {
        if (tasks.isEmpty()) return
        val pairs = arrayOf(
            "current" to "taskCurrent", "threshold" to "taskThreshold",
            "mode" to "taskMode", "benefitStatus" to "taskBenefitStatus",
            "needRemindToday" to "taskNeedRemindToday", "taskStatus" to "taskStatus",
        )
        items.forEach { x ->
            val t = tasks[x.optInt("campaignId", -1).toString()] ?: return@forEach
            pairs.forEach { (src, dst) ->
                if (t.has(src)) x.put(dst, t.opt(src))
            }
        }
    }

    /** 查活动列表。返回 (活动, 错误)。 */
    private suspend fun hwDelivery(cookie: String): Pair<JSONObject?, String> {
        val d = hwDeliveryAll(cookie)
        return Pair(d.camp, d.err)
    }

    /** 活动清单摘要，用于失败时留档（用户发一条红字就能看出有几个活动、哪个是每日签到）。 */
    private fun hwBrief(items: List<JSONObject>): String {
        val sb = StringBuilder()
        items.take(12).forEach { x ->
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append("cid=").append(x.optInt("campaignId", -1))
                .append(" type=").append(x.optString("type", "?"))
                .append(" st=").append(x.optString("status", "?"))
                .append(" amt=").append(x.optInt("benefitAmount", -1))
                .append(" acts=").append(x.optJSONArray("claimActions")?.length() ?: 0)
        }
        return sb.toString()
    }


    /**
     * 上报一次 `user.login` 事件。返回是否成功。
     *
     * 码道前端每次进站都发（`o7t()`，靠 `localStorage["lastLoginReportDate"]`
     * 保证一天一次）。不发的话 `USER_LOGIN` 类活动在服务端可能一直停在
     * 「未达成」，delivery 拿到的 status 就不会是 ELIGIBLE。
     * 事件失败不阻断签到 —— 调用方只当提示，继续往下走。
     */
    private suspend fun hwTriggerLogin(cookie: String): Boolean {
        if (!hwHasSession(cookie)) return false
        val cal = java.util.Calendar.getInstance()
        val ymd = String.format(
            java.util.Locale.US, "%04d%02d%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        val rand = (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val body = JSONObject()
            .put("triggerEvent", HW_LOGIN_EVENT)
            .put("channel", "WEB")
            .put("idempotentKey", "login-$ymd-$rand")
            .toString()
        val (_doc, _e, _af) = hwApi(HW_EVENTS, cookie, "POST", body)
        if (_doc == null) return false
        val ok = hwRespOk(_doc)
        if (ok) hwMarkLoginReported(cookie)
        return ok
    }

    /**
     * 上报 `campaign.read`。返回是否成功。
     *
     * 对齐码道前端 `QPt()`：`idempotentKey` 只由 cids 决定（`read_<ids 排序后 join "_">`），
     * 不带时间戳 —— 重复发是幂等的，服务端只记一条。
     */
    private suspend fun hwTriggerRead(cookie: String, cids: List<Int>): Boolean {
        if (!hwHasSession(cookie)) return false
        val ids = cids.filter { it >= 0 }.distinct().sorted()
        if (ids.isEmpty()) return false
        val body = JSONObject()
            .put("triggerEvent", HW_READ_EVENT)
            .put("readCampaignIds", org.json.JSONArray(ids))
            .put("idempotentKey", "read_" + ids.joinToString("_"))
            .put("channel", "WEB")
            .toString()
        val (doc, _e, _af) = hwApi(HW_EVENTS, cookie, "POST", body)
        return hwRespOk(doc)
    }

    /**
     * 今天是否已经上报过 `user.login`（进程内去重）。
     *
     * 前端用 `localStorage["lastLoginReportDate"]` 去重，**每次页面加载最多发一次**
     * （`vn().isLoggedIn && a7t()`）。`LocalCheckin` 是 `object`、拿不到 Context，
     * 所以这里用进程内变量模拟同样的语义：一次 App 生命周期内只发一次，
     * 用户反复点「测试签到」不会刷出一串事件。
     *
     * 跨进程重启会再发一次 —— 与前端「重开页面就再发一次」完全一致，
     * 服务端只记一条事件，不会把签到判成异常流量。
     */
    @Volatile
    private var hwLoginDay: String = ""

    private fun hwLoginReportedToday(cookie: String): Boolean {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val today = String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        return hwLoginDay == today + "|" + HwAccount.keyOf(cookie)
    }

    /** 记下「今天已上报 user.login」。 */
    private fun hwMarkLoginReported(cookie: String) {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val today = String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        hwLoginDay = today + "|" + HwAccount.keyOf(cookie)
    }

    /**
     * 从 `metrics[]` 里挑出积分那一项并解包。
     *
     * 码道前端（packageInfoStore）用的就是 `name == "usageTotalPackageCredit"`；
     * 其余 metrics 是 token 包（`usageTokenChatMessages` 之类），混用会把
     * token 数当成积分显示。
     */
    private fun hwCreditFromMetrics(metrics: JSONArray?): Int {
        if (metrics == null) return -1
        for (i in 0 until metrics.length()) {
            val m = metrics.optJSONObject(i) ?: continue
            if (m.optString("name", "").trim() != HW_CREDIT_METRIC) continue
            if (!m.has("package_credit_remain")) return -1
            val v = m.optDouble("package_credit_remain", Double.NaN)
            if (v.isNaN()) return -1
            return v.toInt()
        }
        return -1
    }

    /**
     * 读码道积分余额。
     *
     * **主路径就在签到同域**：`GET /chat/snap-manager/v1/statistics/plugin`，
     * 用签到那份 cookie（`devclouddevuibjJ_SESSION_ID`），不需要任何门户会话。
     *
     * 注意：**不能只打 `/package/overview`** —— 那个端点只返回 `model_package`
     * （按需 token 包），响应里没有 `metrics` 字段，读积分必然是空。码道前端
     * 是两个请求合起来的：`statistics/plugin` 出 `metrics[]`，`package/overview`
     * 出 `model_package`。
     *
     * 拿不到再降级到 codearts 门户（需要另一份 cookie）。
     * 读不到返 -1（前端显示「—」），绝不返 0 —— 0 是「真的用完了」。
     */
    suspend fun huaweiHeld(cookie: String, portalCookie: String = ""): Int =
        withContext(Dispatchers.IO) {
            if (!hwHasSession(cookie)) return@withContext -1
            // ── 主路径：签到同域。先 statistics/plugin（前端就是从这里取 metrics），
            //    再试 package/overview —— 万一上游哪天把 metrics 挪过去，两个都打一遍最稳。
            //    头组用 `/snap-manager/*` 那套（码道前端同一个 axios 实例），
            //    **不能带 `agent-type: PromptCenter`**（那是签到接口专用的）。
            val cftk = hwCftk(cookie)
            for (path in listOf(HW_SNAP_STATS, HW_SNAP_OVERVIEW)) {
                try {
                    val url = HW_CHAT_BASE + path + "?_=" + System.currentTimeMillis()
                    val rb = Request.Builder().url(url)
                        .header("User-Agent", HW_UA)
                        .header("language", "zh-cn")
                        .header("x-language", "zh-cn")
                        .header("X-Language", "zh-cn")
                        .header("x-requested-with", "XMLHttpRequest")
                        .header("x-codearts-doer-scenario-type", "web")
                        .header("accept", "application/json, text/plain, */*")
                        .header("referer", HW_HOME)
                        .header("Cookie", cookie)
                    if (cftk.isNotBlank()) rb.header("cftk", cftk)
                    client.newCall(rb.build()).execute().use { r ->
                        if (r.isSuccessful) {
                            val t = r.body?.string() ?: ""
                            if (t.trim().startsWith("{")) {
                                val v = hwCreditFromMetrics(JSONObject(t).optJSONArray("metrics"))
                                if (v >= 0) return@withContext v
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            // ── 兜底：codearts 门户（另一份 cookie）──
            if (hwHasPortalSession(portalCookie)) {
                hwCreditPortal(portalCookie)?.let { pkg ->
                    if (pkg.has("package_credit_remain")) {
                        val v = pkg.optDouble("package_credit_remain", -1.0)
                        if (!v.isNaN() && v >= 0) return@withContext v.toInt()
                    }
                }
            }
            -1
        }

    /** 旧的门户口径（codearts 域，需要单独的 SessionID cookie）。仅作兜底。 */
    private suspend fun hwCreditPortal(portalCookie: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val url = HW_PORTAL_BASE + HW_PORTAL_OVERVIEW + "?_=" + System.currentTimeMillis()
                val b = Request.Builder().url(url)
                    .header("User-Agent", HW_UA)
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("accept", "application/json, text/plain, */*")
                    .header("referer", HW_PORTAL_BASE + "/portal/settings/personal-usage?locale=zh-cn")
                    .header("Cookie", portalCookie)
                    .build()
                client.newCall(b).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val t = r.body?.string() ?: return@withContext null
                    if (!t.trim().startsWith("{")) return@withContext null
                    JSONObject(t).optJSONObject("all_credit_package")
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 华为码道签到。
     *
     * @param cookie 会话 Cookie（必填，含 devclouddevuibjJ_SESSION_ID）
     * @param portalCookie 码道门户 Cookie（可选，只用于回传当前积分）
     */
    suspend fun huawei(cookie: String, portalCookie: String = ""): CheckinResult {
        if (!hwHasSession(cookie)) {
            return CheckinResult(
                "huawei", "fail",
                "缺少华为会话 Cookie：点「登录华为码道」在站内登录一次即可自动采集（Cookie 含 devclouddevuibjJ_SESSION_ID）",
                "",
            )
        }
        val d0 = hwDeliveryAll(cookie)
        val camp0 = d0.camp
        if (camp0 == null) {
            // authFailed 透传（BUGS-v2.48 P2-2）：空响应 / 非 JSON / 401 都属于登录态问题，
            // 上层据此把账号记成「未登录」，别靠匹配中文文案。
            return CheckinResult("huawei", "fail", d0.err, "", authFailed = d0.authFailed)
        }
        // ── 进站第一件事：无条件上报 user.login（对齐前端 a7t()）──
        // 前端是 `vn().isLoggedIn && a7t()`，只靠 localStorage 日期去重，
        // **不看活动状态**。以前我们只在「既不可领也未领」时才发，条件太窄：
        // 用户 m26306 那次 claimable=true 且 taskStatus 为空，事件压根没发，
        // 服务端自然没理由把 USER_LOGIN 任务标成 COMPLETED，于是 claim 200 但状态永不翻。
        var camp = camp0
        var loginOk = false
        if (!hwLoginReportedToday(cookie)) {
            loginOk = hwTriggerLogin(cookie)
            if (loginOk) {
                // 事件刚上报，活动状态可能刚变（尤其 USER_LOGIN 类），重查一次
                val (c2, _e2) = hwDelivery(cookie)
                if (c2 != null) camp = c2
            }
        }
        // 紧接着补 campaign.read（对齐前端 RedDot 的 markRead）：码道的任务型活动
        // 靠「读过」推进 taskStatus，不发的话 claim 是空操作。
        val rcid = camp.optInt("campaignId", -1)
        if (rcid >= 0) hwTriggerRead(cookie, listOf(rcid))
        val cid = camp.optInt("campaignId", -1)
        if (cid < 0) {
            return CheckinResult("huawei", "fail", "每日签到活动缺少 campaignId，无法领取", "")
        }
        val amount = camp.optDouble("benefitAmount", 0.0)
        val unit = camp.optString("benefitUnit", "CREDIT")
        val unitCn = if (unit == "CREDIT") "积分" else unit
        val state = camp.optString("status", "").uppercase()

        // 「今天还能不能领」只信 hwClaimed()（对齐码道前端 GPt()），不看裸 status：
        // status 会一直停在 ELIGIBLE，真正翻的是 claimActions 子项 / taskStatus+claimable。
        if (hwClaimed(camp)) {
            val held = huaweiHeld(cookie, portalCookie)
            val amtTxt = if (amount > 0) amount.toInt().toString() else "?"
            return CheckinResult("huawei", "already", "今日已签到（$amtTxt $unitCn）", "", held)
        }
        if (!hwClaimable(camp)) {
            // 任务型活动且任务没达成时，claim 是空操作（服务端 200 但什么都不改）。
            // 这种情形要说清楚「是任务没完成」，不能让用户以为是网络或登录问题。
            val mode = camp.optString("taskMode", "").uppercase()
            val tstat = camp.optString("taskStatus", "").uppercase()
            if (HW_TASK_MODES.contains(mode) && tstat != "COMPLETED") {
                return CheckinResult(
                    "huawei", "fail",
                    "码道的任务还没达成（taskStatus=${tstat.ifBlank { "-" }}，taskMode=$mode，" +
                        "进度 ${camp.opt("taskCurrent")}/${camp.opt("taskThreshold")}）。" +
                        "这一项要先在码道客户端或网页里完成任务动作才能领；" +
                        "软件已经把能做的（登录事件上报）都发了，" +
                        "若你在客户端里已经做过任务，隔几分钟再试一次", ""
                )
            }
            return CheckinResult(
                "huawei", "fail",
                "签到活动当前不可领取（status=${state.ifBlank { "?" }} " +
                    "taskStatus=${camp.optString("taskStatus", "-")} " +
                    "claimable=${camp.opt("claimable")}）", ""
            )
        }

        val ts = System.currentTimeMillis()

        // 领取目标：有 claimActions 的活动必须**逐个 benefit 领**（码道前端 b(O,V)
        // 就是这么做的，idempotentKey 形如 `rank-claim-<cid>-<benefitId>-<ts>`）；
        // 没有就整活动领一次。只领主活动时，若服务端把「每日签到」拆成子权益，
        // 会出现「领取 200 但状态仍 ELIGIBLE」—— 这正是之前「显示签到成功、实际没签到」的成因。
        val targets = mutableListOf<Int>()
        val acts = camp.optJSONArray("claimActions")
        if (acts != null) {
            for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                if (!a.has("benefitId")) continue
                val bid = a.optInt("benefitId", -1)
                if (bid < 0) continue
                val st = a.optString("status", "").uppercase()
                if (st.isNotBlank() && st != "ELIGIBLE") continue
                targets.add(bid)
                if (targets.size >= HW_MAX_BENEFITS) break
            }
        }

        val claimed = mutableListOf<Int>()
        if (targets.isNotEmpty()) {
            targets.forEach { bid ->
                val body = JSONObject()
                    .put("campaignId", cid)
                    .put("idempotentKey", "claim_${cid}_${bid}_$ts")
                    .put("channel", "WEB")
                    .put("benefitId", bid)
                    .toString()
                val (d, e, af) = hwApi(HW_CLAIM, cookie, "POST", body)
                if (d == null) {
                    return CheckinResult("huawei", "fail", "领取失败（benefitId=$bid）：$e", "", authFailed = af)
                }
                if (!hwRespOk(d)) {
                    return CheckinResult(
                        "huawei", "fail",
                        "领取失败 ${hwRespErr(d)}（benefitId=$bid）", "",
                    )
                }
                claimed.add(bid)
            }
        } else {
            val claimBody = JSONObject()
                .put("campaignId", cid)
                .put("idempotentKey", "claim_${cid}_$ts")
                .put("channel", "WEB")
                .toString()
            val (cDoc, cErr, cAf) = hwApi(HW_CLAIM, cookie, "POST", claimBody)
            if (cDoc == null) return CheckinResult("huawei", "fail", "领取失败：$cErr", "", authFailed = cAf)
            if (!hwRespOk(cDoc)) {
                return CheckinResult(
                    "huawei", "fail",
                    "领取失败 ${hwRespErr(cDoc)}", "",
                )
            }
        }

        val (fDoc, fErr, fAf) = hwApi(
            HW_CONFIRM, cookie, "POST",
            JSONObject().put("campaignId", cid).toString(),
        )
        if (fDoc == null) {
            // claim 成功但 confirm 失败：上游状态已推进到 CLAIMED，钱通常到账，
            // 但按协议「少 confirm 不算签完」→ 报 fail 让用户看到，下次自动补确认。
            return CheckinResult("huawei", "fail", "已领取但确认失败（下次会自动补确认）：$fErr", "", authFailed = fAf)
        }
        if (!hwRespOk(fDoc)) {
            return CheckinResult(
                "huawei", "fail",
                "签到确认失败 ${hwRespErr(fDoc)}", "",
            )
        }

        // 只信回读：confirm 之后重新查一次，确认状态真的翻转了才算成功。
        // **状态没翻就报 fail、不写今日标记** —— 之前在这里也返回 success，
        // 造成「软件显示已签到、码道客户端里还能领」的假成功。
        //
        // 回读必须按 campaignId 定位同一个活动（hwFindById），不能重新 hwFindDaily：
        // 签完之后原活动变成 CLAIMED，而列表里另一个同类活动（登录礼 / 学生认证）
        // 仍是 ELIGIBLE，重挑就会挑到别人身上，照样报「状态仍是 ELIGIBLE」。
        //
        // v2.53 补两件事（用户 m26306）：
        // ① 回读**带重试**：码道任务状态是异步结算的，claim/confirm 立刻回读
        //    经常还是旧值（taskStatus 空、claimable 仍 true）。
        // ② 重试之间**再补一次 user.login**：前端 a7t() 只在进站发一次，
        //    我们多发一次是幂等的（idempotentKey 带随机后缀）。
        var d2 = hwDeliveryAll(cookie)
        var same = hwFindById(d2.all, cid)
        var state2 = (same?.optString("status", "")
            ?: d2.camp?.optString("status", "") ?: "").uppercase()
        var attempt = 0
        while (attempt < HW_READBACK_RETRIES) {
            if (same != null && hwClaimed(same)) break
            if (same == null && HW_DONE_STATES.contains(state2)) break
            if (attempt >= HW_READBACK_RETRIES - 1) break
            kotlinx.coroutines.delay(HW_READBACK_GAP_MS)
            if (attempt == 0 && !loginOk) hwTriggerLogin(cookie)
            d2 = hwDeliveryAll(cookie)
            same = hwFindById(d2.all, cid)
            state2 = (same?.optString("status", "")
                ?: d2.camp?.optString("status", "") ?: "").uppercase()
            attempt++
        }
        val held = huaweiHeld(cookie, portalCookie)
        val gained = if (amount > 0) "+${amount.toInt()}" else ""
        // 成功判据同 hwClaimed()：不能只看 status（见 hwClaimed 的注释）。
        if (same != null && hwClaimed(same)) {
            return CheckinResult("huawei", "success", "签到成功 $gained$unitCn", gained, held)
        }
        if (same == null && HW_DONE_STATES.contains(state2)) {
            return CheckinResult("huawei", "success", "签到成功 $gained$unitCn", gained, held)
        }
        if (d2.err.isNotBlank()) {
            return CheckinResult(
                "huawei", "fail",
                "领取与确认都返回成功，但回读失败无法证实：${d2.err.take(80)}", "",
                authFailed = d2.authFailed,
            )
        }
        val acts2 = same?.optJSONArray("claimActions")
        var pending = 0
        if (acts2 != null) {
            for (i in 0 until acts2.length()) {
                val a = acts2.optJSONObject(i) ?: continue
                if (a.optString("status", "").uppercase() == "ELIGIBLE") pending++
            }
        }
        return CheckinResult(
            "huawei", "fail",
            "领取接口返回成功，但活动仍未翻状态（status=${state2.ifBlank { "未知" }}，" +
                "还有 $pending 个可领子项，taskStatus=${same?.optString("taskStatus", "-") ?: "-"} " +
                "claimable=${same?.opt("claimable")}）。" +
                "活动 type=${camp.optString("type", "?")}、campaignId=$cid。" +
                "若反复出现请把这条发给我",
            "",
        )
    }
}
