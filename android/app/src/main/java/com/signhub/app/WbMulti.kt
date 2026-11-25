package com.signhub.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 多账号 WorkBuddy 模块：OAuth 登录 + 每账号签到 + 自动续期。
 * 协议对齐 workbuddy-switch（wb-switch-core）：
 *   OAuth: POST {ep}/v2/plugin/auth/state?platform=workbuddy
 *          GET  {ep}/v2/plugin/auth/token?state=..
 *          GET  {ep}/v2/plugin/login/account?state=..
 *   签到: POST {base}/v2/billing/meter/checkin-activity-status,daily-checkin
 *   续期: POST {ep}/v2/plugin/auth/token/refresh
 * 网关按 UA 指纹拦截，UA 必须与桌面版一致。
 */
data class WbAccount(
    val id: String,
    val uid: String = "",
    val nickname: String = "",
    val email: String = "",
    val enterpriseId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val domain: String = "",
    val variant: String = "cn",     // cn | ai
    val expiresAt: Long = 0L,
    val createdAt: Long = 0L,
    val lastStatus: String = "",    // 最近签到结果 success/already/fail
    val lastMessage: String = "",
    val lastCheckAt: Long = 0L,
    val totalPoints: Int = -1,      // 账号当前持有积分（余额口径；-1 = 未读到）
    val streakDays: Int = -1,       // 上游连续签到天数（-1 = 未读到）
    /**
     * 读进来时的**原始密文**，只在「解不开」时需要用来保护数据（N1，BUGS-v2.1）。
     *
     * 解密失败时 [accessToken] 被 `decryptOrEmpty` 映射成空串，而 `toJson()` 里
     * `encrypt("")` 返回空串 → 一次 save 就把完好的密文抹掉。保留原密文后
     * `encryptKeep("", accessTokenRaw)` 会原样写回。
     */
    val accessTokenRaw: String = "",
    val refreshTokenRaw: String = "",
    /**
     * 手机号打码串（用户 m16555），例如 `166******71`。
     *
     * 只存上游原样给的串 —— WB 的 `login/account` 返回里如果有 `phone`/`mobile`
     * 就顺手带上，没有就留空。留空时界面回落到昵称 → 邮箱 → **uid 前 8 位**
     * （uid 是完整 UUID，直接显示会把卡片撑爆，v2.21 在真机上出现过）。
     */
    val phone: String = "",
    /**
     * 用户自定义的显示名（用户 m02619）。
     *
     * 不能复用 [nickname]：那个字段是 OAuth `login/account` 返回的上游昵称，
     * 每次登录/刷新都会被覆盖。用户改的名字必须独立存，`upsert` 命中时也不能被冲掉。
     * 留空 = 用自动名（[displayName] 的老逻辑）。
     */
    val alias: String = "",
) {
    fun displayName(): String = when {
        alias.isNotBlank() -> alias
        phone.isNotBlank() -> phone
        nickname.isNotBlank() -> nickname
        email.isNotBlank() -> email
        uid.isNotBlank() -> uid.take(8)
        else -> "账号"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("uid", uid); put("nickname", nickname); put("email", email)
        put("enterpriseId", enterpriseId)
        // accessToken / refreshToken 是能直接调上游的长期凭据，落盘前过 SecretBox
        // （与 Settings.kt 共用同一把 Keystore 密钥；老明文 decrypt 原样返回，下次 save 转密文）
        // encryptKeep：明文为空但原密文还在（= 上次解密失败）时原样写回，避免覆盖（N1）。
        put("accessToken", SecretBox.encryptKeep(accessToken, accessTokenRaw))
        put("refreshToken", SecretBox.encryptKeep(refreshToken, refreshTokenRaw))
        put("domain", domain); put("variant", variant); put("expiresAt", expiresAt); put("createdAt", createdAt)
        put("lastStatus", lastStatus); put("lastMessage", lastMessage); put("lastCheckAt", lastCheckAt)
        put("totalPoints", totalPoints); put("streakDays", streakDays)
        put("phone", phone)
        put("alias", alias)
    }

    companion object {
        fun fromJson(o: JSONObject): WbAccount {
            val atRaw = o.optString("accessToken", "")
            val rtRaw = o.optString("refreshToken", "")
            val at = SecretBox.decryptOrEmpty(atRaw)
            val rt = SecretBox.decryptOrEmpty(rtRaw)
            return WbAccount(
                // N5（BUGS-v2.1）：id 兜底原来是「时间戳 + 随机数」—— 只要有一条缺 id，
                // 每次 all() 都得到新 id，WbAccountStore.update() 按 id 找就永远找不到，
                // 签到结果写不回列表。改成与 MultiAccounts 一致的内容哈希。
                id = o.optString("id", "").ifBlank {
                    AccountIds.stable(o.optString("uid", ""), at, o.optString("email", ""))
                },
                uid = o.optString("uid", ""),
                nickname = o.optString("nickname", ""),
                email = o.optString("email", ""),
                enterpriseId = o.optString("enterpriseId", ""),
                accessToken = at,
                refreshToken = rt,
                domain = o.optString("domain", ""),
                variant = o.optString("variant", "cn"),
                expiresAt = o.optLong("expiresAt", 0),
                createdAt = o.optLong("createdAt", 0),
                lastStatus = o.optString("lastStatus", ""),
                lastMessage = o.optString("lastMessage", ""),
                lastCheckAt = o.optLong("lastCheckAt", 0),
                totalPoints = o.optInt("totalPoints", -1),
                streakDays = o.optInt("streakDays", -1),
                accessTokenRaw = atRaw,
                refreshTokenRaw = rtRaw,
                phone = o.optString("phone", ""),
                alias = o.optString("alias", ""),
            )
        }
    }
}

