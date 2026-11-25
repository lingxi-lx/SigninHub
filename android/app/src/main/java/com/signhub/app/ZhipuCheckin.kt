package com.signhub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 智谱清言（chatglm.cn）每日签到 —— **纯 OkHttp，不需要 WebView**。
 *
 * 这是本项目里少见的「签名可以本地算」的上游：签名算法在桌面端 JS 里是明文常量，
 * 没有 `a_bogus` 那种 SDK 层 monkey-patch。实测（2026-11-24）签名头 + 假 Bearer
 * 返回 `401 40102 unauthorized user`，说明**签名被服务端接受**、请求进到了 token 校验层
 * —— 签名这一关是真的过了，不是被兜底挡掉。
 *
 * ## 接口与签名（逐字来自 zaoba 的 `src/adapters/zhipu.js`，本机已复现）
 *
 *   · `POST /chatglm/member-api/member/daily_login_score` body `{}` —— 签到；
 *   · `GET  /chatglm/member-api/member/member_info`        —— 余额（`left_score` 是
 *     **1/100 精度**，要除以 100）；
 *   · `POST /chatglm/user-api/user/refresh` body `{}`      —— 用 refresh token 换 access token。
 *
 * `X-Sign = md5("<ts>-<nonce>-<APP_SECRET>")`，`nonce` 是 16 字节随机数的 hex（32 字符）。
 *
 * ## 时间戳必须改写（最容易漏的一步）
 *
 * 上游不是拿原样毫秒串去算 md5，而是先做一次**校验位替换**：13 位串的倒数第二位
 * 换成 `(各位数字之和 − 倒数第二位) % 10`，再拿改写后的串参与 md5。
 * 直接发原样时间戳 → 签名永远不对。见 [signedTimestamp]。
 *
 * ## 登录判据：必须看 JWT 里的 `is_guest`，不能看 token 在不在
 *
 * 游客态也会拿到**一整套完整 token**（`chatglm_token` 421 字节 + `chatglm_refresh_token`），
 * 只是 JWT payload 里 `is_guest: true`。zaoba 的 `src/login-one.js:30-34` 注释记录过同一个坑：
 * 用 `Boolean(chatglm_token)` 判登录会导致「一打开就判成功、4 秒后自动关窗」，用户来不及扫码。
 * 所以 [hasSession] 判的是「refresh token 存在 **且** 不是游客」。
 *
 * ## 每次签到前都先 refresh 一次
 *
 * access token 只有约 24 小时，refresh token 约 50 天。实测 **refresh 不轮换**
 * （同一个 refresh token 反复刷都 200，旧值可复用），所以可以每次签到前无条件刷一次，
 * 拿一枚新的 access token 再去签到 —— 这样就不需要把新 token 写回 DataStore，
 * 也就没有「写回失败 → 下次还是过期 token」这类问题。
 *
 * ## 错误码语义（实测，别混）
 *
 *   · `400 + 40003` = **缺 `Authorization` 头**（看着像未登录，其实是缺头）；
 *   · `401 + 40102` = Bearer token 无效 / 过期；
 *   · `401 + 40103` = token 有效但是游客（`is_guest:true`）；
 *   · `400 + 40001` = 缺签名头（refresh 端点实测）。
 *
 * ## 设备号
 *
 * `X-Device-Id` **不是硬校验**：错 deid、无 deid 都回 40103（与正确 deid 一致），
 * 服务端只校验 token 里的 `device_id`。所以 deid 直接从 JWT 里解，不需要用户另填；
 * 解不出来时按 uid 派生一个稳定的 32 位 hex（同一账号每次相同，不跨账号撞）。
 */
object ZhipuCheckin {

    private const val BASE = "https://chatglm.cn"

    private const val P_CHECKIN = "/chatglm/member-api/member/daily_login_score"
    private const val P_MEMBER = "/chatglm/member-api/member/member_info"
    private const val P_REFRESH = "/chatglm/user-api/user/refresh"

    /** 桌面端 JS 里的明文常量。上游换版本时这里要跟着换（换了签名会全 401）。 */
    private const val APP_SECRET = "8a1317a7468aa3ad86e997d08f3f31cb"

    private const val APP_VERSION = "2.0.7"

