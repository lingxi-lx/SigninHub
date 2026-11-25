package com.signhub.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 华为码道「静默重登」——让 Cookie 不过期、不用反复输手机号（V2.79）。
 *
 * ## 为什么需要它
 *
 * 用户 m05846 要求「cookie 不过期，自动续签，软件能长期运作」。实测（`_hw_cookie.py` /
 * `_hw_roll.py` / `_hw_sso.py`，2026-11-25）结论是：
 *
 *   · 华为**没有** Trae 那种「长期凭据换短期 token」的换发端点 ——
 *     `chat/home` 下发的 `devclouddevuibjJ_SESSION_ID` / `devclouddevuibjtcftk` /
 *     `devclouddevuibjagencyID` **全部是 Session 级**（无 `Expires` / `Max-Age`），
 *     会话静置 30~60 分钟就失效；
 *   · 连打三次同一份 Cookie，`SESSION_ID` **不会滚动续期**（只有 WAF 的三个 cookie 会重发）；
 *   · 主 bundle（4.3 MB）里 `refresh` / `renew` / `keepalive` / `heartbeat` 全都不指向
 *     任何续期接口 —— `keepalive` 是 Vue 的 `<KeepAlive>`，`heartbeat` 是 SSE 心跳；
 *   · `auth.huaweicloud.com/authui/login.html`（7711 B）里 `hwssotoken` / `ssoToken` /
 *     `rememberMe` / `keepLogin` / `autoLogin` **全部 0 命中**。
 *
 * 所以「纯 HTTP 续签」这条路不存在。**但「静默重登」存在**：
 *
 * `chat/home` 的会话失效时，服务端会 302 到
 * `auth.huaweicloud.com/authui/login?service=…`；只要 **auth 域**的长期凭据还在，
 * 页面就会自动回跳并在 `chat/home` 域下发一份**全新的** `SESSION_ID`，全程不需要
 * 用户输手机号。而登录时 WebView 本来就走过 auth 域，那些 cookie 一直在
 * `CookieManager` 里 —— 以前只是**从来没采集过**（只 `getCookie` 了 devcloud 域）。
 *
 * ## 做法
 *
 * 1. 把已存的 devcloud Cookie 与 auth Cookie 一起灌回 `CookieManager`
 *    （进程重启后它可能是空的，而两者都从加密存储里读出来）；
 * 2. 起一个**不可见**的 WebView 打开 [HOME]，让页面自己走 302 往返；
 * 3. 页面停在 `chat/home` 且 cookie 里出现新的 `SESSION_ID` → 采集两份 Cookie 返回；
 * 4. 调用方（[HwCheckin.run]）拿新 Cookie 重跑一次签到，并把新值落盘。
 *
 * ## 代价
 *
 * 只在**签到失败且判定为登录态问题**时才走这条路，成功时不走。一次约 2~4 秒。
 *
 * ## 失败语义
 *
 * 返回 `null` 表示「救不回来」——auth 域的凭据也过期了，只能让用户重新登录一次。
 * 调用方必须保留原 Cookie，不能因为这次失败就把用户的账号判死。
 */
object HwRenew {

    /** 码道首页。会话有效时直接渲染，失效时 302 到 auth 域。 */
    private const val HOME = "https://devcloud.cn-north-4.huaweicloud.com/chat/home"

    /** auth 域。登录页与 SSO 都在这下面，它的 cookie 才是「长期凭据」。 */
    private const val AUTH = "https://auth.huaweicloud.com"

    /** 门户域，只用于读积分。 */
    private const val PORTAL = "https://codearts.huaweicloud.com"

    /**
     * 与 [LoginWebActivity] 的 WebView UA 保持逐字一致。
     *
     * 必须一致：登录时用 A 串、续期时用 B 串，服务端会把这两次当成两台设备 ——
     * 这是风控最典型的特征（与 `DoubaoWebCheckin.UA` 同一个理由）。
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 整轮静默重登的上限。超过就认输，别把签到任务拖到超时。 */
    private const val TIMEOUT_MS = 18_000L

    /** 页面 onPageFinished 之后再多等一会儿，让 302 链走完。 */
    private const val SETTLE_MS = 1_200L

    /**
     * 静默重登的结果。
     *
     * @param cookie        devcloud 域的新 Cookie（含新的 `SESSION_ID`）
     * @param authCookie    auth 域的新 Cookie（下一次续期靠它）
     * @param portalCookie  门户域 Cookie（可能为空，只影响积分显示）
     */
    data class Renewed(
        val cookie: String,
        val authCookie: String,
        val portalCookie: String,
    )

