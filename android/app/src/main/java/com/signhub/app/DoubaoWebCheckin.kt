package com.signhub.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 豆包签到（WebView 版）。
 *
 * ## 为什么必须走 WebView，不能用纯 HTTP
 *
 * V2.66 曾判定「a_bogus 不是前置校验，纯 OkHttp + Cookie 就能打」。那个结论只在**游客态**
 * 成立：没 cookie 时上游先回 `710012001 登录已过期`，签名层根本没走到。带上真 cookie 后
 * 立刻变成 `710010702 系统错误` —— 说明校验发生在鉴权之后。
 *
 * 签名不是全局函数，算不出来：
 *   · `window.byted_acrawler` / `window.webmssdk` 在真浏览器里恒为 `undefined`；
 *   · 签名由 SDK 在 fetch / XHR 层 monkey-patch 注入 query，证据是
 *     `s2-request-cache-runtime.62319c4a55.js` 里的
 *     `Object.freeze(["a_bogus","msToken","tea_uuid","web_id","web_tab_id"])`
 *     —— 这五个是**请求层自动附加的 query 名**；
 *   · `710010702` 在全部 433 个前端 bundle 里 0 命中，只存在于服务端，无法反推含义。
 *
 * 所以正确做法是：在**真实页面上下文**里发 fetch，让 SDK 自己签名，Cookie 由
 * `credentials:'include'` 自动带。请求指纹与官方页面完全一致，这是最不触发风控的形态。
 *
 * ## fetch 必须显式带 `Content-Type: application/json`（V2.73 修，之前漏了）
 *
 * 实测（同一页面上下文，三种写法对照）：不带该头 → **HTTP 403 + 空 `{}`**；带
 * `application/json` → HTTP 200 + 业务 JSON。网关（agw）要求 POST body 声明成 JSON，
 * 不声明直接拦掉，连业务层都到不了 —— 这就是用户 m04138 看到的 `code -1 {}`。
 * V2.70 注释里「不设 header 免得绕开 SDK 签名」的判断是错的。
 *
 * ## 接口方向（V2.69 已确认，别再改反）
 *
 *   · `POST /alice/ug/campaign/landing/get`  body `{badge_counts:{incentive:1}}` —— **不带 scene**；
 *   · `POST /alice/ug/campaign/landing/checkin` body `{scene, biz_params:{}}` ——
 *     `scene` 的值就是上一步响应里的 `campaign_group_code`。
 *
 * bundle 原文（`agent-explore_page.37bc01f6.js`）：
 * `DailyCheckIn({scene: t || void 0})`，`t = e.campaignGroupCode`。
 *
 * ## 风控相关（改动前先读）
 *
 *   · 一天只发 2 个请求：`landing/get` 一次、`landing/checkin` 一次。**不循环试 scene**；
 *   · 失败不重试（重试交给用户在界面上手动点，别自动打）；
 *   · UA 必须与登录页一致（同一个常量 [UA]），中途换 UA 等于换了台设备；
 *   · 已签直接返回 already，不发写请求。
 */
object DoubaoWebCheckin {

    /**
     * 豆包总开关。**V2.75 起为 false（功能搁置、界面隐藏）。**
     *
     * 为什么关：2026-11-24 用户 m04439 报「还是不行」，红框是
     * `签到失败（code 710010702 系统错误）`。此时 V2.74 的三个公共 query
     * （`aid`/`device_platform`/`version_code`）已全部带上，登录态也是真的，
     * 但 `landing/checkin` 仍返业务兜底码 —— 已排除网关拦截（V2.73 修了
     * `Content-Type`）、排除签名（V2.70 起走 WebView 页面内 fetch、SDK 自签名）、
     * 排除缺公共参数（V2.74 修了）。剩下的可能只有「该账号确实没有签到活动」
     * 或「还有没找到的必填字段」，两者都需要真实可签账号才能继续验证，
     * 手上没有 → 搁置。
     *
     * 关掉的效果：`LocalApi` 的 status/config/checkin/autoStatus/resetCreds/probe
     * 全部跳过豆包，前端 `renderProviderEditors()` / `renderStats()` 因为拿不到
     * `providers.doubao` / `status.providers[doubao]` 会自动不画它 —— 不需要改前端。
     * 代码与文档全部保留，改回 true 即可恢复，不用重写。
     */
    const val ENABLED = false

    private const val HOST = "https://www.doubao.com"
    private const val PAGE = "https://www.doubao.com/chat/"