object WbAccountStore {
    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("wb_accounts", Context.MODE_PRIVATE)

    /** 存储口径版本：v2 起 totalPoints 是「余额」而非「活动累计发放」，旧值一律作废。 */
    private const val VER = 2

    fun all(ctx: Context): MutableList<WbAccount> {
        val p = prefs(ctx)
        val raw = p.getString("list", "[]") ?: "[]"
        val stale = p.getInt("__ver", 0) != VER
        val out = mutableListOf<WbAccount>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val a = WbAccount.fromJson(arr.getJSONObject(i))
                // 旧口径写下的 totalPoints 会虚高（2200 这类），读的时候直接丢掉
                out.add(if (stale) a.copy(totalPoints = -1) else a)
            }
        } catch (e: Exception) {
            // 以前这里静默返回空表：JSON 一旦损坏，界面上所有账号凭空消失，用户以为被删了。
            // 现在留日志 + 把原始串备份到 list_broken，绝不静默丢数据。
            android.util.Log.e("WbAccountStore", "账号列表解析失败，原文保留在 prefs[wb_accounts/list]", e)
            p.edit().putString("list_broken", raw).apply()
        }
        if (stale) {
            save(ctx, out)
            p.edit().putInt("__ver", VER).apply()
        }
        return out
    }

    fun save(ctx: Context, list: List<WbAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        // commit 而不是 apply：upsert/update 是「读-改-写」，用 apply 时连续两次调用
        // 后一次可能读到前一次落盘前的旧值，把刚加的账号覆盖掉（MultiAccounts.kt 同口径）
        prefs(ctx).edit().putString("list", arr.toString()).commit()
    }

    /** 新增或按 uid/domain 更新已有账号 */
    fun upsert(ctx: Context, acc: WbAccount) {
        val list = all(ctx)
        // 判重必须带 variant：workbuddy.ai 与 codebuddy.cn 是两套账号体系，
        // 两边的 uid 完全可能撞车（各自从 0 编号）。只比 uid 会把刚登录的国际版
        // 账号当成国内主账号的重复项，凭据直接被覆盖掉（m19236）。
        val idx = list.indexOfFirst {
            it.variant == acc.variant && ((it.uid.isNotEmpty() && it.uid == acc.uid) || it.id == acc.id)
        }
        if (idx >= 0) {
            // P2R4（BUGS-v2.2）：命中已有账号时必须把凭据字段回填，与 MultiAccounts.kt 对齐。
            // 只 `copy(id = old.id)` 的话，调用方按 uid 命中但没带 token 时
            //（WbAccountsActivity 的轮询回调只回写状态字段）会把已有 accessToken
            // 冲成空串，下次签到直接报「缺少凭据」。
            val old = list[idx]
            list[idx] = acc.copy(
                id = old.id,
                accessToken = acc.accessToken.ifBlank { old.accessToken },
                refreshToken = acc.refreshToken.ifBlank { old.refreshToken },
                domain = acc.domain.ifBlank { old.domain },
                enterpriseId = acc.enterpriseId.ifBlank { old.enterpriseId },
                accessTokenRaw = if (acc.accessToken.isBlank()) old.accessTokenRaw else acc.accessTokenRaw,
                refreshTokenRaw = if (acc.refreshToken.isBlank()) old.refreshTokenRaw else acc.refreshTokenRaw,
                // 手机号/昵称是登录后异步补的：这次没带就沿用旧值，别把已取到的抹掉
                phone = acc.phone.ifBlank { old.phone },
                nickname = acc.nickname.ifBlank { old.nickname },
                // 自定义名同理：登录流程永远带不上它，命中已有账号必须沿用旧值，
                // 否则用户改过的名字会被下一次登录冲掉。清空改名走 update()。
                alias = acc.alias.ifBlank { old.alias },
            )
        } else list.add(acc)
        save(ctx, list)
    }

    fun update(ctx: Context, acc: WbAccount) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == acc.id }
        if (idx >= 0) { list[idx] = acc; save(ctx, list) }
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    /**
     * 当前选中的账号 id（用户 m14411：成长中心要能切换账号）。
     *
     * 以前 wbAccount() 恒取「主账号」，其它账号根本做不了成长任务 ——
     * 因为没有「选中」这个概念。这里用 SharedPreferences 单独存一个 id，
     * 空串表示「没选过，用默认的」（主账号优先）。
     */
    fun currentId(ctx: Context): String =
        prefs(ctx).getString("current", "") ?: ""

    fun setCurrent(ctx: Context, id: String) {
        prefs(ctx).edit().putString("current", id).commit()
    }
}