    /** 签到结果码：今日已领（幂等，可以盲调，不会重复发分）。 */
    private const val ST_ALREADY = 10001

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 只 pin 智谱自己的域名。
     *
     * **哈希口径是 SPKI（SubjectPublicKeyInfo）的 SHA-256，不是整证书 DER 的 SHA-256。**
     * OkHttp 的 `CertificatePinner` 比的是 `cert.publicKey.encoded` 的哈希 ——
     * 用 `openssl x509 -outform der | sha256` 或「整张证书字节算 sha256」得到的值**永远匹配不上**，
     * 报错长这样：`Certificate pinning failure! ... Pinned certificates for chatglm.cn: sha256/xxx`，
     * 而上面列出的 Peer chain 里每条都是 SPKI 哈希（V2.77 修的就是这个）。
     *
     * 正确算法（`E:\RUANJIAN\_pinfix.py`）：解析 DER → 取 tbsCertificate 的第 7 个字段（index 5，
     * 有 `[0] version` 时 index 6）→ 该 TLV 的完整字节算 SHA-256 → base64。
     *
     * 叶子 `*.chatglm.cn` + 中间 CA `Encryption Everywhere DV TLS CA - G2` 双写：
     * 叶子按 CDN 节点轮换（不同节点可能发不同叶子），中间 CA 稳定，两条任一命中即可。
     */
    private val PINNER: CertificatePinner = CertificatePinner.Builder()
        .add(
            "**.chatglm.cn",
            "sha256/1g6gMbNEnilRuhuAqM1PoNt1Avz5+Z1WzZkQXymKoto=",
            "sha256/gxeKFFaZ2HFJIsTdTjEl6nVo3ckTCX+qzRMqb9Xoa1w=",
        )
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .certificatePinner(PINNER)
        .build()

    // ── Cookie / token 解析 ──────────────────────────────────────────

    /** `a=1; b=2` → map。名字大小写敏感，值原样（不 trim 内部）。 */
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

    fun accessToken(cookie: String): String = parseCookie(cookie)["chatglm_token"] ?: ""

    fun refreshToken(cookie: String): String = parseCookie(cookie)["chatglm_refresh_token"] ?: ""

    /**
     * 把 Cookie 里的 `chatglm_refresh_token` 换成 [newRt]，其余键值原样保留
     * （BUGS-v2.63-286 P3：`run()` 的 onRenewed 回调拿它回写 `zpCookie`）。
     *
     * 找不到该键就追加一条 —— 上游改行为时宁可多一个键，也不能让新 refresh 丢失。
     */
    fun withRefreshToken(cookie: String, newRt: String): String {
        if (newRt.isBlank()) return cookie
        val parts = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var hit = false
        for (i in parts.indices) {
            val seg = parts[i]
            val eq = seg.indexOf('=')
            if (eq <= 0) continue
            if (seg.substring(0, eq).trim() == "chatglm_refresh_token") {
                parts[i] = "chatglm_refresh_token=$newRt"
                hit = true
                break
            }
        }
        if (!hit) parts.add("chatglm_refresh_token=$newRt")
        return parts.joinToString("; ")
    }

    /** JWT 的 payload；解不开返回 null。 */
    fun jwtPayload(token: String): JSONObject? {
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return null
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            val raw = android.util.Base64.decode(p, android.util.Base64.NO_WRAP)
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 这份 Cookie 是否代表「真的登录了」。
     *
     * 判据 = refresh token 存在 **且** JWT 里 `is_guest != true`。
     * 游客态也有完整 token，只判存在会把游客认成已登录（见类注释）。
     */
    fun hasSession(cookie: String): Boolean {
        val rt = refreshToken(cookie)
        if (rt.isBlank()) return false
        val o = jwtPayload(rt) ?: return false
        if (o.optBoolean("is_guest", false)) return false
        // 有的版本把 is_guest 写成 0/1 而不是布尔
        if (o.optInt("is_guest", 0) == 1) return false
        return true
    }

    /** JWT 里的 uid（`uid` 优先，兜底 `sub` / `user_id`）；取不到返回空串。 */
    fun uidOf(cookie: String): String {
        for (tok in listOf(accessToken(cookie), refreshToken(cookie))) {
            if (tok.isBlank()) continue
            val o = jwtPayload(tok) ?: continue
            for (k in listOf("uid", "sub", "user_id", "userId")) {
                val v = o.optString(k, "")
                if (v.isNotBlank() && v != "null") return v
            }
        }
        return ""
    }

    /**
     * 设备号：JWT 里的 `device_id` → 用户显式填的 → 按 uid 派生的稳定 32 位 hex。
     *
     * 顺序上 JWT 优先，因为服务端只认 token 里那个 `device_id`，
     * 自己编一个反而和 token 对不上（虽然实测不校验，但一致总比不一致好）。
     */
    fun deviceIdOf(cookie: String, explicit: String = ""): String {
        for (tok in listOf(accessToken(cookie), refreshToken(cookie))) {
            if (tok.isBlank()) continue
            val v = jwtPayload(tok)?.optString("device_id", "") ?: ""
            if (v.isNotBlank() && v != "null") return v
        }
        if (explicit.isNotBlank()) return explicit
        val uid = uidOf(cookie)
        if (uid.isNotBlank()) return md5("chatglm-device-$uid")
        return md5("chatglm-device-" + cookie.take(64))
    }

    // ── 签名 ────────────────────────────────────────────────────────

    /**
     * 13 位毫秒串 → 把**倒数第二位**换成校验位 `(各位数字之和 − 倒数第二位) % 10`。
     *
     * 复刻上游桌面端 `j()`。不这么改写，`X-Sign` 一定对不上，服务端回 401 40102
     * （看起来像 token 无效，其实是签名错了 —— 这个误导性很强，别被带跑）。
     */
    private fun signedTimestamp(nowMs: Long = System.currentTimeMillis()): String {
        val raw = nowMs.toString()
        if (raw.length < 3) return raw
        val digits = raw.map { it - '0' }
        val sum = digits.sum() - digits[digits.size - 2]
        val check = sum % 10
        return raw.substring(0, raw.length - 2) + check + raw.substring(raw.length - 1)
    }

    private fun nonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder(32)
        b.forEach { sb.append(String.format("%02x", it)) }
        return sb.toString()
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        d.forEach { sb.append(String.format("%02x", it)) }
        return sb.toString()
    }