    /**
     * 与 `LoginWebActivity` 的 WebView UA 保持**逐字一致**（那里是唯一真值）。
     *
     * 必须一致：登录时用 A 串、签到时用 B 串，服务端会把这两次请求当成两台设备，
     * 是风控最典型的特征。改这里就要同步改 `LoginWebActivity`，反之亦然。
     */
    const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val P_LANDING_GET = "/alice/ug/campaign/landing/get"
    private const val P_LANDING_CHECKIN = "/alice/ug/campaign/landing/checkin"

    /**
     * 必须随每个 `/alice/` 请求带上的公共 query（V2.74 修，之前一个都没带）。
     *
     * 为什么：2026-11-24 在同一页面上下文做参数消融（`_abl.py`），基线九参数全带
     * 返 `code 0`，逐个剔除的结果是 ——
     *   · 去掉 `aid`              → `710012001`
     *   · 去掉 `device_platform`  → `710012001`
     *   · 去掉 `version_code`     → `710012001`
     *   · 去掉其余六个（`os`/`pkg_type`/`web_platform`/`language`/`region`/
     *     `doubao_device_platform`）→ 仍 `code 0`
     * 再单独试 `aid`、`device_platform`、`aid+device_platform` 两两组合 → 全部
     * `710012001`。所以**这三个是必需的最小集**，缺一个上游就把请求当成未登录。
     *
     * 这也解释了用户 m04252 的 `710010702`：带真 Cookie 时上游能解析出会话，但缺公共
     * 参数导致校验失败，返回的是业务层兜底码（`0x2A51E74E`，不在前端 51 个枚举里），
     * 而不是游客态的 `710012001`。
     *
     * 取值来源是页面自己发的请求（`_hookchk.py` 抓到的真实 URL），`aid=497858` 是豆包
     * Web 端固定应用号，`version_code=20800` 是当前 Web 端版本号。版本号过旧会不会被
     * 拒没有验证过；若日后开始报错，先来这里改版本号。
     */
    private const val PUBLIC_Q = "?aid=497858&device_platform=web&version_code=20800"

    private val SESSION_KEYS = listOf("sessionid", "sid_tt", "uid_tt", "sid_guard")

    private val DONE_TEXTS = listOf("明天再来", "已签到", "已领取", "今日已签")

    /** 页面里等 SDK 装载完成的最长时间。实测首屏 JS 大概 1~3 秒。 */
    private const val SETTLE_MS = 2500L

    /** 单个 fetch 的超时。两个请求串起来不超过这个数太多。 */
    private const val FETCH_TIMEOUT_MS = 20000L

    fun hasSession(cookie: String): Boolean {
        val c = cookie.trim()
        if (c.isEmpty()) return false
        return SESSION_KEYS.any { c.contains("$it=") }
    }

    /**
     * 跑一次签到。
     *
     * 必须在主线程创建 WebView，所以整个流程包在 `Dispatchers.Main` 里；网络与解析
     * 都在页面内完成，Kotlin 侧只负责收 JSON。
     *
     * 返回的 [CheckinResult] 与其它 provider 同构，`provider` 固定 `"doubao"`。
     */
    suspend fun run(ctx: Context, cookie: String): CheckinResult = withContext(Dispatchers.Main) {
        val ck = cookie.trim()
        if (!hasSession(ck)) {
            return@withContext CheckinResult(
                "doubao", "fail",
                "缺少豆包 Cookie：请点「登录豆包」走网页登录，或手动粘贴含 sessionid 的整串 Cookie",
                authFailed = true,
            )
        }

        // 先把 cookie 灌进 WebView 的 CookieManager。
        // 为什么不用登录时留下的那份：进程重启后 CookieManager 可能是空的，
        // 而 dbCookie 是从加密存储里读出来的，两者不一定同步。
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for (kv in ck.split(";")) {
                val one = kv.trim()
                if (one.isEmpty()) continue
                cm.setCookie(HOST, one)
                cm.setCookie("$HOST/", one)
            }
            cm.flush()
        } catch (_: Exception) {
            // CookieManager 写不进去也不算致命，页面可能已经带着会话
        }

        val web = try {
            buildWebView(ctx)
        } catch (e: Exception) {
            return@withContext CheckinResult(
                "doubao", "fail",
                "WebView 初始化失败：${e.message ?: e.javaClass.simpleName}",
            )
        }