object WbApi {
    val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    const val EP_CN = "https://www.codebuddy.cn"
    const val EP_AI = "https://www.workbuddy.ai"
    private const val PLUGIN = "/v2/plugin"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    // N3（BUGS-v2.1）：必须显式设 callTimeout。
    // LocalApi.parallel 超时后调 f.cancel(true)，但 Call.execute() 不检查中断位，
    // 线程会一直占到 connect+read 自然返回（最坏 40 秒），池子只有 6 个线程 →
    // 几次 hang 之后 /api/status 触发的 backfill 全在排队。
    // callTimeout 让 OkHttp 自己在 30 秒时断开 socket 并抛 InterruptedIOException。
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    fun isAi(v: String) = v == "ai"
    fun endpoint(v: String) = if (isAi(v)) EP_AI else EP_CN
    fun platform(v: String) = if (isAi(v)) "workbuddy-ai" else "workbuddy"

    private fun req(url: String, method: String, body: String?, headers: Map<String, String>): JSONObject {
        try {
            val b = Request.Builder().url(url)
            b.addHeader("User-Agent", UA)
            b.addHeader("Content-Type", "application/json")
            headers.forEach { (k, v) -> if (v.isNotBlank()) b.addHeader(k, v) }
            if (method == "POST") b.post((body ?: "{}").toRequestBody(JSON)) else b.get()
            client.newCall(b.build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                return try { JSONObject(text) } catch (e: Exception) {
                    JSONObject().put("code", r.code).put("message", text.take(300))
                }
            }
        } catch (e: Exception) {
            return JSONObject().put("code", -1).put("message", e.message ?: "网络错误")
        }
    }

    private fun ok(o: JSONObject): Boolean { val c = o.optLong("code", -1); return c == 0L || c == 200L }

