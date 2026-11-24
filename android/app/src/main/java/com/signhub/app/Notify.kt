package com.signhub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 推送通知（本地模式实装，不依赖电脑端 /api/test_notify）。
 *
 * 支持 7 个渠道，按 webhook 的 host 自动适配 payload —— 用户只需要粘一条地址：
 *   · 企业微信群机器人   qyapi.weixin.qq.com   {"msgtype":"text","text":{"content":..}}
 *   · 钉钉群机器人       oapi.dingtalk.com    同上；开了加签时拼 timestamp+sign
 *   · 飞书群机器人       open.feishu.cn       {"msg_type":"text","content":{"text":..}}
 *   · PushPlus           pushplus.plus/one    {"token","title","content","template":"html"}
 *   · Server酱           sctapi.ftqq.com      form: title + desp（markdown）
 *   · Bark（iOS）        api.day.app          {"device_key","title","body","group"}
 *   · WxPusher（微信）   wxpusher.zjiecode.com {"appToken","content","uids",..}
 *   · 通用 / 自建        其它                 {"title","text","content","ts"}
 *
 * 配置落在 SharedPreferences "signhub_notify"：
 *   enabled / kind / url / secret（Bark 的 key 也可填 url；WxPusher 的 UID 填 secret）
 */
object Notify {

    private const val PREF = "signhub_notify"
    private val TZ = ZoneId.of("Asia/Shanghai")
    // N3（BUGS-v2.1）：显式 callTimeout，见 WbMulti.kt 同处注释。
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    val KINDS = listOf(
        "wecom" to "企业微信群机器人",
        "dingtalk" to "钉钉群机器人",
        "feishu" to "飞书群机器人",
        "pushplus" to "PushPlus（推个人微信）",
        "serverchan" to "Server酱（推个人微信）",
        "bark" to "Bark（推 iOS）",
        "wxpusher" to "WxPusher（推微信）",
        "generic" to "通用 / 自建",
    )

    data class Cfg(
        val enabled: Boolean = false,
        val kind: String = "wecom",
        val url: String = "",
        val secret: String = "",
        /**
         * secret 解密失败（Keystore 密钥被清）。N6（BUGS-v2.1）。
         *
         * 为真时 [save] 不覆盖原密文 —— 否则「解不开 → 空串 → 保存 → 覆盖」会把
         * 钉钉加签密钥/PushPlus token 永久抹掉，且用户无从察觉。
         */
        val secretLost: Boolean = false,
    )

    fun load(ctx: Context): Cfg {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val rawSecret = p.getString("secret", "") ?: ""
        val dec = SecretBox.decrypt(rawSecret)
        // decryptOrEmpty 会把哨兵映射成 ""，但这里要额外记下「解不开」这件事，
        // 好让 save() 拒绝覆盖原密文（N6）。
        val lost = SecretBox.isFailed(dec)
        return Cfg(
            enabled = p.getBoolean("enabled", false),
            kind = p.getString("kind", "wecom") ?: "wecom",
            url = p.getString("url", "") ?: "",
            // secret 是钉钉加签密钥 / PushPlus token / Bark key 一类「能直接发消息」的凭据，
            // 与 Settings.kt 一样过 SecretBox；老明文 decrypt 原样返回，下次 save 自动转密文。
            secret = if (lost) "" else dec,
            secretLost = lost,
        )
    }