    /** 一组签名头（不含 Authorization，由调用方按用途追加 access / refresh token）。 */
    private fun signHeaders(deviceId: String): Map<String, String> {
        val ts = signedTimestamp()
        val nc = nonce()
        return linkedMapOf(
            "Content-Type" to "application/json",
            "App-Name" to "chatglm",
            "X-App-Platform" to "pc",
            "X-App-Version" to APP_VERSION,
            "X-Device-Id" to deviceId,
            "X-Timestamp" to ts,
            "X-Nonce" to nc,
            "X-Sign" to md5("$ts-$nc-$APP_SECRET"),
        )
    }

    private fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
        client.newCall(b.build()).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    private fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
        client.newCall(b.build()).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        JSONObject().put("status", -1).put("message", text.take(300))
    }

    /** 用 refresh token 换一枚新的 access token。成功返回 (access, refresh, isGuest)。 */
    private fun refresh(refreshTok: String, deviceId: String): Triple<String, String, Boolean>? {
        val h = LinkedHashMap(signHeaders(deviceId))
        h["Authorization"] = "Bearer $refreshTok"
        val (code, text) = post("$BASE$P_REFRESH", h, "{}")
        if (code != 200) return null
        val doc = parse(text)
        if (doc.optInt("status", -1) != 0) return null
        val res = doc.optJSONObject("result") ?: return null
        val access = res.optString("access_token", "")
        if (access.isBlank()) return null
        return Triple(
            access,
            res.optString("refresh_token", refreshTok).ifBlank { refreshTok },
            res.optBoolean("is_guest", false),
        )
    }

    /**
     * 读余额（`left_score` 是 1/100 精度）。读不到返回 -1（界面显示「—」，绝不返 0）。
     */
    private fun memberScore(access: String, deviceId: String): Int {
        return try {
            val h = LinkedHashMap(signHeaders(deviceId))
            h["Authorization"] = "Bearer $access"
            val (code, text) = get("$BASE$P_MEMBER", h)
            if (code != 200) return -1
            val doc = parse(text)
            if (doc.optInt("status", -1) != 0) return -1
            val res = doc.optJSONObject("result") ?: doc
            val raw = when (val v = res.opt("left_score")) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: -1.0
                else -> -1.0
            }
            if (raw < 0) -1 else Math.round(raw / 100.0).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 签到。
     *
     * 顺序：refresh 换新 access → 打 `daily_login_score` → 再读一次余额。
     * 每次只发 **1 个写请求**（签到本身幂等，重复调用返回 10001 不会重复发分）。
     *
     * @param onRenewed refresh 若真的下发了新的 refresh_token，回调通知调用方回写
     *                  `zpCookie`（BUGS-v2.63-286 P3）。上游目前**不轮换**，所以这条
     *                  路径平时不会触发；但一旦上游改行为，不回写就会表现为「某天起
     *                  每天都提示 refresh token 已失效，用户必须重新登录」—— 静默退化。
     *                  用回调而不是改返回值，是为了不改动另外两处调用点的解构形状。
     */
    suspend fun run(
        cookie: String,
        explicitDevice: String = "",
        onRenewed: ((String) -> Unit)? = null,
    ): CheckinResult =
        withContext(Dispatchers.IO) {
            val rt = refreshToken(cookie)
            if (rt.isBlank()) {
                return@withContext CheckinResult(
                    "zhipu", "fail",
                    "缺少凭据：请点「登录智谱清言」，在站内登录一次即可自动采集 Cookie"
                )
            }
            if (!hasSession(cookie)) {
                return@withContext CheckinResult(
                    "zhipu", "fail",
                    "这份 Cookie 是游客态（JWT 里 is_guest=true）：请重新登录一次，" +
                        "游客也能拿到完整 token，光看「有 token」判断不了",
                    authFailed = true,
                )
            }
            val deid = deviceIdOf(cookie, explicitDevice)

            try {
                val fresh = refresh(rt, deid)
                if (fresh == null) {
                    return@withContext CheckinResult(
                        "zhipu", "fail",
                        "refresh token 已失效或过期：请重新登录智谱清言采集一份新的 Cookie",
                        authFailed = true,
                    )
                }
                val (access, newRt, isGuest) = fresh
                // BUGS-v2.63-286 P3：新 refresh 一旦真的变了就必须回写，否则下一次
                // 还在用旧的换 access。上游当前不轮换，所以这里平时不触发。
                if (newRt.isNotBlank() && newRt != rt) {
                    runCatching { onRenewed?.invoke(newRt) }
                }
                if (isGuest) {
                    return@withContext CheckinResult(
                        "zhipu", "fail",
                        "登录态已失效（refresh 后仍是游客）：请重新登录一次",
                        authFailed = true,
                    )
                }

                val h = LinkedHashMap(signHeaders(deid))
                h["Authorization"] = "Bearer $access"
                val (code, text) = post("$BASE$P_CHECKIN", h, "{}")
                val doc = parse(text)
                val st = doc.optInt("status", -1)

                // BUGS-v2.63 P3-2：余额只在「这次真签成」之后才读。
                // 以前这行在 if (code != 200) 之前，签到失败（token 过期 / 签名错 / 限流）
                // 也会白打一次 member_info —— 失败路径的请求数比预期多一倍，对限流敏感的
                // 账号是纯浪费。
                if (code != 200) {
                    val hint = when (st) {
                        40003 -> "缺 Authorization 头（内部错误，请反馈）"
                        40102 -> "access token 无效：请重新登录"
                        40103 -> "token 有效但是游客态：请重新登录"
                        40001 -> "签名头缺失（内部错误，请反馈）"
                        else -> doc.optString("message", text.take(120))
                    }
                    return@withContext CheckinResult("zhipu", "fail", "签到失败：$hint", authFailed = st == 40102 || st == 40103)
                }

                when (st) {
                    ST_ALREADY -> CheckinResult(
                        "zhipu", "already", "今日已签到（之前领过了）",
                        "", memberScore(access, deid),
                    )
                    0, 10000 -> {
                        val gained = earnedOf(doc)
                        CheckinResult(
                            "zhipu", "success",
                            if (gained > 0) "签到成功 +$gained 积分" else "签到成功",
                            if (gained > 0) "+$gained" else "",
                            memberScore(access, deid),
                        )
                    }
                    else -> CheckinResult(
                        "zhipu", "fail",
                        "签到未成功（status=$st ${doc.optString("message", "")}）".trim(),
                    )
                }
            } catch (e: Exception) {
                CheckinResult("zhipu", "fail", "网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    /** 签到响应里能读到的「本次得分」，读不到返回 0（不当成错误）。 */
    private fun earnedOf(doc: JSONObject): Int {
        val res = doc.optJSONObject("result") ?: return 0
        for (k in listOf("score", "reward", "add_score", "gain", "points")) {
            when (val v = res.opt(k)) {
                is Number -> if (v.toInt() > 0) return v.toInt()
                is String -> v.toIntOrNull()?.let { if (it > 0) return it }
                else -> {}
            }
        }
        return 0
    }

    /**
     * 只读探测：能不能读到余额。
     *
     * 不在这里判「今天签没签」——`member_info` 不带签到状态，硬判会误报。
     * 签到状态由 `/api/status` 从本地历史库读（与其它 provider 一致）。
     */
    suspend fun probe(cookie: String, explicitDevice: String = ""): JSONObject =
        withContext(Dispatchers.IO) {
            val out = JSONObject()
            out.put("key", "zhipu")
            if (cookie.isBlank()) {
                out.put("ok", false).put("message", "未填写 Cookie")
                return@withContext out
            }
            if (!hasSession(cookie)) {
                out.put("ok", false).put("message", "游客态 Cookie（is_guest=true），请重新登录")
                return@withContext out
            }
            val deid = deviceIdOf(cookie, explicitDevice)
            try {
                val fresh = refresh(refreshToken(cookie), deid)
                if (fresh == null) {
                    out.put("ok", false).put("message", "refresh token 已失效，请重新登录")
                    return@withContext out
                }
                val (access, _, isGuest) = fresh
                if (isGuest) {
                    out.put("ok", false).put("message", "refresh 后仍是游客，请重新登录")
                    return@withContext out
                }
                val score = memberScore(access, deid)
                out.put("ok", true)
                out.put("total", score)
                out.put("message", if (score >= 0) "余额 $score 积分" else "已登录（余额读取失败）")
            } catch (e: Exception) {
                out.put("ok", false).put("message", "网络错误：${e.message}")
            }
            out
        }
}