    // ---- OAuth ----
    data class Start(val state: String, val authUrl: String)
    fun oauthStart(variant: String): Start? {
        val o = req(endpoint(variant) + PLUGIN + "/auth/state?platform=" + platform(variant), "POST", "{}", emptyMap())
        val d = o.optJSONObject("data") ?: return null
        val state = d.optString("state", "")
        if (state.isEmpty()) return null
        var url = d.optString("authUrl", d.optString("auth_url", d.optString("url", "")))
        if (url.isEmpty()) url = endpoint(variant) + "/login?state=" + state
        return Start(state, url)
    }

    /** 返回 (accessToken, domain, refreshToken, expiresAt) 或 null（未授权） */
    data class TokenBundle(val accessToken: String, val domain: String, val refreshToken: String, val expiresAt: Long)
    fun oauthPoll(variant: String, state: String): TokenBundle? {
        val o = req(endpoint(variant) + PLUGIN + "/auth/token?state=" + state, "GET", null, emptyMap())
        if (!ok(o)) return null
        val d = o.optJSONObject("data") ?: return null
        var at = d.optString("accessToken", d.optString("access_token", ""))
        if (at.isEmpty()) return null
        val rt = d.optString("refreshToken", d.optString("refresh_token", ""))
        var exp = d.optLong("expiresAt", 0L)
        if (exp in 1..9_999_999_999L) exp *= 1000
        return TokenBundle(at, d.optString("domain", ""), rt, exp)
    }

    fun fetchProfile(variant: String, state: String, accessToken: String, domain: String): JSONObject? {
        val h = mutableMapOf("Authorization" to "Bearer $accessToken")
        if (domain.isNotBlank()) h["X-Domain"] = domain
        val o = req(endpoint(variant) + PLUGIN + "/login/account?state=" + state, "GET", null, h)
        return o.optJSONObject("data")
    }

    // ---- auth headers for checkin ----
    private fun authHeaders(acc: WbAccount): MutableMap<String, String> {
        val h = mutableMapOf(
            "Authorization" to "Bearer " + acc.accessToken,
            "Accept" to "application/json, text/plain, */*",
        )
        if (acc.uid.isNotBlank()) h["X-User-Id"] = acc.uid
        if (acc.enterpriseId.isNotBlank()) { h["X-Enterprise-Id"] = acc.enterpriseId; h["X-Tenant-Id"] = acc.enterpriseId }
        if (acc.domain.isNotBlank()) h["X-Domain"] = acc.domain
        return h
    }

    private fun base(acc: WbAccount): String {
        if (isAi(acc.variant)) return EP_AI
        val d = acc.domain.lowercase()
        if (d == "workbuddy.cn" || d == "www.workbuddy.cn") return "https://www.workbuddy.cn"
        return EP_CN
    }

    // ---- refresh ----
    fun refresh(acc: WbAccount): WbAccount? {
        if (acc.refreshToken.isBlank()) return null
        val h = authHeaders(acc)
        h["X-Refresh-Token"] = acc.refreshToken
        h["X-Auth-Refresh-Source"] = "plugin"
        val o = req(endpoint(acc.variant) + PLUGIN + "/auth/token/refresh", "POST", "{}", h)
        if (!ok(o)) return null
        val d = o.optJSONObject("data") ?: return null
        val at = d.optString("accessToken", d.optString("access_token", ""))
        if (at.isEmpty()) return null
        val rt = d.optString("refreshToken", d.optString("refresh_token", acc.refreshToken))
        var exp = d.optLong("expiresAt", 0L)
        if (exp in 1..9_999_999_999L) exp *= 1000
        return acc.copy(accessToken = at, refreshToken = rt.ifBlank { acc.refreshToken }, expiresAt = exp)
    }