        val pageReady = CompletableDeferred<Boolean>()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageReady.isCompleted) pageReady.complete(true)
            }
        }

        try {
            web.loadUrl(PAGE)
            val loaded = withTimeoutOrNull(25000L) { pageReady.await() } ?: false
            if (!loaded) {
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "打不开豆包对话页（25 秒超时）：可能是网络不通，或本机 WebView 组件缺失",
                )
            }

            // 等 SDK 装载。签名器是异步注入的，页面 onPageFinished 时还不一定能用。
            withContext(Dispatchers.Main) {
                kotlinx.coroutines.delay(SETTLE_MS)
            }

            // ---- 第 0 步：先确认页面到底有没有登录 ----
            // 为什么必须先查：`hasSession` 只看 Cookie 里有没有 sessionid 这个名字，
            // 而过期会话的名字也还在（用户 m03836 的 bug —— 登录弹窗还开着，界面已经
            // 显示「已采集 2716 字符」）。带着死 Cookie 去打接口，上游会先回 710012001
            // 或直接给兜底码，从错误码根本分不清是「登录失效」还是「签名不对」。
            // 页面上的登录弹窗或右上角「登录」胶囊还在 = 没登录（用户 m03980 的 bug：
            // 弹窗没了但「登录」胶囊还在，只看弹窗会漏）。
            if (loginModalShown(web)) {
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "豆包登录态已失效：页面还挂着「登录」入口（存下来的 Cookie 是上一次的死会话）。" +
                        "请点「登录豆包」重新登录一次",
                    authFailed = true,
                )
            }

            // ---- 第一步：landing/get（不带 scene）----
            // 读请求失败重试一次是安全的（不写数据、不改状态），主要防「SDK 还没装好」。
            var getRaw = evalJson(web, jsGet())
            if (getRaw == null) {
                withContext(Dispatchers.Main) { kotlinx.coroutines.delay(2000L) }
                getRaw = evalJson(web, jsGet())
            }
            if (getRaw == null) {
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "页面内请求没有返回（landing/get）" +
                        lastJsErr.ifBlank { "：WebView 里的 fetch 失败或被拦截" },
                )
            }
            val land = try { JSONObject(getRaw) } catch (_: Exception) { null }
            val biz = land?.optInt("code", -1) ?: -1
            if (biz == 710012001) {
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "豆包登录态已失效（710012001）：请重新登录网页版豆包",
                    authFailed = true,
                )
            }
            if (biz != 0) {
                val msg = land?.optString("msg").orEmpty().ifBlank { getRaw.take(160) }
                // `code -1` 且响应体是空对象 = 网关层直接拦掉了，没到业务逻辑。
                // V2.70 时就是这个现象（403 + `{}`），根因是 fetch 没带 Content-Type。
                val why = when {
                    biz == -1 && getRaw.trim() in listOf("{}", "", "null") ->
                        "返回体是空的，说明请求被网关拦掉（V2.73 起已带 Content-Type，若仍出现请把这段发回来）"
                    biz == 710010702 -> "这个码在页面内请求下仍然出现，说明不是签名问题，多半是该账号没有签到活动。"
                    else -> "请把这段发回来排查。"
                }
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "取不到豆包活动数据（code $biz $msg）。$why",
                )
            }

            // ---- 第二步：判断今天签没签 ----
            val data = land?.opt("data")
            val scene = findCampaignGroupCode(data)
            val already = isCheckedIn(data)
            if (already) {
                return@withContext CheckinResult(
                    "doubao", "already",
                    if (scene.isNotBlank()) "今天已经签过了（$scene）" else "今天已经签过了",
                )
            }

            // ---- 第三步：landing/checkin（一次，不循环）----
            val checkRaw = evalJson(web, jsCheckin(scene)) ?: run {
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "页面内请求没有返回（landing/checkin）" + lastJsErr,
                )
            }
            val co = try { JSONObject(checkRaw) } catch (_: Exception) { null }
            val cbiz = co?.optInt("code", -1) ?: -1
            val cmsg = co?.optString("msg").orEmpty().ifBlank { co?.optString("message").orEmpty() }

            if (cbiz == 710012001) {
                return@withContext CheckinResult(
                    "doubao", "fail",
                    "豆包登录态已失效（710012001）：请重新登录",
                    authFailed = true,
                )
            }
            if (cbiz == 0) {
                val got = findIntByKey(co?.opt("data"), "credit", "amount", "reward", "quota")
                return@withContext CheckinResult(
                    "doubao", "success",
                    when {
                        cmsg.isNotBlank() -> "签到成功：$cmsg"
                        scene.isNotBlank() -> "签到成功（$scene）"
                        else -> "签到成功"
                    },
                    points = if (got > 0) "+$got" else "",
                    totalPoints = got,
                )
            }
            return@withContext CheckinResult(
                "doubao", "fail",
                "签到失败（code $cbiz ${cmsg.ifBlank { checkRaw.take(160) }}）",
            )
        } catch (e: Exception) {
            CheckinResult("doubao", "fail", "签到异常：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                web.loadUrl("about:blank")
                web.destroy()
            } catch (_: Exception) {
                // 销毁失败不影响结果
            }
        }
    }

    /**
     * 页面 → Kotlin 的回调桥。
     *
     * 为什么必须有它：`WebView.evaluateJavascript(js, cb)` 的 `cb` 只收**脚本的返回值**，
     * 不会等 Promise —— 直接 `evaluateJavascript("fetch(...).then(...)")` 拿回来的是
     * `undefined`。所以异步结果只能靠 `@JavascriptInterface` 回抛。
     */
    class Bridge {
        @Volatile
        var slot: CompletableDeferred<String?>? = null

        @JavascriptInterface
        fun onResult(text: String?) {
            slot?.let { if (!it.isCompleted) it.complete(text) }
        }
    }

    /** 在页面里跑一段 JS 并把它的回调结果等回来；超时、异常或 `__ERR__` 返 null。 */
    private suspend fun evalJson(web: WebView, js: String): String? {
        val slot = CompletableDeferred<String?>()
        bridge.slot = slot
        lastJsErr = ""
        web.evaluateJavascript(js, null)
        val got = withTimeoutOrNull(FETCH_TIMEOUT_MS) { slot.await() }
        bridge.slot = null
        if (got.isNullOrBlank()) return null
        if (got.startsWith("__ERR__")) {
            lastJsErr = got.removePrefix("__ERR__")
            return null
        }
        return got
    }

    /** 最近一次页面内 fetch 的异常文本，用来在失败文案里带上真实原因。 */
    @Volatile
    private var lastJsErr: String = ""

    /** 每次 [run] 复用一个桥实例，避免重复 addJavascriptInterface 造成泄漏。 */
    private val bridge = Bridge()

    /**
     * 建一个只在签到期间存活的 WebView。
     *
     * 为什么要关图片：这个 WebView 只用来跑 fetch，渲染图片纯属浪费流量与时间
     * （对话页首屏图片很多，关掉能省 2~4 秒）。
     *
     * `@SuppressLint("SetJavaScriptEnabled")` 是必须的 —— 打开 JS 在静态检查里是告警，
     * 但这里离了 JS 就跑不了（签名器是页面里的 SDK 注入的）。
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
        w.addJavascriptInterface(bridge, "SHDoubao")
        return w
    }

    /**
     * 页面上还挂着「未登录」的证据吗（V2.72 起同时看两样）。
     *
     * 判据一：登录弹窗在不在 —— 用弹窗独有文案，不搜「登录」两个字（页面别处也有）。
     * 判据二：右上角还有没有「登录」胶囊 —— 用户 m03980 的截图里页面已经是正常对话页、
     *         弹窗没了，但右上角「登录」黑胶囊还挂着，说明这份 Cookie 根本没登录。
     *         只看判据一会漏掉这种情况（V2.71 就漏了）。
     *
     * 读不到（异常 / 空结果）按「未登录」处理：宁可让用户重新登录一次，
     * 也不要拿一份死 Cookie 去撞上游、再拿 710010702 猜半天。
     */
    private suspend fun loginModalShown(web: WebView): Boolean {
        val raw = evalJson(web, jsLoginModal())
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return true
        return t.contains("yes")
    }

    private fun jsLoginModal(): String = """
        (function(){
          var t=(document.body&&document.body.innerText)||'';
          var keys=['使用豆包或飞书账号登录','飞书一键登录','抖音一键登录','请输入手机号'];
          for(var i=0;i<keys.length;i++){if(t.indexOf(keys[i])>=0){SHDoubao.onResult('yes');return;}}
          var els=document.querySelectorAll('button,a,div,span');
          for(var j=0;j<els.length;j++){var e=els[j];
            if(e.children&&e.children.length>0)continue;
            var s=(e.innerText||'').trim();
            if(s!=='登录'&&s!=='登录/注册'&&s!=='立即登录')continue;
            var r=e.getBoundingClientRect();
            if(r.width<=2||r.height<=2)continue;
            if(r.top<0||r.top>160)continue;
            if(r.left<window.innerWidth*0.5)continue;
            SHDoubao.onResult('yes');return;}
          SHDoubao.onResult('no');
        })()
    """.trimIndent()

    /**
     * 页面内发 landing/get。
     *
     * **必须显式带 `Content-Type: application/json`**（V2.73 修，V2.70 的注释写反了）。
     * 2026-11-24 用 Playwright 在同一页面上下文实测三种写法：
     *   · 不带 Content-Type          → **HTTP 403**，响应体是空 `{}`（就是用户 m04138 的 `code -1 {}`）
     *   · 带 application/json        → HTTP 200 + 业务 JSON
     *   · 带 application/json+Accept → 同上
     * 所以之前「不设 header 免得绕开 SDK 签名」的判断是错的：SDK 的拦截器不看这个头，
     * 而网关（agw）要求 POST body 声明成 JSON，不声明就直接 403 拦掉。
     *
     * **还必须带上 [PUBLIC_Q] 那三个公共 query**（V2.74 修）：不带时上游把请求当未登录，
     * 回 `710012001`；带真 Cookie 时回业务兜底码 `710010702`。两者都不是签名问题。
     *
     * 结果通过 `SHDoubao.onResult(...)` 回抛（`evaluateJavascript` 等不了 Promise）。
     */
    private fun jsGet(): String = """
        (function(){
          fetch('$P_LANDING_GET$PUBLIC_Q', {
            method: 'POST',
            credentials: 'include',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({badge_counts:{incentive:1}})
          }).then(function(r){
              return r.text().then(function(t){
                if(!r.ok){ SHDoubao.onResult('__ERR__HTTP ' + r.status + ' ' + String(t).slice(0,120)); return; }
                SHDoubao.onResult(t);
              });
            })
            .catch(function(e){ SHDoubao.onResult('__ERR__' + e); });
        })()
    """.trimIndent()

    private fun jsCheckin(scene: String): String {
        val body = if (scene.isNotBlank()) {
            """{"scene":${JSONObject.quote(scene)},"biz_params":{}}"""
        } else {
            """{"biz_params":{}}"""
        }
        return """
            (function(){
              fetch('$P_LANDING_CHECKIN$PUBLIC_Q', {
                method: 'POST',
                credentials: 'include',
                headers: {'Content-Type':'application/json'},
                body: JSON.stringify($body)
              }).then(function(r){
                  return r.text().then(function(t){
                    if(!r.ok){ SHDoubao.onResult('__ERR__HTTP ' + r.status + ' ' + String(t).slice(0,120)); return; }
                    SHDoubao.onResult(t);
                  });
                })
                .catch(function(e){ SHDoubao.onResult('__ERR__' + e); });
            })()
        """.trimIndent()
    }

    /** 从活动页响应里取 `campaign_group_code`，它就是要传给 checkin 的 scene。 */
    private fun findCampaignGroupCode(node: Any?): String {
        var found = ""
        walk(node) { k, v ->
            if (found.isNotBlank()) return@walk
            val key = k.lowercase().replace("-", "_")
            if (key == "campaign_group_code" || key == "campaigngroupcode" || key == "group_code") {
                val s = v?.toString()?.trim().orEmpty()
                if (s.length in 3..64) found = s
            }
        }
        return found
    }

    /**
     * 今天签没签。
     *
     * 优先用布尔字段（bundle 里 `agent-explore_page.37bc01f6.js` 有
     * `daily_checkin.checked_in_today`），其次才退回卡片文案。
     * 都读不到返 false —— 宁可多打一次写请求，也不要因为猜错漏签。
     */
    private fun isCheckedIn(node: Any?): Boolean {
        var hit = false
        walk(node) { k, v ->
            if (hit) return@walk
            val key = k.lowercase()
            if (key.contains("checked_in") || key.contains("checkin") ||
                key.contains("signed") || key.contains("sign_status")
            ) {
                when {
                    v is Boolean && v -> hit = true
                    v?.toString()?.lowercase() in listOf("1", "true", "done", "checked", "signed", "already") -> hit = true
                }
            }
            val txt = v?.toString().orEmpty()
            if (txt.length in 2..40 && DONE_TEXTS.any { txt.contains(it) }) hit = true
        }
        return hit
    }

    private fun walk(node: Any?, sink: (String, Any?) -> Unit) {
        when (node) {
            is JSONObject -> {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = node.opt(k)
                    sink(k, v)
                    if (v is JSONObject || v is JSONArray) walk(v, sink)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val v = node.opt(i)
                sink("", v)
                if (v is JSONObject || v is JSONArray) walk(v, sink)
            }
        }
    }

    private fun findIntByKey(node: Any?, vararg keys: String): Int {
        var found = -1
        walk(node) { k, v ->
            if (found >= 0) return@walk
            val key = k.lowercase()
            if (keys.any { key.contains(it) }) {
                val n = when (v) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull() ?: -1
                    else -> -1
                }
                if (n > 0) found = n
            }
        }
        return found
    }
}