    /**
     * 尝试用 auth 域的长期凭据换一份新的 devcloud 会话。
     *
     * @param cookie     当前（已失效的）devcloud Cookie，用来把旧会话灌回 CookieManager
     * @param authCookie auth 域 Cookie —— **没有它必然失败**（登录页会停在输手机号那一步）
     * @param portalCookie 门户域（codearts）自己的 Cookie；为空就不灌门户域，
     *                   **绝不拿 authCookie 顶替**（见 [seedCookies] 的 P3-3 说明）
     * @return 成功返回新的三份 Cookie；救不回来返回 `null`
     */
    suspend fun renew(
        ctx: Context,
        cookie: String,
        authCookie: String,
        portalCookie: String = "",
    ): Renewed? {
        if (cookie.isBlank()) return null
        // auth 域一个 cookie 都没有 → 登录页不可能自动回跳，省掉这 18 秒。
        if (authCookie.isBlank()) return null
        return withContext(Dispatchers.Main) {
            val web = try {
                buildWebView(ctx)
            } catch (_: Exception) {
                return@withContext null
            }
            try {
                seedCookies(cookie, authCookie, portalCookie)
                val done = CompletableDeferred<Boolean>()
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!done.isCompleted) done.complete(true)
                    }
                }
                web.loadUrl(HOME)
                val loaded = withTimeoutOrNull(TIMEOUT_MS) { done.await() } ?: false
                if (!loaded) return@withContext null
                // 302 链可能分几次 onPageFinished，等页面稳定再读 cookie
                delay(SETTLE_MS)

                val fresh = try { CookieManager.getInstance().getCookie(HOME) } catch (_: Exception) { null }
                if (fresh.isNullOrBlank()) return@withContext null
                if (!LocalCheckin.hwHasSession(fresh)) return@withContext null
                // 没有任何变化说明会话压根没刷新（还是那份死的），别拿它去重打上游
                if (fresh == cookie) return@withContext null
                // agencyID 是「会话收全」的标志；缺了它签到会报头组不完整而不是 401，
                // 排查起来更绕，所以宁可这次不换。
                if (!fresh.contains("agencyID=")) return@withContext null

                val newAuth = try { CookieManager.getInstance().getCookie(AUTH) } catch (_: Exception) { null } ?: ""
                val newPortal = try { CookieManager.getInstance().getCookie(PORTAL) } catch (_: Exception) { null } ?: ""
                Renewed(
                    cookie = fresh,
                    authCookie = newAuth.ifBlank { authCookie },
                    portalCookie = newPortal,
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching {
                    web.loadUrl("about:blank")
                    web.destroy()
                }
            }
        }
    }

    /**
     * 把已存的三份 Cookie 各自灌回对应的域。
     *
     * 必须逐条 `setCookie` 而不是整串塞：`CookieManager.setCookie` 一次只认一条
     * `name=value`（带属性会静默失败），整串塞进去等于什么都没写。
     *
     * BUGS-v2.63 P3-3：以前这里是 `listOf(HOME to cookie, AUTH to authCookie, PORTAL to authCookie)`
     * —— 把 auth 域的凭据整串灌进了门户域。后果有两层：① 续期后 `getCookie(PORTAL)` 取到的是
     * 「门户原有 Cookie + 被灌进去的 auth 域 Cookie」的混合体，落盘成 `hwPortalCookie` 后下一次
     * 又灌回去，滚雪球式累积；② auth 域的长期凭据被当作 Cookie 头发给门户接口，是跨域凭据外泄面。
     * 现在门户域只收门户自己的 Cookie。
     */
    private fun seedCookies(cookie: String, authCookie: String, portalCookie: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for ((domain, raw) in listOf(HOME to cookie, AUTH to authCookie, PORTAL to portalCookie)) {
            if (raw.isBlank()) continue
            for (kv in raw.split(";")) {
                val one = kv.trim()
                if (one.isEmpty() || !one.contains("=")) continue
                runCatching {
                    cm.setCookie(domain, one)
                    cm.setCookie(domain + "/", one)
                }
            }
        }
        runCatching { cm.flush() }
    }

    /**
     * 建一个只在续期期间存活的 WebView。
     *
     * 关图片：这条路径只用来走 302，不渲染任何内容，首屏图片纯属浪费
     * （`chat/home` 首屏图很多，关掉能省 2~4 秒，与 [DoubaoWebCheckin] 同一处理）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(ctx: Context): WebView {
        val w = WebView(ctx.applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.userAgentString = UA
        w.settings.loadsImagesAutomatically = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        return w
    }
}