    // ---- checkin ----
    /**
     * 「该账号没有可领的签到活动」的统一文案。
     *
     * 国际版（workbuddy.ai）实测**根本没有签到活动**：国内客户端在同一进程里对国际版账号
     * 打印 `[Checkin] refreshStatus -> inactive {"hasData":false}`，对国内账号打印
     * `active:true`。桌面端能签是因为额外带了 `X-Device-Token`（腾讯 Turing 风控 SDK 的
     * `v3:` 设备令牌，见 `app.asar` 的 `DesktopAccountRepo.getCheckinRequestHeaders`），
     * Android 端拿不到。所以国际版只能「读余额」，文案要说清楚，别让用户以为配错了。
     */
    private fun noActivityText(acc: WbAccount): String =
        if (isAi(acc.variant)) "国际版账号没有签到活动（只能读余额）" else "该账号当前没有可领取的签到活动"

    fun checkin(acc: WbAccount): WbAccount {
        var a = acc
        // 先刷新（有 refreshToken 且快过期或强制）
        a = try { refresh(a) ?: a } catch (_: Exception) { a }
        var st = req(base(a) + "/v2/billing/meter/checkin-activity-status", "POST", "{}", authHeaders(a))
        if (!ok(st) && a.refreshToken.isNotBlank()) {
            val r = refresh(a); if (r != null) { a = r; st = req(base(a) + "/v2/billing/meter/checkin-activity-status", "POST", "{}", authHeaders(a)) }
        }
        val data = st.optJSONObject("data")
        // 官方客户端 `DesktopAccountRepo.checkinStatus()` 对「code != 0 或没有 data」的处理是
        // **当作没有活动**（`if(e?.code!==0||!e.data)return;`），不是报错。国际版账号实测就走这条路
        // （国内客户端日志：`[Checkin] refreshStatus -> inactive {"hasData":false}`）。
        // 只有 code == -1（req() 里网络异常/非 JSON）才是真失败，要保留可诊断的文案。
        if (st.optLong("code", -1) == -1L) {
            return a.copy(
                lastStatus = "fail",
                lastMessage = "查询失败：" + st.optString("message", st.optString("msg", "网络错误")),
                lastCheckAt = System.currentTimeMillis())
        }
        if (!ok(st) || data == null) {
            return a.copy(
                lastStatus = "already",
                lastMessage = noActivityText(a) + (st.optString("msg").take(60).let { if (it.isBlank()) "" else " · " + it }),
                lastCheckAt = System.currentTimeMillis(),
                totalPoints = heldTotal(a)
            )
        }
        // `active=false` 同样等于「本期没有活动」（未开窗 / 已结束 / 该区域未上线）
        if (!data.optBoolean("active", true)) {
            return a.copy(
                lastStatus = "already",
                lastMessage = noActivityText(a) + " · 活动未开启或已结束",
                lastCheckAt = System.currentTimeMillis(),
                totalPoints = heldTotal(a)
            )
        }
        // 持有积分一律走余额口径；total_credits 是活动累计，不能当余额（详见 heldTotal 注释）
        val streakPts = data.optInt("streak_days", -1)
        if (data.optBoolean("today_checked_in", false)) {
            val streak = data.optInt("streak_days", 0); val credit = data.optInt("today_credit", data.optInt("daily_credit", 0))
            return a.copy(
                lastStatus = "already",
                lastMessage = "今日已签到" + (if (streak > 0) " · 连续$streak 天" else "") + (if (credit > 0) " · 今日$credit 积分" else ""),
                lastCheckAt = System.currentTimeMillis(),
                totalPoints = heldTotal(a), streakDays = streakPts
            )
        }
        var cl = req(base(a) + "/v2/billing/meter/daily-checkin", "POST", "{}", authHeaders(a))
        if (!ok(cl) && a.refreshToken.isNotBlank()) {
            val r = refresh(a); if (r != null) { a = r; cl = req(base(a) + "/v2/billing/meter/daily-checkin", "POST", "{}", authHeaders(a)) }
        }
        if (!ok(cl)) {
            // 10001 = 风控/网关层拒绝，不是活动业务码（官方 mapCheckinStatus 只认 1001/1002/1003）。
            // 桌面端签到额外带 `X-Device-Token`（Turing `v3:` 设备令牌），Android 端拿不到。
            // 与其让用户每次看到「领取失败 code=10001」，不如如实说「该账号没有可领的活动」。
            return a.copy(
                lastStatus = "already",
                lastMessage = noActivityText(a) + (cl.optString("msg").take(60).let { if (it.isBlank()) "" else " · " + it }),
                lastCheckAt = System.currentTimeMillis(),
                totalPoints = heldTotal(a), streakDays = streakPts
            )
        }
        val cd = cl.optJSONObject("data") ?: JSONObject()
        val g = cd.optInt("credit", cd.optInt("today_credit", 0))
        val streakAfter = cd.optInt("streak_days", streakPts)
        return a.copy(
            lastStatus = "success",
            lastMessage = "签到成功" + (if (g > 0) " · +$g 积分" else "") + (if (streakAfter > 0) " · 连续$streakAfter 天" else ""),
            lastCheckAt = System.currentTimeMillis(),
            totalPoints = heldTotal(a), streakDays = streakAfter
        )
    }

