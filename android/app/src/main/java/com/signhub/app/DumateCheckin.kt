package com.signhub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 百度千帆 / 百度搭子（DuMate）每日登录奖励 —— **纯 OkHttp + 采集到的整串 Cookie**。
 *
 * ## 它到底叫什么
 *
 * 桌面端叫「百度搭子 DuMate」（进程目录 `qianfan-desktop-app`），网页端在
 * `https://console.bce.baidu.com`（百度智能云控制台）里。所以「百度千帆签到」
 * 指的是这一家的**每日登录奖励**，不是千帆大模型平台的额度。
 *
 * ## 接口（两个，都是 `/api/dumate/points/`）
 *
 *   · `GET  /api/dumate/points/loginBonusInfo` —— 查状态，`result.hasIssued` = 今天领没领，
 *     `result.totalPoints` = 累计积分，`result.signInDays` = 已签日期数组；
 *   · `POST /api/dumate/points/loginBonus`     —— 领取。
 *
 * ## 鉴权：Cookie + CSRF 头，缺一不可
 *
 *   · `Cookie` 整串（关键两个：`BDUSS` 是登录票据，`bce-user-info` 是控制台身份）；
 *   · `csrftoken: <bce-user-info 的值，去掉外层引号>` —— 少这个头直接被打回登录页；
 *   · `X-Requested-With: XMLHttpRequest` + `Origin` / `Referer` 必须是
 *     `https://console.bce.baidu.com`（网关按 Referer 判来源）。
 *
 * 未认证时的表现很有欺骗性：HTTP **200**，但 body 是登录页 HTML，或者
 * `{"success":false,"message":{"redirect":"https://login.bce.baidu.com?..."}}`。
 * 所以判据必须是「body 能解析成 JSON **且** success 为真」，不能只看状态码。
 *
 * ## `result` 可能是布尔 true（必须防）
 *
 * 领取成功时上游有时回 `{"success":true,"result":true}` —— 只有「已发奖」没有数额。
 * 在 Kotlin 里 `JSONObject.optInt("result")` 对 `true` 会得到 **1**，
 * 直接显示就是「成功领取 1 积分」（Python 那边 `isinstance(True, int)` 也是同一个坑）。
 * 所以先判 `opt("result") is Boolean`，是布尔就当成「已发放、数额未知」。
 *
 * ## 登录页
 *
 * `https://console.bce.baidu.com/` 在手机 UA 下会 302 到
 * `https://login.bce.baidu.com/?redirect=...`，标题是「百度智能云-WAP登录」，
 * 页面有「百度账号 / 云账号 / 发送验证码 / 账号登录 / 子用户登录 / 百度APP一键登录」
 * —— 手机上能走完整个登录流程，登录成功后会跳回控制台，此时 Cookie 里才有 `BDUSS`。
 *
 * 游客态实测有 12~14 个 cookie（`BAIDUID` / `BAIDUID_BFESS` / `HMACCOUNT` /
 * `BCE_MONITOR_TRACK_SESSION_ID` / `ppfuid` / `__bid_n` / `CASSSID` / `XFI` / `XFT` …），
 * **但没有 `BDUSS`、没有 `bce-user-info`** —— 这两个就是登录判据。
 */
object DumateCheckin {

    const val ENABLED = true

    private const val HOST = "https://console.bce.baidu.com"

    private const val P_INFO = "/api/dumate/points/loginBonusInfo"
    private const val P_CLAIM = "/api/dumate/points/loginBonus"

    private const val CSRF_COOKIE = "bce-user-info"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 只 pin 百度智能云自己的域名。
     *
     * **哈希口径是 SPKI（SubjectPublicKeyInfo）的 SHA-256，不是整证书 DER 的 SHA-256。**
     * OkHttp 的 `CertificatePinner` 比的是 `cert.publicKey.encoded` 的哈希 ——
     * 拿整张证书字节算 sha256 得到的值**永远匹配不上**，报错是
     * `Certificate pinning failure! ... Pinned certificates for console.bce.baidu.com: sha256/xxx`，
     * 而错误里列出的 Peer chain 每条都是 SPKI 哈希（V2.77 修的就是这个）。
     *
     * 正确算法（`E:\RUANJIAN\_pinfix.py`）：解析 DER → tbsCertificate 的第 7 个字段
     * （index 5，带 `[0] version` 时 index 6）→ 该 TLV 的完整字节算 SHA-256 → base64。
     *
     * 叶子是 `CN=baidu.com` 的泛域名证书（SAN 含 `baidu.com` / `www.baidu.com.cn` / `dwz.cn` 等），
     * 中间 CA 是 `GlobalSign RSA OV SSL CA 2018` —— 双写，叶子换发时中间 CA 那条续命。
     */
    private val PINNER: CertificatePinner = CertificatePinner.Builder()
        .add(
            "**.bce.baidu.com",
            "sha256/p632KhRD8nHO+0UhywglOdGGc5G6052mjOTXmKq3oyU=",
            "sha256/hETpgVvaLC0bvcGG3t0cuqiHvr4XyP2MTwCiqhgRWwU=",
        )
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .certificatePinner(PINNER)
        .build()