    fun save(ctx: Context, c: Cfg) {
        val e = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled)
            .putString("kind", c.kind)
            .putString("url", c.url)
        // 解不开且用户没重填 → 保留原密文，绝不写空串覆盖（N6，BUGS-v2.1）
        // M3（BUGS-v2.61）：加密失败时也不写 —— 宁可这次不落盘，也不能把
        // webhook secret 明文写进 SharedPreferences。
        if (!(c.secretLost && c.secret.isEmpty())) {
            if (c.secret.isEmpty()) {
                e.putString("secret", "")
            } else {
                val enc = SecretBox.encryptOrNull(c.secret)
                if (enc != null) e.putString("secret", enc)
            }
        }
        e.apply()
    }

    /** 归一化渠道名（兼容网页/后端旧写法）。 */
    fun normalize(kind: String): String = when (kind.lowercase().trim()) {
        "wecom", "weixin", "qyapi", "企业微信" -> "wecom"
        "dingtalk", "ding", "钉钉" -> "dingtalk"
        "feishu", "lark", "飞书" -> "feishu"
        "pushplus", "push_plus", "pushplus.plus" -> "pushplus"
        "serverchan", "server_chan", "sct", "server酱" -> "serverchan"
        "bark", "ios" -> "bark"
        "wxpusher", "wx_pusher", "wxp" -> "wxpusher"
        else -> "generic"
    }

    private fun host(url: String): String =
        try { java.net.URL(url).host.lowercase() } catch (e: Exception) { "" }

    /** 按 webhook 域名推断渠道：用户粘什么地址都能对。 */
    private fun detect(url: String, fallback: String): String {
        val h = host(url)
        return when {
            h.contains("qyapi.weixin.qq.com") -> "wecom"
            h.contains("oapi.dingtalk.com") -> "dingtalk"
            h.contains("open.feishu.cn") || h.contains("open.larksuite.com") -> "feishu"
            h.contains("pushplus.plus") || h.contains("pushplus.one") -> "pushplus"
            h.contains("ftqq.com") -> "serverchan"
            h.contains("day.app") || h.contains("bark") -> "bark"
            h.contains("wxpusher") -> "wxpusher"
            else -> normalize(fallback)
        }
    }

    /** 钉钉加签：HmacSHA256(secret, "ts\nsecret") → Base64 → urlEncode。 */
    private fun dingSignedUrl(url: String, secret: String): String {
        if (secret.isBlank()) return url
        val ts = System.currentTimeMillis().toString()
        val stringToSign = "$ts\n$secret"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sign = android.util.Base64.encodeToString(
            mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP
        )
        val enc = URLEncoder.encode(sign, "UTF-8")
        return if (url.contains("?")) "$url&timestamp=$ts&sign=$enc" else "$url?timestamp=$ts&sign=$enc"
    }

    /** PushPlus 的 token 既可能在路径 /send/<token> 也可能在 ?token=。 */
    private fun pushplusToken(url: String): String {
        return try {
            val u = java.net.URL(url)
            val segs = u.path.split("/").filter { it.isNotBlank() }
            if (segs.size >= 2 && (segs[0] == "send" || segs[0] == "batchSend")) segs[1]
            else Regex("token=([^&]+)").find(url)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 取地址路径最后一段当 key。
     * Bark 的 device key（https://api.day.app/<key>）与 WxPusher 的 appToken
     * （.../api/send/message/<appToken>）段数不固定，不能用 pushplusToken 那套规则。
     */
    private fun lastPathToken(url: String): String {
        val s = url.trim()
        if (s.isBlank()) return ""
        if (!s.contains("://")) return s
        return try {
            val u = java.net.URL(s)
            val segs = u.path.split("/").filter { it.isNotBlank() }
            if (segs.isEmpty()) s else java.net.URLDecoder.decode(segs.last(), "UTF-8")
        } catch (e: Exception) {
            s
        }
    }

    /**
     * 从 Server酱 的填法里抠出 SendKey。
     *
     * 历史 bug：原来直接把用户填的整串当 URL POST 过去，于是
     *   - 填了 `https://sctapi.ftqq.com/<SCTxxx>.send`（placeholder 带尖括号）→ 发出去 key 变成 `<SCTxxx>.send`
     *   - 服务端回 `HTTP 400 {"message":"[AUTH]错误的Key","code":40001}`
     * 现在统一清洗：去掉尖括号、引号、空白、`.send` 后缀，只留 key 本身，
     * 再拼成标准地址 `https://sctapi.ftqq.com/<key>.send`。
     */
    private fun serverChanKey(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) return ""
        s = s.replace("<", "").replace(">", "").replace("\"", "").replace("'", "").trim()
        if (s.contains("://") || s.contains("/")) {
            s = lastPathToken(s)
        }
        s = s.removeSuffix(".send").removeSuffix("/")
        s = s.replace("<", "").replace(">", "").trim()
        return s
    }

    data class Ret(val ok: Boolean, val kind: String, val detail: String)

    /** 推送一条。title 为空时用默认标题。 */
    suspend fun push(
        ctx: Context?, cfg: Cfg, title: String, body: String, timeoutUrl: String = ""
    ): Ret = withContext(Dispatchers.IO) {
        val target = if (timeoutUrl.isNotBlank()) timeoutUrl else cfg.url
        if (target.isBlank()) return@withContext Ret(false, cfg.kind, "webhook 地址为空")
        val kind = detect(target, cfg.kind)
        val now = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        try {
            val (url, reqBody, media) = when (kind) {
                "wecom", "dingtalk" -> {
                    val text = "$title\n$body\n\n$now"
                    val u = if (kind == "dingtalk") dingSignedUrl(target, cfg.secret) else target
                    Triple(u, JSONObject().put("msgtype", "text")
                        .put("text", JSONObject().put("content", text)).toString(), JSON)
                }
                "feishu" -> {
                    val text = "$title\n$body\n\n$now"
                    Triple(target, JSONObject().put("msg_type", "text")
                        .put("content", JSONObject().put("text", text)).toString(), JSON)
                }
                "pushplus" -> {
                    val html = "<b>${esc(title)}</b><br>" +
                        body.lineSequence().filter { it.isNotBlank() }
                            .joinToString("<br>") { esc(it) } +
                        "<br><br><span style=\"color:#999\">$now</span>"
                    val o = JSONObject()
                        .put("token", pushplusToken(target))
                        .put("title", title)
                        .put("content", html)
                        .put("template", "html")
                    // 走官方网关，不依赖用户填的完整路径
                    Triple("https://www.pushplus.plus/send", o.toString(), JSON)
                }
                "serverchan" -> {
                    val md = "**$title**\n\n$body\n\n$now"
                    val key = serverChanKey(target)
                    if (key.isBlank()) {
                        return@withContext Ret(false, kind, "SendKey 为空：请填 SCT 开头的那串，或 https://sctapi.ftqq.com/<SendKey>.send")
                    }
                    Triple("https://sctapi.ftqq.com/$key.send",
                        "title=" + URLEncoder.encode(title, "UTF-8") +
                            "&desp=" + URLEncoder.encode(md, "UTF-8"), FORM)
                }
                "bark" -> {
                    val key = lastPathToken(target)
                    val o = JSONObject()
                        .put("device_key", key)
                        .put("title", title.ifBlank { "签到中心" })
                        .put("body", body)
                        .put("group", "SigninHub")
                    Triple("https://api.day.app/push", o.toString(), JSON)
                }
                "wxpusher" -> {
                    val appToken = lastPathToken(target)
                    val uids = cfg.secret.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val html = "<b>${esc(title)}</b><br>" +
                        body.lineSequence().filter { it.isNotBlank() }
                            .joinToString("<br>") { esc(it) }
                    val o = JSONObject()
                        .put("appToken", appToken)
                        .put("content", html)
                        .put("summary", title.take(20))
                        .put("contentType", 2)
                        .put("uids", org.json.JSONArray(uids))
                    Triple("https://wxpusher.zjiecode.com/api/send/message", o.toString(), JSON)
                }
                else -> {
                    val text = "$title\n$body\n\n$now"
                    Triple(target, JSONObject().put("title", title).put("text", text)
                        .put("content", text).put("ts", now).toString(), JSON)
                }
            }
            val b = Request.Builder().url(url)
                .addHeader("Content-Type", if (media == FORM) "application/x-www-form-urlencoded" else "application/json")
                .post(reqBody.toRequestBody(media))
                .build()
            client.newCall(b).execute().use { r ->
                val text = r.body?.string() ?: ""
                val ok = r.code in 200..299 && checkBody(kind, text)
                Ret(ok, kind, if (ok) "已推送（HTTP ${r.code}）" else "HTTP ${r.code} $text".take(200))
            }
        } catch (e: Exception) {
            Ret(false, kind, "推送异常：${e.message}")
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 各家成功判定：有的 HTTP 200 但 body 里 code != 0。 */
    private fun checkBody(kind: String, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        return try {
            val o = JSONObject(t)
            when (kind) {
                "wecom" -> o.optInt("errcode", 0) == 0
                "dingtalk" -> o.optInt("errcode", 0) == 0
                "feishu" -> o.optInt("code", 0) == 0 || o.optInt("StatusCode", 0) == 0
                "pushplus" -> o.optInt("code", 200) == 200
                "serverchan" -> o.optInt("code", 0) == 0
                "bark" -> o.optInt("code", 200) == 200
                "wxpusher" -> {
                    val top = o.optInt("code", 1000) == 1000
                    val arr = o.optJSONArray("data")
                    var inner = true
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val d = arr.optJSONObject(i) ?: continue
                            if (d.optInt("code", 1000) != 1000) { inner = false; break }
                        }
                    }
                    top && inner
                }
                else -> true
            }
        } catch (e: Exception) {
            true
        }
    }

    /** 给签到完成时用的默认文案。 */
    fun summaryTitle(okCount: Int, total: Int): String = when {
        okCount == total && total > 0 -> "自动签到完成"
        okCount > 0 -> "自动签到部分成功 $okCount/$total"
        else -> "自动签到失败"
    }
}