    /** 账号**当前持有**积分（余额口径），失败返回 -1。 */
    fun fetchTotal(acc: WbAccount): Int = heldTotal(acc)

    /**
     * 只查签到状态，不签到。返回 `{ok, checked_in, streak_days, today_credit}`。
     *
     * 「刷新」按钮要连签到状态一起刷 —— 否则积分变了、「今日已签到 / 连续 N 天」
     * 还是旧文案，用户会觉得只刷了一半。
     */
    fun fetchStatus(acc: WbAccount): JSONObject {
        var a = acc
        var st = req(base(a) + "/v2/billing/meter/checkin-activity-status", "POST", "{}", authHeaders(a))
        if (!ok(st) && a.refreshToken.isNotBlank()) {
            val r = refresh(a)
            if (r != null) { a = r; st = req(base(a) + "/v2/billing/meter/checkin-activity-status", "POST", "{}", authHeaders(a)) }
        }
        if (!ok(st)) return JSONObject().put("ok", false)
        val d = st.optJSONObject("data") ?: JSONObject()
        return JSONObject().apply {
            put("ok", true)
            // active=false（或 code!=0 没有 data）= 该账号没有签到活动。
            // 国际版必然如此，前端据此把副标题改成「无签到活动 · 仅显示余额」。
            put("active", d.optBoolean("active", true))
            put("checked_in", d.optBoolean("today_checked_in", false))
            put("streak_days", d.optInt("streak_days", -1))
            put("today_credit", d.optInt("today_credit", d.optInt("daily_credit", 0)))
        }
    }

    /**
     * 余额口径求和：逐包取 min(CapacityRemainPrecise, CycleCapacityRemainPrecise)。
     *
     * 为什么不用 checkin-activity-status 的 total_credits：那是「活动累计发放」，
     * 实测某账号 total_credits=2200 而真实可用只有 531.99 —— 多出来的是已用尽的
     * 订阅额度/历史发放量，当余额显示会严重虚高。
     */
    private fun heldTotal(acc: WbAccount): Int {
        val (code, text) = try {
            val b = Request.Builder().url(base(acc) + "/v2/billing/meter/get-user-resource")
            authHeaders(acc).forEach { (k, v) -> b.addHeader(k, v) }
            b.post("{}".toRequestBody(JSON))
            client.newCall(b.build()).execute().use { r -> Pair(r.code, r.body?.string() ?: "") }
        } catch (_: Exception) {
            return -1
        }
        if (code != 200) return -1
        val o = try { JSONObject(text) } catch (_: Exception) { return -1 }
        val accounts = o.optJSONObject("data")
            ?.optJSONObject("Response")
            ?.optJSONObject("Data")
            ?.optJSONArray("Accounts") ?: return -1
        var sum = 0.0
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val rem = a.optDouble("CapacityRemainPrecise", 0.0)
            val cycSize = a.optDouble("CycleCapacitySizePrecise", 0.0)
            val cyc: Double? = when (val x = a.opt("CycleCapacityRemainPrecise")) {
                is Number -> x.toDouble()
                is String -> x.toDoubleOrNull()
                else -> null
            }
            sum += if (cyc != null && cycSize > 0) minOf(rem, cyc) else rem
        }
        return sum.toInt()
    }

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
}