    // ── Cookie 解析 ──────────────────────────────────────────────────

    fun parseCookie(cookie: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        cookie.split(';').forEach { seg ->
            val i = seg.indexOf('=')
            if (i <= 0) return@forEach
            val k = seg.substring(0, i).trim()
            val v = seg.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** CSRF 令牌：`bce-user-info` 的值，去掉外层成对引号（WebView 里常带 `"`）。 */
    fun csrfOf(cookie: String): String {
        val v = parseCookie(cookie)[CSRF_COOKIE] ?: return ""
        return v.trim().removeSurrounding("\"")
    }

    /**
     * 这份 Cookie 是否真的登录了。
     *
     * 判据 = `BDUSS` 存在 **且** `bce-user-info` 存在。
     * 游客态两个都没有（实测），所以这个判据不会把游客认成已登录。
     */
    fun hasSession(cookie: String): Boolean {
        val m = parseCookie(cookie)
        return (m["BDUSS"] ?: "").isNotBlank() && (m[CSRF_COOKIE] ?: "").isNotBlank()
    }

    /** 账号身份：优先后端下发的 uid（`bce-user-info` 里通常带），兜底取 BDUSS 前缀。 */
    fun identityOf(cookie: String): String {
        val csrf = csrfOf(cookie)
        if (csrf.isNotBlank()) return csrf
        val bd = parseCookie(cookie)["BDUSS"] ?: ""
        return bd.take(24)
    }

    // ── 请求 ────────────────────────────────────────────────────────

    private fun headers(cookie: String): Map<String, String> = linkedMapOf(
        "Content-Type" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to HOST,
        "Referer" to "$HOST/",
        "Cookie" to cookie,
        "csrftoken" to csrfOf(cookie),
    )

    private fun get(url: String, h: Map<String, String>): Pair<Int, String> {
        val b = Request.Builder().url(url).get()
        h.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
        client.newCall(b.build()).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    private fun post(url: String, h: Map<String, String>, body: String): Pair<Int, String> {
        val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
        h.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
        client.newCall(b.build()).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    /**
     * 把响应解成 JSONObject。
     *
     * 未认证时上游回 **200 + 登录页 HTML**，直接 `JSONObject(text)` 会抛，
     * 抛出来的 message 是「Expected literal value at ...」，对用户毫无意义 ——
     * 这里统一翻译成「登录态已失效」。
     *
     * 返回值第三个是 **authFailed**（BUGS-v2.63 P3-1）：以前调用方写
     * `authFailed = ierr.contains("登录")`，靠中文散文匹配 —— 四条失效路径里
     * 「响应非 JSON」不含「登录」，于是这条真失效路径不会被标成 authFailed，
     * 而且将来任何一次改文案（「登录态已失效」→「会话已过期」）都会静默失效，
     * 编译器不报错、测试也覆盖不到。改成在解析处按结构判，不再让调用方 contains 中文。
     */
    private fun parseAuth(code: Int, text: String): Triple<JSONObject?, String, Boolean> {
        val t = text.trim()
        if (t.startsWith("<")) {
            return Triple(
                null,
                if (code == 200) "登录态已失效（上游返回登录页）：请重新登录百度智能云" else "HTTP $code：上游返回网页",
                code == 200,
            )
        }
        val doc = try {
            JSONObject(t)
        } catch (e: Exception) {
            // 200 却不是 JSON 也不是 HTML：多半被重定向到了别的页，按失效处理；
            // 非 2xx 是网关/服务端错误，不算登录问题。
            return Triple(null, "HTTP $code：响应非 JSON（${t.take(120)}）", code == 200)
        }
        val msg = doc.opt("message")
        if (msg is JSONObject && msg.optString("redirect", "").isNotBlank()) {
            return Triple(null, "登录态已失效：请重新登录百度智能云", true)
        }
        if (msg is String && msg.contains("login", true) && msg.contains("bce", true)) {
            return Triple(null, "登录态已失效：请重新登录百度智能云", true)
        }
        return Triple(doc, "", false)
    }

    /** `result` 里的数值字段；`true`/`false` 一律不当数字（见类注释）。 */
    private fun intOf(o: JSONObject?, vararg keys: String): Int {
        if (o == null) return -1
        for (k in keys) {
            when (val v = o.opt(k)) {
                is Boolean -> continue
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
                else -> {}
            }
        }
        return -1
    }

    /**
     * 签到。
     *
     * 顺序：先查 `loginBonusInfo`，`hasIssued` 为真直接返回 already（**不发写请求**，
     * 少一次可能触发风控的调用）；否则 `POST loginBonus` 领取，再读一次余额。
     */
    suspend fun run(cookie: String): CheckinResult = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) {
            return@withContext CheckinResult(
                "qianfan", "fail",
                "缺少凭据：请点「登录百度智能云」，在站内登录一次即可自动采集 Cookie"
            )
        }
        if (!hasSession(cookie)) {
            return@withContext CheckinResult(
                "qianfan", "fail",
                "Cookie 里没有 BDUSS / bce-user-info：这是游客态，请重新登录一次",
                authFailed = true,
            )
        }
        val h = headers(cookie)
        try {
            val (ic, it) = get("$HOST$P_INFO", h)
            val (info, ierr, iAuth) = parseAuth(ic, it)
            if (info == null) {
                return@withContext CheckinResult("qianfan", "fail", ierr, authFailed = iAuth)
            }
            val res = info.optJSONObject("result") ?: info.optJSONObject("data")
            val issued = res?.optBoolean("hasIssued", false) ?: false
            val total0 = intOf(res, "totalPoints", "total", "points")

            if (issued) {
                val days = res?.optJSONArray("signInDays")?.length() ?: -1
                return@withContext CheckinResult(
                    "qianfan", "already",
                    "今日已签到" + (if (days > 0) " · 已签 $days 天" else ""),
                    "", total0,
                )
            }

            val (cc, ct) = post("$HOST$P_CLAIM", h, "{}")
            val (claim, cerr, cAuth) = parseAuth(cc, ct)
            if (claim == null) {
                return@withContext CheckinResult("qianfan", "fail", cerr, authFailed = cAuth)
            }
            if (!claim.optBoolean("success", false)) {
                val m = claim.opt("message")
                val txt = when (m) {
                    is String -> m
                    is JSONObject -> m.optString("msg", m.toString())
                    else -> claim.toString().take(120)
                }
                return@withContext CheckinResult("qianfan", "fail", "领取失败：$txt")
            }

            // result 可能是布尔 true（已发奖、无数额），也可能是数字
            val got = claim.opt("result")
            val gained = when (got) {
                is Boolean -> -1          // -1 = 已发放但上游没给数额
                is Number -> got.toInt()
                is String -> got.toIntOrNull() ?: -1
                is JSONObject -> intOf(got, "points", "score", "amount", "totalPoints")
                else -> -1
            }

            // 领完再读一次余额（claim 响应里的 totalPoints 不一定是最新的）
            var total = -1
            runCatching {
                val (i2c, i2t) = get("$HOST$P_INFO", h)
                val (i2, _, _) = parseAuth(i2c, i2t)
                total = intOf(i2?.optJSONObject("result") ?: i2?.optJSONObject("data"), "totalPoints", "total")
            }
            if (total < 0) total = total0

            CheckinResult(
                "qianfan", "success",
                if (gained > 0) "签到成功 +$gained 积分" else "签到成功",
                if (gained > 0) "+$gained" else "",
                total,
            )
        } catch (e: Exception) {
            CheckinResult("qianfan", "fail", "网络错误：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 只读探测：登录态是否可用 + 当前累计积分。 */
    suspend fun probe(cookie: String): JSONObject = withContext(Dispatchers.IO) {
        val out = JSONObject()
        out.put("key", "qianfan")
        if (cookie.isBlank()) {
            out.put("ok", false).put("message", "未填写 Cookie")
            return@withContext out
        }
        if (!hasSession(cookie)) {
            out.put("ok", false).put("message", "游客态 Cookie（缺 BDUSS / bce-user-info），请重新登录")
            return@withContext out
        }
        try {
            val (code, text) = get("$HOST$P_INFO", headers(cookie))
            val (doc, err, _) = parseAuth(code, text)
            if (doc == null) {
                out.put("ok", false).put("message", err)
                return@withContext out
            }
            val res = doc.optJSONObject("result") ?: doc.optJSONObject("data")
            val total = intOf(res, "totalPoints", "total", "points")
            out.put("ok", true)
            out.put("total", total)
            out.put("issued", res?.optBoolean("hasIssued", false) ?: false)
            out.put("message", if (total >= 0) "累计 $total 积分" else "已登录（积分读取失败）")
        } catch (e: Exception) {
            out.put("ok", false).put("message", "网络错误：${e.message}")
        }
        out
    }
}
