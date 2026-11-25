package com.signhub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * App 内登录页：打开官方登录网页，登录后把凭据抓下来存到本机（纯手机，不依赖电脑）。
 *   workbuddy -> 打开 codebuddy.cn，全量 dump localStorage/sessionStorage + 原生 Cookie，
 *                在其中找 accessToken/refreshToken/uid（或 JWT），抓到即存
 *   trae      -> 打开 trae.cn，读 localStorage['Cloud-IDE-Token']，并本地生成 16 位设备号
 *   minimax   -> 打开 agent.minimax.cn，读 localStorage['_token']（网页端无前缀裸串），
 *                兜底再扫原生 Cookie（_minimax_token）
 * 自动轮询；抓不到时可点「完成」手动再抓，或点「粘贴」（会预填候选）手动保存。
 */
class LoginWebActivity : Activity() {

    companion object {
        const val EXTRA_PROVIDER = "provider"
        /**
         * true = 这次登录的结果追加到该服务的「多账号列表」，不覆盖设置页里的主账号。
         * 多账号管理页（MultiAccountsActivity）打开登录页时传 true。
         */
        const val EXTRA_TO_LIST = "to_list"
        const val P_WB = "workbuddy"
        /**
         * WorkBuddy **国际版**（workbuddy.ai）。
         *
         * 为什么用「同一个 provider + 后缀」而不是新增一个 provider：
         *   国际版与国内版是**同一套协议**，只是域名不同（实测两边
         *   `/v2/billing/meter/checkin-activity-status` 未登录时返回的错误体一字不差）。
         *   所以登录流程、凭据解析、落库字段全部复用，唯一差别是 endpoint 与 platform
         *   （`workbuddy` / `workbuddy-ai`）。用后缀表达「同一家的另一个区域」，
         *   比再复制一套 Activity 省事，也不会让 provider 列表多出一家。
         */
        const val P_WB_AI = "workbuddy-ai"
        const val P_TRAE = "trae"
        const val P_MM = "minimax"
        const val P_QD = "qoder"
        /**
         * 华为码道（DevCloud PromptCenter）。
         *
         * 与其他几家不同：没有 accessToken 可以抓，鉴权就是**整串 Cookie**
         * （`devclouddevuibjJ_SESSION_ID` 是 HttpOnly，页面 JS 读不到，
         *  但 `CookieManager.getCookie()` 走的是引擎层，能读到 → 手机端反而比电脑端省事）。
         * 所以这个 provider 的「登录流程」= 打开 chat/home 让用户登录 + 等会话落全 + 采集。
         */
        const val P_HW = "huawei"
        /**
         * 豆包（www.doubao.com）。
         *
         * 与华为码道同类：没有 token 可抓，鉴权就是**整串 Cookie**
         * （`sessionid` / `sid_tt` / `uid_tt` / `sid_guard` 里至少一个，
         *  其中 `sessionid` 是 HttpOnly，页面 JS 读不到，但 `CookieManager.getCookie()`
         *  走引擎层能读到）。
         *
         * 与其他几家的关键区别：**不需要算 a_bogus 签名**。实测服务端先查登录态、
         * 签名不参与前置校验，所以拿到 Cookie 后纯 OkHttp 直打就行，不碰 WebView。
         * 详见 `DoubaoWebCheckin.kt` 类注释。
         */
        const val P_DB = "doubao"
        /**
         * 智谱清言（chatglm.cn）。
         *
         * 与豆包/华为同类：鉴权就是**整串 Cookie**（`chatglm_token` + `chatglm_refresh_token`），
         * 页面 JS 也能读到（这两个不是 HttpOnly），但走 `CookieManager` 更省事。
         *
         * **判据必须看 JWT 里的 `is_guest`，不能只看有没有 `chatglm_token`**：
         * 未登录游客也会拿到一整套 token（421 B + 423 B），只判存在会把游客当已登录
         * （zaoba 项目 `src/login-one.js:30-34` 记录过同一个坑：一打开就判成功、4 秒后自动关窗，
         *  用户来不及扫码）。判据收在 `ZhipuCheckin.hasSession()`。
         *
         * 签到不需要 WebView：`X-Sign` 能本地算（md5 + 时间戳校验位改写），见 `ZhipuCheckin`。
         */
        const val P_ZP = "zhipu"
        /**
         * 百度千帆 / 百度搭子 DuMate（console.bce.baidu.com）。
         *
         * 鉴权同样是整串 Cookie，关键是 `BDUSS`（登录态）与 `bce-user-info`（CSRF 令牌）。
         * 游客态这两个都没有 —— 实测游客只有 `BAIDUID` / `HMACCOUNT` / `ppfuid` / `CASSSID` 等
         * 十几个无关 cookie，所以「两个都在」就是可靠判据。
         *
         * 登录页会 302 到 `login.bce.baidu.com`（WAP 版），手机 UA 下能正常走
         * 「百度账号 / 发送验证码 / 百度APP一键登录」流程。
         */
        const val P_QF = "qianfan"

        // 顶栏胶囊按钮配色（与 SignDialog / 主界面同一套）：
        //   BRAND→BRAND2 渐变是主操作，「确认」用它；
        //   其余按钮用 BTN_BG 半透明底 + BTN_LINE 细描边。
        private const val BRAND = 0xFF6C8CFF.toInt()
        private const val BRAND2 = 0xFF9B6CFF.toInt()
        private const val BTN_BG = 0xFF20273A.toInt()
        private const val BTN_LINE = 0xFF2E3648.toInt()

        // ---- Qoder CN device flow 常量（从 Qoder CN 客户端 app.asar 逆出，prod 环境）----
        private const val QD_CLIENT_ID = "732aef47-9cf2-46a2-95fe-4cebb5d0d1fa"
        private const val QD_AUTH_BASE = "https://qoder.cn"
        private const val QD_OPENAPI_BASE = "https://openapi.qoder.com.cn"
        private const val QD_VERIFIER_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /**
         * 豆包采集探测的三态结论（V2.72）。
         *
         * 为什么要有 [DB_UNKNOWN] 这一态：刚登录成功时页面 SDK 可能还没装好，
         * 页面内 fetch 会失败。把「判断不了」当成「未登录」会把用户刚拿到的有效登录态清掉，
         * 所以必须与「确定未登录」分开 —— 这与项目里「凭据判断不了必须与否分开」是同一条铁律。
         */
        private const val DB_YES = 1
        private const val DB_NO = 0
        private const val DB_UNKNOWN = -1
    }

    private lateinit var provider: String
    private lateinit var web: WebView
    private lateinit var status: TextView
    // saved / wbState 会被轮询线程写、主线程读（「完成」按钮判断、extractNow 读 state）。
    // 不加 @Volatile 时 JIT 可能把值缓存在寄存器里，表现为「明明登录成功了，按钮还提示未登录」。
    @Volatile private var saved = false
    private var toList = false
    private var lastCandidate = ""
    private var tokenOnlyCandidate = ""
    @Volatile private var wbState: String? = null
    private var pollThread: Thread? = null
    /** MiniMax 网页里顺手 dump 到的昵称（用户 m16091）；空串表示没读到。 */
    @Volatile private var mmNickname = ""

    /**
     * Activity 已销毁标记。
     *
     * 轮询线程最长跑 10 分钟（startWbPoll / startQdPoll），用户中途退出登录页后，
     * 线程仍在跑并在回调里碰 status / finish() —— 对已销毁的 Activity 操作视图会抛
     * IllegalStateException（或被 ROM 记一笔）。所有回主线程的地方统一走 [ui]。
     */
    @Volatile private var destroyed = false

    // Qoder device flow 的一次性参数（登录页与轮询必须用同一组）
    private var qdVerifierValue: String? = null
    private var qdNonceValue: String? = null
    private var qdMachineValue: String? = null
    private var qdRefreshValue: String? = null

    /** MiniMax 被站点导到 Google 页时纠正回国内页的次数（防来回弹） */
    private var mmBounce = 0

    /**
     * 是否已有一个 MiniMax token 探针在飞。
     *
     * 1.5 秒轮询 + `onPageFinished` 兜底都会走到 `persistMm`，不加这个闸门会
     * 同时起好几个网络请求；探针本身要打一次 `signin/status`，堆起来反而更慢。
     */
    @Volatile
    private var probeBusy = false

    /**
     * 华为：页面加载完成的次数。
     *
     * 用来区分「打开就是已登录（上次会话还在）」与「用户真的走了一遍登录」：
     * 首次加载就自动采集，用户根本没机会输手机号（用户 m21086 报的 bug）。
     * 登录流程必然是 chat/home → 登录页 → 回 chat/home，所以只有第 2 次及以后
     * 的加载才自动采集；第一次只提示，等用户自己点「确认」或走完登录。
     */
    private var hwLoads = 0

    /** 华为：是否正在采集（替代用 [saved] 当闸门 —— 那个会把「再采一次」也挡掉）。 */
    @Volatile
    private var hwBusy = false

    /** 豆包：是否正在采集（与 hwBusy 同理，避免「再采一次」被 saved 永久挡住）。 */
    @Volatile
    private var dbBusy = false

    /**
     * 华为：历史遗留的门户采集标记。
     *
     * 积分主路径已改到签到同域（`/chat/snap-manager/v1/package/overview`），
     * 不再需要跑 codearts 门户，这个标记已无实际用途，保留只为兼容
     * [hwPortalCapture] 的空壳实现。
     */
    private var hwPortalPending = false

    /**
     * 当前页面 URL 的快照。
     *
     * 为什么单独存一份：JS 桥的回调跑在 WebView 的 JavaBridge 线程上，
     * 在非 UI 线程调 WebView.getUrl() 是未定义行为（可能直接崩）。
     * 所以在 UI 线程的导航回调里更新这个字段，桥线程只读它。
     */
    @Volatile
    private var currentUrl: String = ""

    /**
     * 当前页面是不是本服务认可的登录域？
     *
     * 以前 JS 桥 `SignHubLogin` 无条件下接受 onToken/onDump/onMmToken：
     * 页面一旦被导航到任意第三方站点（shouldOverrideUrlLoading 一律 return false，
     * 不拦任何跳转），那个站点就能直接调 `SignHubLogin.onToken('{"accessToken":"..."}')`
     * 把一份伪造凭据写进本地设置 —— 用户之后所有签到都打到一个攻击者控制的账号上，
     * 或者干脆拿到一个无效 token 让功能永久报错。
     * 现在按 provider 限定可信域，跨域来的数据一律丢弃。
     */
    private fun isTrustedOrigin(): Boolean = isTrustedUrl(currentUrl)

    /**
     * 判断任意 URL 是否属于本 provider 的可信登录域。
     *
     * P2R6（BUGS-v2.2）：[onUrl] 是喂给 [currentUrl] 的唯一入口，而
     * [isTrustedOrigin] 的唯一判据就是 [currentUrl]。如果 `onUrl()` 不做校验，
     * 页面一旦被 XSS 或被中间人注入一段 `SignHubLogin.onUrl('https://www.trae.cn/')`，
     * 白名单立刻变真，随后伪造的 `onToken` 凭据就会被正常接收并落盘 ——
     * 等于把守门人自己交出去了。所以这里抽成按 URL 判定的形式，`onUrl` 复用它。
     */
    private fun isTrustedUrl(u: String): Boolean {
        if (u.isBlank()) return false
        val host = try {
            java.net.URI(u).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        val allowed = when (provider) {
            P_WB, P_WB_AI -> listOf("codebuddy.cn", "workbuddy.cn", "workbuddy.ai")
            P_TRAE -> listOf("trae.cn", "trae.ai")
            P_MM -> listOf("minimax.cn", "minimaxi.com")
            P_QD -> listOf("qoder.cn", "qoder.com", "qoder.sh")
            P_HW -> listOf("huaweicloud.com", "codearts.huaweicloud.com")
            P_DB -> listOf("doubao.com")
            // 智谱清言：登录、会员中心、SSO 回跳都在 chatglm.cn 下（含 bigmodel.cn 的跳转中转）
            P_ZP -> listOf("chatglm.cn", "bigmodel.cn")
            // 百度：登录页在 login.bce.baidu.com，账号体系走 passport.baidu.com，
            // 业务域是 console.bce.baidu.com —— 三者必须都在白名单里，否则登录成功后
            // 回跳那一刻会被判成「不在域内」，采集直接放弃。
            P_QF -> listOf("bce.baidu.com", "baidu.com", "passport.baidu.com")
            else -> emptyList()
        }
        return allowed.any { host == it || host.endsWith("." + it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = intent.getStringExtra(EXTRA_PROVIDER) ?: P_WB
        toList = intent.getBooleanExtra(EXTRA_TO_LIST, false)

        val bg = Color.parseColor("#0F1115")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // 顶栏改两行：状态独占一行（原来 4 个按钮把宽度吃光，文字被压成一列单字），
        // 按钮第二行等分。dp 换算走 resources，避免不同密度下按钮挤成一团。
        val dp = resources.displayMetrics.density
        val topWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151922"))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }
        status = TextView(this).apply {
            text = when (provider) {
                P_TRAE -> "登录 Trae（手机号/验证码最快）"
                P_MM -> "在下方网页登录 MiniMax，登录后自动读取 token"
                P_QD -> "在下方网页登录 Qoder，登录后自动取回 token"
                P_WB_AI -> "登录 WorkBuddy 国际版（workbuddy.ai）；登录后自动保存"
                P_HW -> "在下方登录华为码道（devcloud），回到 chat/home 后自动采集 Cookie"
                P_DB -> "在下方登录豆包（www.doubao.com），登录后自动采集 Cookie"
                P_ZP -> "在下方登录智谱清言（chatglm.cn），登录后自动采集 Cookie"
                P_QF -> "在下方登录百度智能云（登录后回到 console.bce.baidu.com），自动采集 Cookie"
                else -> "登录后自动读取凭据"
            }
            setTextColor(Color.parseColor("#E6EAF2"))
            textSize = 12.5f
            // 顶栏高度锁死两行（用户 m13898）：文案在「1 行 / 2 行 / 3 行」之间切换时
            // 顶栏高度跟着变，下面的 WebView 被顶得上下跳 —— 用户看到的就是
            // 「两个信息窗口在打架，窗口一直跳动」。
            // minLines + maxLines 同时锁 2，TextView 高度恒定，任何提示都不会推动网页。
            // 超长文案由 ellipsize 收尾（正常提示都在 2 行内）。
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        topWrap.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun barBtn(label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
            // 用户 m20782：原生 Button 在 Material 主题下就是一块灰直角方块，
            // 与主界面（22dp 圆角渐变卡片 + 胶囊按钮）完全两个世界。
            // 这里改成 TextView + GradientDrawable 自绘胶囊，视觉口径与 SignDialog 一致：
            //   主按钮 = brand→brand2 渐变白字；次按钮 = 半透明底 + 细描边。
            val pillBg = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (primary) intArrayOf(BRAND, BRAND2) else intArrayOf(BTN_BG, BTN_BG),
            ).apply {
                cornerRadius = (13 * dp)
                if (!primary) setStroke((1 * dp).toInt(), BTN_LINE)
            }
            return TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (primary) Color.WHITE else Color.parseColor("#C6CEE4"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = pillBg
                isClickable = true
                isFocusable = true
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                setOnClickListener {
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    }.start()
                    onClick()
                }
            }
        }
        val pasteBtn = barBtn("粘贴") { pasteFallback() }
        // 换账号：清掉本站登录态再重开登录页。网站有「记住登录」，不清就永远进上一个号。
        val switchBtn = barBtn("换账号") {
            status.text = "正在清除本站登录状态，准备登录新账号…"
            clearWebLoginState {
                Toast.makeText(this@LoginWebActivity, "已清除登录状态，请登录新账号", Toast.LENGTH_SHORT).show()
                startFlow()
            }
        }
        val doneBtn = barBtn(if (provider == P_TRAE) "完成" else "确认", primary = true) { extractNow() }
        val closeBtn = barBtn("关闭") { finish() }
        for ((i, b) in listOf(pasteBtn, switchBtn, doneBtn, closeBtn).withIndex()) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = if (i == 3) 0 else (6 * dp).toInt()
            bar.addView(b, lp)
        }
        topWrap.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(topWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // M2（BUGS-v2.61）：原来是 MIXED_CONTENT_COMPATIBILITY_MODE —— 允许
            // https 页面里加载 http 子资源。登录页全是 https，唯一会被它放行的是
            // 「中间人把某个子资源（脚本/iframe）降到 http」这种降级攻击，而降级的
            // 目的往往就是往页面里注入 JS，进而调 SignHubLogin 桥伪造凭据。
            // 改成 NEVER_ALLOW：http 子资源一律不加载，页面可能少几个图标，但桥安全。
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 阿里云 SSO 会从 account.aliyun.com 弹新窗口做登录，不放开会白屏
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // 所有 provider 共用手机 UA。**豆包尤其不能改成桌面 UA**（V2.68 实测）：
            // 桌面 UA 打开 doubao.com 会把「登录」入口整个隐藏，只剩游客态；
            // 手机 UA 下 `/chat/` 右上角才有「登录」胶囊。别为「看起来像电脑版」
            // 去伪装 SamanthaDoubao，那条路只会让登录入口消失。
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // M2（BUGS-v2.61）：第三方 Cookie 只对「确实要跨站回跳」的两家开。
        // 原来自始至终 true —— 登录页里任何第三方 iframe（广告、统计、被注入的
        // 资源）都能在这个 WebView 里种/读 Cookie，配合 JS 桥就是一个跨站取数通道。
        // 现在只有 WorkBuddy（走 account.aliyun.com / codebuddy SSO）与 Qoder
        // （openapi.qoder.com.cn 回跳）需要它，其余三家关掉。
        val needsThirdPartyCookie = provider == P_WB || provider == P_WB_AI || provider == P_QD
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, needsThirdPartyCookie)
        }

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onToken(json: String) {
                // 安全闸：JS 桥对整个 WebView 生效，页面导航到任意域后仍能调到这里。
                // 恶意/被劫持的页面可以伪造一份凭据喂进来（凭据注入），
                // 所以先确认「当前页面」确实是该服务的登录域，再收数据。
                if (!isTrustedOrigin()) return
                ui { handleToken(json) }
            }

            @JavascriptInterface
            fun onDump(json: String) {
                if (!isTrustedOrigin()) return
                ui { handleDump(json) }
            }

            @JavascriptInterface
            fun onMmToken(json: String) {
                if (!isTrustedOrigin()) return
                ui { handleMmToken(json) }
            }

            /**
             * 豆包采集探测的回抛口（V2.72）。
             *
             * 页面里 `fetch('/alice/ug/campaign/landing/get')` 的结果经这里回来，
             * 由 [dbProbeVerdict] 判断服务端认不认这份 Cookie。
             * 同样过 `isTrustedOrigin()` 闸 —— 桥对整个 WebView 生效，
             * 页面被导航到第三方域后仍能调进来。
             */
            @JavascriptInterface
            fun onDbProbe(text: String) {
                if (!isTrustedOrigin()) return
                val cb = dbProbeCb ?: return
                dbProbeCb = null
                ui { cb(dbProbeVerdict(text)) }
            }

            /**
             * P3-8（BUGS-v1.63）：SPA 用 `history.pushState` 换地址时不会触发
             * `onPageStarted` / `onPageFinished` / `shouldOverrideUrlLoading`，
             * `currentUrl` 一直停在旧页面上。于是：
             *   · 登录成功后页面内部跳到 `/auth/callback`（pushState），白名单仍按旧 URL
             *     判定 → 桥里的 onToken 被 `isTrustedOrigin()` 误杀，用户「明明登录成功却读不到」；
             *   · 反过来，从可信页 pushState 到第三方路径后，桥仍然认为可信。
             * 页面里的 `injectUrlWatcher()` 会包住 pushState/replaceState 并监听
             * popstate/hashchange，每次变化都回报真实地址。
             */
            @JavascriptInterface
            fun onUrl(url: String) {
                // P2R6（BUGS-v2.2）：这个入口以前无条件赋值，等于把 isTrustedOrigin()
                // 的判据交给页面自己决定。现在只接受可信域内的 URL —— 页面被注入后
                // 调 onUrl('https://www.trae.cn/') 不再能把白名单「洗白」。
                if (url.isNotBlank() && isTrustedUrl(url)) currentUrl = url
            }
        }, "SignHubLogin")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                currentUrl = u
                // MiniMax 的 `agent.minimax.cn/login`（不带 sso=1）是海外/桌面版路由，
                // 渲染出来只有一个「使用 Google 继续」按钮（国内用户无法登录）。
                // 只要还没拿到 token，就把它拉回国内统一登录页。
                if (provider == P_MM && !saved && isMmGoogleOnlyPage(u)) {
                    // 只纠正 3 次，避免站点真把 /login 当合法落地页时来回弹
                    if (mmBounce < 3) {
                        mmBounce++
                        status.text = "站点把页面导到了 Google 登录页，已切回国内统一登录页…"
                        view?.post { if (!saved) view.loadUrl(mmAuthorizeUrl()) }
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: ""
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: ""
                injectUrlWatcher()
                injectExtract()
                if (provider == P_HW) {
                    hwLoads++
                    // 积分接口就在签到同域（/chat/snap-manager/v1/package/overview），
                    // 用同一份 cookie —— 不再需要跑 codearts 门户那一趟
                    // （那边未登录只回 200+0 字节，还要等 CAS 跳转，白费 9 秒）。
                    if (!saved && hwLoads > 1) {
                        // 只有第 2 次及以后的加载才自动采集：
                        // WebView 与浏览器共享 CookieManager，上次会话还在时页面一开
                        // 就是已登录态，首次就自动采集会让用户根本没机会输手机号（m21086）。
                        hwCapture(true)
                    }
                }
                if (provider == P_MM && !saved && url != null) {                    // 兜底 1：回跳 URL 上直接带凭据（站点 SSO 分支就是 `auth-callback?accessToken=...`）
                    val fromUrl = mmTokenFromUrl(url)
                    if (fromUrl.isNotBlank()) {
                        persistMm(fromUrl)
                        return
                    }
                    // 兜底 2：落回 agent.minimax.cn 业务页时立刻抓一次（不用等 1.5 秒轮询）
                    //
                    // 只在业务域抓：授权域（account.minimax.cn）的 localStorage 里
                    // 全是登录中间态，抓了就是误判（用户 m13107 的 bug）。
                    if (mmOnBusinessPage()) {
                        view?.evaluateJavascript(mmJs(), null)
                    }
                }
                // 豆包与华为同类：登录态就是整串 Cookie，没有 token 可抓。
                // 采集阶段不需要真打接口验一次 —— Cookie 里有没有 sessionid 就足够判定。
                // （注意：签到阶段纯 HTTP 打不通，签名由页面里的 SDK 在 fetch 层注入，
                //  所以签到走 DoubaoWebCheckin 的 WebView 方案；采集阶段不受影响。）
                // 这里只在 URL 回到 doubao.com 业务页时才采集，避免登录页中间态误存。
                if (provider == P_DB && !saved && url != null) {
                    dbCapture(false)
                }
                // 智谱 / 百度：同样只在页面加载完成时采一次，采到就停（saved 挡住重复）。
                if (provider == P_ZP && !saved && url != null) {
                    zpCapture(false)
                }
                if (provider == P_QF && !saved && url != null) {
                    qfCapture(false)
                }
            }
        }

        // 多账号通道必须先清掉这个 WebView 的登录态。
        // 否则网站有「记住登录」：cookie / localStorage 里还是上一个账号，页面一打开就是已登录，
        // 轮询立刻抓到旧凭据 → 直接当成新账号存进列表，用户根本没机会输另一个号。
        //
        // Trae 在真正开始登录前先弹一次风控提示（用户 m27966）：
        // 9074 = ErrWorkRiskControlBanned，最常见诱因是「账号刚在官网注册、还没正常用过，
        // 就先被本软件拿来签到」。这一步放在主账号与多账号两条入口的公共位置，
        // 因为两者最终都进到这里（WebUiActivity.openLogin / MultiAccountsActivity.addAccount）。
        if (provider == P_TRAE) {
            showTraeRiskNotice {
                if (toList) {
                    status.text = "正在清除本站登录状态，准备登录新账号…"
                    clearWebLoginState { startFlow() }
                } else {
                    startFlow()
                }
            }
        } else if (toList) {
            status.text = "正在清除本站登录状态，准备登录新账号…"
            clearWebLoginState { startFlow() }
        } else {
            startFlow()
        }
    }

    /**
     * Trae 登录前的风控提示（用户 m27966）。
     *
     * 为什么必须弹：9074 是账号级风控封禁，一旦被记，12~24 小时内怎么点都签不上，
     * 用户会以为是软件坏了。而它的头号诱因是使用顺序 —— 账号在官网刚注册、
     * 没正常用过就先让脚本签到。把顺序讲在前面，比事后报错解释有用得多。
     *
     * 「怎么判断自己被风控」这一条只在本软件里可验证：点签到看到 9074 即是。
     * Trae 官网/客户端不会给这个提示，所以必须写在这里。
     */
    private fun showTraeRiskNotice(onProceed: () -> Unit) {
        status.text = "先看一条提示：还没注册 Trae 的话，先注册再登录签到"
        // 正文改成「注册引导 + 邀请链接」（用户要求）：
        // 这段提示的原始目的就是让用户先去官网注册、正常用一会儿再回来签到，
        // 直接给可点的注册入口比罗列 9074 的成因更短、更可执行。
        // 9074 的完整解释仍留在「点签到后的失败文案」里（LocalCheckin.trae 的说明），
        // 真被风控时用户仍然看得到原因与出路。
        //
        // 按钮仍是长按确认（用户 m28007）：按住沿边框走进度、未满松手回弹、
        // 填满变「已确认」并轻震；退出走安卓返回键（onCancel）。
        SignDialog.holdConfirm(
            this,
            kicker = "TRAE · 注册提示",
            title = "先到官网注册，再用本软件签到",
            message = "是否还没有注册 TRAE，点击下方网址进行注册后再登录签到",
            holdText = "已阅读，继续登录",
            holdHint = "长按不放 · 填满边框即确认",
            confirmedText = "已确认",
            // 点一下开浏览器去注册；长按复制（见 SignDialog.holdConfirm 的 linkUrl）
            linkUrl = "https://www.trae.cn/events/code-fission/8ZZYZW5HDUGC" +
                "?utm_source=copy_link&utm_medium=code_fission",
            onOk = onProceed,
            onCancel = { finish() },
        )
    }

    /** 按 provider 打开对应的登录流程（清登录态之后再调用） */
    private fun startFlow() {
        if (provider == P_TRAE) {
            traeOauthFlow()
        } else if (provider == P_MM) {
            mmOauthFlow()
        } else if (provider == P_QD) {
            qoderOauthFlow()
        } else if (provider == P_HW) {
            hwFlow()
        } else if (provider == P_DB) {
            dbFlow()
        } else if (provider == P_ZP) {
            zpFlow()
        } else if (provider == P_QF) {
            qfFlow()
        } else {
            // P_WB 与 P_WB_AI 共用一条流程，区域由 [wbVariant] 决定
            wbOauthFlow()
        }
    }

    // ── 豆包：站内登录 + Cookie 采集 ──────────────────────────────

    private val DB_HOME_URL = "https://www.doubao.com/chat/"

    /**
     * 打开豆包对话页让用户登录。
     *
     * 为什么是 `/chat/` 而不是 `pc/agent-explore`（V2.68 更正）：
     *   · V2.66 开的是 `pc/agent-explore`，实测在手机 UA 下直接渲染成
     *     「该活动仅支持在豆包电脑版参与」+「去下载」引导页，**没有任何登录入口**；
     *   · `/chat/` 在手机 UA 下右上角有「登录」胶囊，点开是
     *     「使用豆包或飞书账号登录 / 手机号登录 / 飞书一键登录 / 抖音一键登录 / 二维码」，
     *     登录成功后自动回跳，Cookie 就落下来了；
     *   · 反过来，桌面 UA（含 `SamanthaDoubao`）打开 `/chat/` 会把登录入口整个隐藏
     *     （DOM 里连「登录」两个字都没有），所以**必须用手机 UA + `/chat/`**。
     * 登录成功后用户再回首页点「签到」，签到走的是纯 HTTP，与页面无关。
     */
    private fun dbFlow() {
        status.text = "在下方登录豆包：点右上角「登录」，用手机号或扫码登录，成功后会自动采集 Cookie"
        web.loadUrl(DB_HOME_URL)
    }

    /**
     * 采集豆包 Cookie。
     *
     * 判据演进（每次都是被真实 bug 逼出来的，别往回退）：
     *   · V2.66：只看 Cookie 里有没有 `sessionid` 这些**名字** —— 过期会话的名字也还在，
     *     于是把上一次的死会话存进去（用户 m03836）。
     *   · V2.71：改成问页面「登录弹窗在不在」—— 但页面已经是正常对话页、弹窗早关了，
     *     右上角却还挂着「登录」黑胶囊，判据答「没弹窗」= 登录了（用户 m03980）。
     *   · V2.72：同时看三样 —— 弹窗在不在、右上角有没有「登录」胶囊、页面内真打一次
     *     `landing/get` 看服务端裁决。任一命中「未登录」就不存。
     *
     * 采集成功也**不关窗**（华为那条 m20782 的教训），顶栏文案改成「已采集」。
     */
    private fun dbCapture(auto: Boolean) {
        if (dbBusy) return
        val u = currentUrl
        if (u.isBlank() || !isTrustedUrl(u)) {
            if (!auto) status.text = "当前页面不在豆包域内，请先打开豆包活动页"
            return
        }
        val isLoginPage = u.contains("/login") || u.contains("sso") || u.contains("passport")
        if (isLoginPage) {
            if (!auto) status.text = "还在登录页，请先完成登录"
            return
        }
        val ck = try { CookieManager.getInstance().getCookie(DB_HOME_URL) } catch (e: Exception) { null }
        if (ck.isNullOrBlank() || !DoubaoWebCheckin.hasSession(ck)) {
            if (!auto) status.text = "还没登录豆包：点页面右上角「登录」，登录成功后再点「确认」"
            return
        }
        // 光看 Cookie 里有 sessionid 不够：过期会话的名字也还在（用户 m03836 的 bug ——
        // 登录弹窗还开着，界面已经显示「已采集 2716 字符」，存进去的是上一次的死会话）。
        // 也不能只看登录弹窗在不在（用户 m03980 的 bug —— 页面已经是正常对话页、弹窗没了，
        // 右上角却还挂着「登录」黑胶囊，说明这份 Cookie 根本没登录）。
        // 唯一可靠的判据：同时看 DOM 两处证据 + 页面内真打一次 landing/get 看服务端裁决。
        dbBusy = true
        // 闸门兜底：回调在极端情况下（WebView 正在销毁）不回来，那样 dbBusy 会永久 true，
        // 之后所有采集都被自己挡住。探测自身有 12 秒超时，这里再兜一层。
        web.postDelayed({ dbBusy = false }, 14000)
        dbLoggedInProbe { verdict ->
            dbBusy = false
            if (verdict == DB_YES) {
                val cookie = try {
                    CookieManager.getInstance().getCookie("https://www.doubao.com") ?: ck
                } catch (e: Exception) { ck }
                persistDb(cookie, auto)
                return@dbLoggedInProbe
            }
            if (verdict == DB_UNKNOWN) {
                // 判断不了（页面内请求没回来）。这时**既不清也不存** —— 刚登录成功时
                // SDK 可能还没装好，把「判断不了」当「未登录」会清掉用户刚拿到的有效登录态。
                if (!auto) status.text = "暂时判断不了登录状态（页面内请求没返回），请稍等再点「确认」"
                return@dbLoggedInProbe
            }
            // 确定没登录。如果 Cookie 里还留着 session 键，说明是上一次的死会话 —— 清掉，
            // 否则每次进页面都会被它骗一次。
            if (DoubaoWebCheckin.hasSession(ck)) {
                dbClearCookies()
                saved = false
                status.text = "检测到旧的登录态已失效（Cookie 里有 sessionid 但服务端不认），" +
                    "已清除，请在下方重新登录"
                // 清完再刷一次页面：否则页面上残留的还是旧会话渲染出来的界面，
                // 用户看到的与刚清掉的状态不一致。刷新后 cookie 里没有 session 键，
                // dbCapture 会走「还没读到会话 Cookie」那条早返，不会来回刷。
                web.postDelayed({ try { web.loadUrl(DB_HOME_URL) } catch (_: Exception) {} }, 400)
            } else if (!auto) {
                status.text = "还没登录豆包：点页面右上角「登录」，登录成功后再点「确认」"
            }
        }
    }

    /** 页面里那次登录态探测的回调；同一时刻只允许一个。 */
    @Volatile
    private var dbProbeCb: ((Int) -> Unit)? = null

    /**
     * 判断「这份 Cookie 现在到底登录着没有」（V2.72）。
     *
     * 三次都判错过，所以这次同时看三样：
     *   1. 登录弹窗在不在（V2.71 只用这个 —— 用户 m03980 的 bug 是弹窗没了但没登录）；
     *   2. 页面右上角还有没有「登录」胶囊（用户截图 m03980 里它就在那儿，这是最直接的证据）；
     *   3. 页面内真打一次 `landing/get`，拿服务端的错误码当参考。
     *
     * 判定顺序：1 或 2 命中 → 确定未登录；否则看错误码 —— `710012001` = 未登录，
     * `0` / `710010702` / `710010202` = 已登录（会话有效，只是可能没活动）；
     * 请求本身失败（-1 / -2）→ 判断不了，**不清 Cookie 也不存**，让用户点「确认」重试。
     * 这一条很重要：刚登录成功时 SDK 可能还没装好，把「判断不了」当成「未登录」
     * 会把用户刚拿到的有效登录态清掉。
     *
     * 只读请求，不改服务端状态。
     */
    private fun dbLoggedInProbe(cb: (Int) -> Unit) {
        dbProbeCb = cb
        // 不设任何 X-* 头，免得与页面 SDK 的签名逻辑打架；Content-Type 必须显式给。
        val js = "(function(){" +
            "var out={modal:false,loginBtn:false,code:-1};" +
            "var t=(document.body&&document.body.innerText)||'';" +
            "var keys=['使用豆包或飞书账号登录','飞书一键登录','抖音一键登录','请输入手机号'];" +
            "for(var i=0;i<keys.length;i++){if(t.indexOf(keys[i])>=0){out.modal=true;break;}}" +
            "var els=document.querySelectorAll('button,a,div,span');" +
            "for(var j=0;j<els.length;j++){var e=els[j];" +
            "if(e.children&&e.children.length>0)continue;" +
            "var s=(e.innerText||'').trim();" +
            "if(s!=='登录'&&s!=='登录/注册'&&s!=='立即登录')continue;" +
            "var r=e.getBoundingClientRect();" +
            "if(r.width<=2||r.height<=2)continue;" +
            "if(r.top<0||r.top>160)continue;" +
            "if(r.left<window.innerWidth*0.5)continue;" +
            "out.loginBtn=true;break;}" +
            "fetch('/alice/ug/campaign/landing/get',{method:'POST',credentials:'include'," +
            "headers:{'Content-Type':'application/json'}," +
            "body:JSON.stringify({badge_counts:{incentive:1}})})" +
            ".then(function(r){return r.text();})" +
            ".then(function(tx){try{out.code=(JSON.parse(tx)||{}).code||-1;}catch(e){out.code=-1;}" +
            "SignHubLogin.onDbProbe(JSON.stringify(out));})" +
            ".catch(function(){out.code=-2;SignHubLogin.onDbProbe(JSON.stringify(out));});" +
            "})()"
        try {
            web.evaluateJavascript(js, null)
        } catch (e: Exception) {
            dbProbeCb = null
            cb(DB_UNKNOWN)
            return
        }
        web.postDelayed({
            val p = dbProbeCb
            if (p != null) {
                dbProbeCb = null
                p(DB_UNKNOWN)
            }
        }, 12000)
    }

    /** 解析探测结果，返回 [DB_YES] / [DB_NO] / [DB_UNKNOWN]。 */
    private fun dbProbeVerdict(text: String): Int {
        if (text.isBlank()) return DB_UNKNOWN
        val o = try { JSONObject(text) } catch (_: Exception) { return DB_UNKNOWN }
        if (o.optBoolean("modal", false)) return DB_NO
        if (o.optBoolean("loginBtn", false)) return DB_NO
        val code = o.optInt("code", -1)
        if (code == -2 || code == -1) return DB_UNKNOWN
        return if (code == 710012001) DB_NO else DB_YES
    }

    /**
     * 清掉豆包域的 Cookie。
     *
     * 为什么要按名字逐个清：`removeAllCookies` 会把所有站点一起清掉（这个 WebView
     * 是所有 provider 共用的），虽然当前流程只服务豆包，但逐个清更精准、也不影响
     * 其它已登录站点留在本 WebView 里的状态。
     */
    private fun dbClearCookies() {
        val cm = CookieManager.getInstance()
        val names = try { cm.getCookie("https://www.doubao.com") } catch (e: Exception) { null }
            ?.split(";")
            ?.mapNotNull { it.substringBefore("=").trim().takeIf { n -> n.isNotEmpty() } }
            ?: emptyList()
        for (n in names) {
            cm.setCookie("https://www.doubao.com", "$n=; Max-Age=0; Path=/")
            cm.setCookie("https://www.doubao.com/chat/", "$n=; Max-Age=0; Path=/")
        }
        cm.flush()
    }

    /**
     * 落库豆包 Cookie。
     *
     * 采集成功也**不关窗**（华为那条 m20782 的教训）：用户可能还要切账号或核对活动状态。
     * 顶栏文案改成「已采集」，让「窗口没消失」这件事有明确解释。
     */
    private fun persistDb(cookie: String, auto: Boolean) {
        val ok = saveBlocking { cur -> cur.copy(dbCookie = cookie) }
        if (!ok) {
            status.text = "保存失败：本机存储不可写"
            return
        }
        saved = true
        status.text = "已采集豆包 Cookie（${cookie.length} 字符），可点右上角关闭"
    }

    // ── 智谱清言：站内登录 + Cookie 采集 ──────────────────────────────

    private val ZP_HOME_URL = "https://chatglm.cn/main/alltoolsdetail"

    /**
     * 打开智谱清言主页让用户登录。
     *
     * 为什么是 `/main/alltoolsdetail` 而不是首页：首页在游客态会挂一整屏拉新文案
     * （「新脑子到岗」「登录送积分好礼」），登录入口在右上角小字里，用户容易找不到。
     * 工具详情页同样是游客可看，但顶部导航固定有「登录」按钮，登录成功后原地刷新，
     * Cookie 直接落下来，不需要回跳。
     */
    private fun zpFlow() {
        status.text = "在下方登录智谱清言：点右上角「登录」，用手机号或扫码登录，成功后自动采集 Cookie"
        web.loadUrl(ZP_HOME_URL)
    }

    /**
     * 采集智谱 Cookie。
     *
     * 判据只有一条但很关键：`ZhipuCheckin.hasSession()` —— 它同时要求
     * ① `chatglm_refresh_token` 存在；② JWT 里 `is_guest != true`。
     *
     * 为什么不能只看「有没有 chatglm_token」：**未登录游客也有一整套 token**。
     * 只看存在性会导致一进页面就判成功、把游客态存下来，之后签到必然 401 40103
     * （zaoba 项目在 `src/login-one.js:30-34` 记录过完全一样的坑）。
     */
    private fun zpCapture(auto: Boolean) {
        val u = currentUrl
        if (u.isBlank() || !isTrustedUrl(u)) {
            if (!auto) status.text = "当前页面不在智谱域内，请先打开 chatglm.cn"
            return
        }
        if (u.contains("/login") || u.contains("sso") || u.contains("passport")) {
            if (!auto) status.text = "还在登录页，请先完成登录"
            return
        }
        val ck = try { CookieManager.getInstance().getCookie("https://chatglm.cn") } catch (e: Exception) { null }
        if (ck.isNullOrBlank() || !ZhipuCheckin.hasSession(ck)) {
            if (!auto) {
                status.text = if (ck.isNullOrBlank())
                    "还没读到智谱 Cookie：点页面右上角「登录」，登录成功后再点「确认」"
                else
                    "这份 Cookie 是游客态（JWT 里 is_guest=true）：游客也有一套完整 token，" +
                        "请真的登录一次再点「确认」"
            }
            return
        }
        persistZp(ck)
    }

    /** 落库智谱 Cookie。与豆包/华为同理：采集成功也**不关窗**。 */
    private fun persistZp(cookie: String) {
        val ok = saveBlocking { cur ->
            cur.copy(
                zpCookie = cookie,
                // 设备号从 JWT 解出来存一份：服务端只认 token 里的 device_id，
                // 存下来是为了下次 refresh 时头组里的 X-Device-Id 与 token 一致。
                zpDeviceId = ZhipuCheckin.deviceIdOf(cookie, ""),
            )
        }
        if (!ok) {
            status.text = "保存失败：本机存储不可写"
            return
        }
        saved = true
        val uid = ZhipuCheckin.uidOf(cookie)
        status.text = "已采集智谱 Cookie（${cookie.length} 字符" +
            (if (uid.isNotBlank()) "，uid ${uid.take(8)}…" else "") + "），可点右上角关闭"
    }

    // ── 百度千帆 / 百度搭子：站内登录 + Cookie 采集 ───────────────────

    private val QF_HOME_URL = "https://console.bce.baidu.com/ai/#/ai/qianfan/overview"

    /**
     * 打开百度智能云千帆控制台让用户登录。
     *
     * 未登录会 302 到 `login.bce.baidu.com`（WAP 版，实测手机 UA 下可正常走
     * 「百度账号 / 发送验证码 / 百度APP一键登录」），登录成功后自动回跳控制台。
     * 选千帆概览页而不是首页：首页有多个产品宫格，回跳后当前 URL 仍属 console 域，
     * 采集判据只需看域，与具体路径无关。
     */
    private fun qfFlow() {
        status.text = "在下方登录百度智能云：登录成功后会自动回到控制台并采集 Cookie"
        web.loadUrl(QF_HOME_URL)
    }

    /**
     * 采集百度 Cookie。
     *
     * 判据：`DumateCheckin.hasSession()` = `BDUSS` 与 `bce-user-info` **同时**存在。
     * 游客态实测有十几个无关 cookie（`BAIDUID` / `HMACCOUNT` / `ppfuid` / `CASSSID` …）
     * 但这两个一个都没有，所以「两个都在」是可靠判据。
     *
     * 采集域必须用 `https://console.bce.baidu.com`：`CookieManager.getCookie(url)`
     * 只返回对该 URL 生效的 cookie，用登录页 URL 去取会拿到一堆 login 域的中间态。
     */
    private fun qfCapture(auto: Boolean) {
        val u = currentUrl
        if (u.isBlank() || !isTrustedUrl(u)) {
            if (!auto) status.text = "当前页面不在百度域内，请先打开百度智能云控制台"
            return
        }
        val ck = try { CookieManager.getInstance().getCookie("https://console.bce.baidu.com") } catch (e: Exception) { null }
        if (ck.isNullOrBlank() || !DumateCheckin.hasSession(ck)) {
            if (!auto) {
                val onLogin = u.contains("login.bce.baidu.com") || u.contains("passport.baidu.com")
                status.text = if (onLogin)
                    "还在百度登录页，请先完成登录（登录成功会自动回跳控制台）"
                else
                    "Cookie 里没有 BDUSS / bce-user-info：还没登录，请在下方完成登录后再点「确认」"
            }
            return
        }
        persistQf(ck)
    }

    /** 落库百度 Cookie。同样不关窗。 */
    private fun persistQf(cookie: String) {
        val ok = saveBlocking { cur -> cur.copy(qfCookie = cookie) }
        if (!ok) {
            status.text = "保存失败：本机存储不可写"
            return
        }
        saved = true
        status.text = "已采集百度智能云 Cookie（${cookie.length} 字符），可点右上角关闭"
    }

    // ── 华为码道：站内登录 + Cookie 采集 ──────────────────────────────

    private val HW_HOME_URL = "https://devcloud.cn-north-4.huaweicloud.com/chat/home"

    /** 打开码道首页让用户登录；页面每次加载完成都尝试采集一次。 */
    private fun hwFlow() {
        status.text = "在下方登录华为账号：登录完成并回到码道首页后会自动采集，也可以随时点「确认」手动采集"
        web.loadUrl(HW_HOME_URL)
    }

    /**
     * 采集华为会话 Cookie。
     *
     * 三重判据（缺一条都会把「预登录态」当成已登录）：
     *   1. 当前 URL 已经回到 `huaweicloud.com/chat/` 且不是登录页；
     *   2. `CookieManager` 里能读到会话 Cookie（`devclouddevuibjJ_SESSION_ID`）；
     *   3. **用这份 cookie 打一次 `/v1/ops/delivery`，只有回 `code=0` + `data` 才算真登录**
     *      —— 华为对未登录访客也会下发匿名 `J_SESSION_ID`，只查 cookie 名字必然误判
     *      （用户 m22649：连手机号都没输就提示「已采集」）。
     *
     * 用户 m20782：刚点进网址就提示「已采集」并自动退出，用户根本没机会输手机号。
     * 原因有两层：
     *   · WebView 与浏览器共享 CookieManager，上次登录的会话还在 → 页面一开就是已登录态；
     *   · 采集成功后立刻 `finish()`，把「已经登好了」这件事直接变成「窗口消失」。
     * 修法：**采到也不关窗**（只把顶栏文案改成「已采集，可点关闭」），并补 `agencyID`
     * 判据 —— 没有 agencyID 说明会话没落全，签到会报头组不完整。
     */
    private fun hwCapture(auto: Boolean) {
        // 闸门用 hwBusy 而不是 saved：saved 一旦为 true 就永久挡住「再采一次」，
        // 用户输完验证码点「确认」时会看到顶栏卡在「正在采集」不动（m21086）。
        if (hwBusy) return
        val u = currentUrl
        if (u.isBlank() || !isTrustedUrl(u)) {
            if (!auto) status.text = "当前页面不在华为域内，请先打开码道首页"
            return
        }
        val isHome = u.contains("huaweicloud.com/chat/") &&
            !u.contains("login") && !u.contains("auth.") && !u.contains("signin")
        if (!isHome) {
            if (!auto) status.text = "还没回到码道首页，请先完成登录"
            return
        }
        val ck = try { CookieManager.getInstance().getCookie(u) } catch (e: Exception) { null }
        if (ck.isNullOrBlank() || !LocalCheckin.hwHasSession(ck)) {
            if (!auto) status.text = "还没读到会话 Cookie，请确认已登录（页面能打开 chat/home 即已登录）"
            return
        }
        hwBusy = true
        // 注意：这里**不改顶栏文案**。以前一进来就写「已检测到登录，正在采集 Cookie…」，
        // 而实际还没登录，用户看到的就是一句假话（m22649）。现在只有 probe 真的
        // 确认登录成功后才改文案，其余情况顶栏保持 hwFlow() 的「请先登录」提示。
        // 用户 m22649：刚打开码道首页、连手机号都没输，就被判成「已检测到登录」
        // 并写进了配置。根因是华为对**未登录访客也会下发匿名 `J_SESSION_ID`**，
        // 所以「cookie 里有会话名」根本不等于「已登录」。
        // 这里加一道接口级校验：拿这份 cookie 打一次 /v1/ops/delivery，
        // 只有真回了 code=0 + data 才认。false 就当场停手，不写存储、不置 saved。
        Thread {
            val verdict = try {
                runBlocking { LocalCheckin.hwProbe(ck) }
            } catch (e: Exception) {
                null
            }
            ui {
                if (saved) { hwBusy = false; return@ui }
                when (verdict) {
                    false -> {
                        // 明确的「未登录」才写 false。这条不落库（不保存这份匿名 Cookie），
                        // 只把结论记下来，让首页/一键签到别把它算成可用账号。
                        HwVerified.set(this@LoginWebActivity, ck, false)
                        hwBusy = false
                        status.text = if (auto) {
                            "等待登录：在下方输入手机号完成登录，回到码道首页后会自动采集"
                        } else {
                            "还没登录：华为接口返回的是未登录状态，请先在下方完成账号登录（输手机号 + 验证码）"
                        }
                        return@ui
                    }
                    null -> {
                        // 判断不了（网络异常）→ 与手动粘贴路径一致：Cookie 照样保存，
                        // 但结论清回「未知」而不是留旧值（BUGS-v2.48 P1）。
                        // 「判断不了」既不该当成已登录、也不该把用户的账号判死。
                        hwBusy = false
                        status.text = if (auto) {
                            "暂时判断不了登录状态（网络异常），已按当前 Cookie 保存；稍后可点「确认」重试"
                        } else {
                            "网络异常，未能确认登录状态 —— 已按当前 Cookie 保存，可稍后点「确认」重试"
                        }
                        val full0 = try { CookieManager.getInstance().getCookie(HW_HOME_URL) } catch (e: Exception) { null }
                        val ck0 = if (!full0.isNullOrBlank() && LocalCheckin.hwHasSession(full0)) full0 else ck
                        val p0 = try { CookieManager.getInstance().getCookie("https://codearts.huaweicloud.com") } catch (e: Exception) { null } ?: ""
                        persistHw(ck0, p0, auto)
                        return@ui
                    }
                    else -> {}
                }
                if (auto) status.text = "已确认登录，正在采集 Cookie…"
                web.postDelayed({
                    // 先解闸，后面无论走哪条分支都不会把「正在采集」留在顶栏
                    hwBusy = false
                    val full = try { CookieManager.getInstance().getCookie(HW_HOME_URL) } catch (e: Exception) { null }
                    val cookie = if (!full.isNullOrBlank() && LocalCheckin.hwHasSession(full)) full else ck
                    // 门户 Cookie 单独一份：codearts.huaweicloud.com 是另一个域，只用于读积分
                    val portal = try { CookieManager.getInstance().getCookie("https://codearts.huaweicloud.com") } catch (e: Exception) { null } ?: ""
                    persistHw(cookie, portal, auto)
                    // **必须在 persistHw 之后**：persistHw 会把旧结论清回「未知」
                    // （新 Cookie 即新证据，BUGS-v2.48 P1），所以 true 要重新写一遍。
                    HwVerified.set(this@LoginWebActivity, cookie, true)
                }, 1200)
            }
        }.start()
    }

    /**
     * 手动粘贴路径的落库入口：**先 probe 再存**。
     *
     * BUGS-v2.41 P2-1：自动采集路径会用 [LocalCheckin.hwProbe] 真打一次接口确认登录，
     * 但手动粘贴路径直接 `persistHw` 只判 cookie 里有没有会话名 —— 而华为对**未登录
     * 访客也会下发匿名 `J_SESSION_ID`**。于是粘一份匿名 cookie 就会被认成「就绪」，
     * 首页显示已配置、`allDoneToday` 把华为算进 active，点签到才报未登录。
     * 这跟用户 m22649 的场景是同一类问题，只是换了个入口。
     */
    private fun persistHwVerified(cookie: String, portal: String) {
        if (!LocalCheckin.hwHasSession(cookie)) {
            status.text = "Cookie 里没有 devclouddevuibjJ_SESSION_ID，可能还没登录完成"
            return
        }
        status.text = "正在确认登录状态…"
        Thread {
            val verdict = try {
                runBlocking { LocalCheckin.hwProbe(cookie) }
            } catch (e: Exception) {
                null
            }
            ui {
                when (verdict) {
                    false -> {
                        // 明确的「未登录」才写 false，且不保存这份匿名 Cookie。
                        HwVerified.set(this@LoginWebActivity, cookie, false)
                        status.text = "这份 Cookie 是未登录状态（华为对未登录访客也会发匿名会话）。" +
                            "请先在下方完成账号登录，再点「确认」自动采集"
                        return@ui
                    }
                    null -> {
                        // 网络异常：判断不了就不拦，Cookie 照样存。结论由 persistHw 清回「未知」
                        // ——「判断不了」既不该当成已登录、也不该把用户的账号判死（BUGS-v2.48 P1）。
                        persistHw(cookie, portal, false)
                        return@ui
                    }
                    else -> {}
                }
                // **先 persistHw 再 set(true)**：persistHw 会把旧结论清回「未知」
                // （新 Cookie 即新证据，BUGS-v2.48 P1），顺序反了 true 会被清掉。
                persistHw(cookie, portal, false)
                HwVerified.set(this@LoginWebActivity, cookie, true)
            }
        }.start()
    }

    /**
     * 读 auth 域（`auth.huaweicloud.com`）的 Cookie —— **静默重登的钥匙**（V2.79）。
     *
     * 为什么必须单独采集：devcloud 域的 `SESSION_ID` 是 Session 级、静置 30~60 分钟
     * 就失效（实测见 `HwRenew` 类文档），而真正能自动换回新会话的是 auth 域那份
     * 登录凭据。以前只 `getCookie(HW_HOME_URL)`，auth 域从来没读过，等于把长期
     * 凭据白放着，每次失效都只能让用户重新输手机号。
     *
     * 用户点「登录华为码道」时 WebView 必然经过 `auth.huaweicloud.com/authui/login`，
     * 所以那一刻这个域一定有 cookie。取不到返回空串（`HwRenew` 会据此直接跳过重登）。
     */
    private fun hwAuthCookieNow(): String = try {
        CookieManager.getInstance().getCookie("https://auth.huaweicloud.com") ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun persistHw(cookie: String, portal: String, auto: Boolean) {
        if (!LocalCheckin.hwHasSession(cookie)) {
            if (!auto) status.text = "Cookie 里没有 devclouddevuibjJ_SESSION_ID，可能还没登录完成"
            return
        }
        // agencyID 是华为回主页后才补发的附属 cookie；缺了它说明会话还没落全，
        // 拿去签到会报「头组不完整」而不是 401，排查起来很绕。
        val hasAgency = cookie.contains("agencyID=")
        // V2.79：auth 域 Cookie 一起存，供 HwRenew 静默重登用。
        val authCk = hwAuthCookieNow()

        // 多账号通道（用户 m24258）：追加到列表，不覆盖设置页里的主账号。
        if (toList) {
            // id 由「账号身份」派生（BUGS-v2.46 P1）：优先 agencyID，拿不到才回退整条 Cookie。
            // 直接用整条 Cookie 会让「同一账号重新登录」被当成新账号（会话 Cookie 必然变）。
            val acc = HwAccount(
                id = AccountIds.stable(HwAccount.identityOf(cookie)),
                cookie = cookie,
                authCookie = authCk,
            )
            val dup = HwAccountStore.all(this).any {
                HwAccount.agencyOf(it.cookie).let { a -> a.isNotBlank() && a == HwAccount.agencyOf(cookie) } ||
                    it.cookie == cookie
            }
            HwAccountStore.upsert(this, acc)
            // 新 Cookie 即新证据（BUGS-v2.48 P1）：今日标记按 identityOf 分桶，
            // 同一账号重登会命中同一个桶 —— 所以「新号不该继承状态」这个前提不成立，
            // 必须显式清掉旧标记与旧验证结论，否则「今天签过」会把这次重新登录
            // 直接短路成 already，用户以为签了、实际没打上游。
            HwTodayMark.clear(this, cookie)
            HwVerified.clear(this, cookie)
            saved = true
            ui {
                Toast.makeText(
                    this@LoginWebActivity,
                    if (dup) "这个华为账号已经在列表里了（未新增）" else "已添加华为码道账号",
                    Toast.LENGTH_LONG
                ).show()
                status.text = if (hasAgency) "已添加华为账号（含 agencyID），可以点「关闭」返回"
                else "已添加华为账号，但缺 agencyID —— 若签到提示头组不完整，请稍等几秒再点「确认」"
            }
            finish()
            return
        }

        // 门户 cookie 只是历史遗留的兜底路径（积分主路径已改到签到同域）。
        // 仍然保留「有真会话才覆盖」的判据：只有 WAF cookie 的空壳会让
        // 兜底路径误判成可用（m21642）。
        val portalOk = portal.contains("SessionID=")
        val ok = saveBlocking { s ->
            s.copy(
                hwCookie = cookie,
                hwAuthCookie = if (authCk.isNotBlank()) authCk else s.hwAuthCookie,
                hwPortalCookie = if (portalOk) portal else s.hwPortalCookie,
            )
        }
        saved = true
        ui {
            if (!ok) {
                status.text = "保存失败，请重试（点「粘贴」也能手动填）"
                saved = false
                return@ui
            }
            // 新 Cookie 即新证据（BUGS-v2.48 P1）：键只由 identityOf 决定，同一账号重登
            // 键完全相同，而 set(null)（网络异常判断不了）什么都不写 —— 旧的 false 会
            // 原封不动地留下来，用户明明重新登录成功，App 却一直说「未登录（会跳过）」，
            // 唯一自愈口是「重置凭据」（会连另外四家的凭据一起清掉）。
            // 所以保存成功后无条件把结论清回「未知」，再由调用方按 probe 结果重设。
            HwVerified.clear(this@LoginWebActivity, cookie)
            Toast.makeText(this@LoginWebActivity, "已采集华为码道 Cookie", Toast.LENGTH_SHORT).show()
            // 关键：不自动 finish，也不再跳 codearts 门户。
            // 积分接口就在签到同域（/chat/snap-manager/v1/package/overview），
            // 用这份 cookie 就能读 —— 以前跑门户那一趟既慢（要等 CAS 跳 9 秒）
            // 又拿不到数（未登录只回 200+0 字节）。
            status.text = if (hasAgency) {
                "已采集华为码道 Cookie（含 agencyID），可以点「关闭」返回"
            } else {
                "已采集 Cookie，但缺 agencyID —— 若签到提示头组不完整，请稍等几秒再点「确认」"
            }
            // V2.79：auth 域没读到就明确说出来 —— 静默重登（免手机号续期）依赖它，
            // 缺了等于每次会话过期都得手动重登，用户有权知道。
            if (authCk.isBlank()) {
                status.text = status.text.toString() +
                    "\n（没读到 auth.huaweicloud.com 的 Cookie：会话过期后无法自动续期）"
            }
        }
    }

    /**
     * 历史遗留：codearts 门户 cookie 采集。
     *
     * 积分主路径已改到**签到同域**（`/chat/snap-manager/v1/package/overview`），
     * 用签到那份 cookie 就能读，不再需要跑 codearts 门户那一趟。
     * 这个方法保留为空壳，只为让 `extractNow()` 里「已经站在门户页」的旧分支
     * 不至于编译失败 —— 实际上 `hwFlow()` 只会把 WebView 导到 chat/home，
     * 不会再去 codearts。
     */
    private fun hwPortalCapture() {
        hwPortalPending = false
    }

    /** WorkBuddy 区域：`ai` = 国际版（workbuddy.ai），`cn` = 国内版 */
    private fun wbVariant(): String = if (provider == P_WB_AI) "ai" else "cn"

    /**
     * 让页面在 SPA 换地址时回报真实 URL（配合桥里的 onUrl）。
     *
     * P3-8（BUGS-v1.63）：`history.pushState` / `replaceState` 不触发任何
     * WebViewClient 回调，`currentUrl` 会一直停在旧页面 → `isTrustedOrigin()`
     * 按旧 URL 判定，登录成功后页面内部跳到 /auth/callback 时桥里的 onToken 被误杀。
     * 这里包住两个 history 方法并监听 popstate/hashchange，每次变化回报真实地址。
     * `onPageFinished` 里调用；用 `window.__signhubUrlWatch` 防重复注入。
     */
    private fun injectUrlWatcher() {
        val js = """
            (function(){
              if (window.__signhubUrlWatch) return;
              window.__signhubUrlWatch = true;
              function report(){ try { SignHubLogin.onUrl(location.href); } catch(e){} }
              ['pushState','replaceState'].forEach(function(k){
                var orig = history[k];
                if (typeof orig !== 'function') return;
                history[k] = function(){
                  var r = orig.apply(this, arguments);
                  try { report(); } catch(e){}
                  return r;
                };
              });
              window.addEventListener('popstate', report);
              window.addEventListener('hashchange', report);
              report();
            })();
        """.trimIndent()
        try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
    }

    /**
     * 清掉 WebView 在本站的全部登录痕迹，让用户能登另一个账号。
     *   · CookieManager.removeAllCookies —— 清 HttpOnly 会话（Trae 的 X-Cloudide-Session、各站 SSO）
     *   · WebStorage.deleteAllData       —— 清 localStorage / IndexedDB（MiniMax 的 _token 就在这）
     * 只清 WebView 容器，不动本机已保存的凭据（那些在 DataStore / SharedPreferences 里）。
     * 全清而不是按域清：一次登录页只服务一个 provider，清干净最可靠。
     */
    private fun clearWebLoginState(onDone: () -> Unit) {
        var finished = false
        fun done() {
            if (finished) return
            finished = true
            ui { onDone() }
        }
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies {
                try { cm.flush() } catch (_: Exception) {}
                try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                done()
            }
            // 兜底：个别 ROM 上 removeAllCookies 的回调不回来
            web.postDelayed({ done() }, 1200)
        } catch (_: Exception) {
            try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
            done()
        }
    }

    /**
     * WorkBuddy 登录 —— 与另外三家统一：**全部在站内 WebView 里完成**，不跳外部浏览器。
     *   ① POST /v2/plugin/auth/state?platform=workbuddy 拿 state + authUrl
     *   ② 直接 web.loadUrl(authUrl)（https://www.codebuddy.cn/login?platform=workbuddy&state=..，
     *      实测移动 UA 下正常渲染「微信扫码 / 手机号 / SSO / QQ / 邮箱」全套入口）
     *   ③ 每 2 秒 GET /v2/plugin/auth/token?state=.. 轮询；
     *      未授权时服务端返回 {"code":11217,"msg":"login ing..."}，继续等
     *   ④ 拿到 token 后 fetchProfile 补 uid/enterpriseId，写入 Settings 的 wbToken 等字段
     *
     * 为什么不需要从页面里抓 token：授权结果由服务端按 state 记账，WebView 只负责「把登录做完」，
     * 凭据由 ③ 的轮询取回。所以容器是浏览器还是 WebView 对结果没有影响。
     *
     * 同一个 state 只能授权一次，所以只在一个容器里开登录页（不要同时开浏览器）。
     */
    private fun wbOauthFlow() {
        status.text = "正在申请登录…"
        showHintPage("正在向 WorkBuddy 申请登录授权…")
        Thread {
            val start = try { WbApi.oauthStart(wbVariant()) } catch (e: Exception) { null }
            ui {
                if (start == null) {
                    status.text = "申请登录失败；可点「粘贴」手动保存 accessToken"
                    showHintPage("申请登录授权失败（网络或接口变动）。\n\n可以点右上角「粘贴」手动填 accessToken，或稍后重试。")
                    return@ui
                }
                wbState = start.state
                status.text = "在下方网页用手机号 / 微信登录；登录完成后自动保存"
                web.loadUrl(start.authUrl)
                startWbPoll()
            }
        }.start()
    }

    /** 在 WebView 里显示一段说明（不用 loadDataWithBaseURL，保持纯本地、不跑远端脚本） */
    private fun showHintPage(msg: String) {
        try {
            val esc = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>")
            web.loadDataWithBaseURL(
                null,
                "<html><body style='background:#0F1115;color:#C9D4EA;font:15px/1.9 -apple-system,sans-serif;padding:28px 22px'>" +
                    esc + "</body></html>",
                "text/html", "utf-8", null
            )
        } catch (_: Exception) {}
    }

    /** 轮询 OAuth token（10 分钟窗口，命中即存并退出） */
    private fun startWbPoll() {
        pollThread = Thread {
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (!saved && System.currentTimeMillis() < deadline) {
                val st = wbState
                if (st.isNullOrBlank()) break
                val tb = try { WbApi.oauthPoll(wbVariant(), st) } catch (e: Exception) { null }
                if (tb != null) {
                    val prof = try { WbApi.fetchProfile(wbVariant(), st, tb.accessToken, tb.domain) } catch (e: Exception) { null }
                    val uid = prof?.optString("uid", "") ?: ""
                    // optString 的 default 参数类型是非空 String，传 String? 会在运行时落 null
                    val ent = (prof?.optString("enterpriseId", "") ?: "")
                        .ifBlank { prof?.optString("enterprise_id", "") ?: "" }
                    saveWbResult(tb.accessToken, tb.refreshToken, uid, tb.domain, ent)
                    saved = true
                    ui {
                        Toast.makeText(
                            this@LoginWebActivity,
                            if (toList) "已添加 WorkBuddy 账号" else "主账号登录成功",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@Thread
                }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
            ui {
                if (!saved) status.text = "登录超时（10 分钟）；也可点「粘贴」手动保存"
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * WorkBuddy 授权结果落库：多账号通道写进 WbAccountStore，单账号通道写进设置。
     * 两者字段口径一致，只是落点不同（与另外三家 toList 分支同构）。
     */
    private fun saveWbResult(at: String, rt: String?, uid: String?, domain: String?, ent: String?) {
        if (!toList) {
            saveWbBlocking(at, rt, uid, domain, ent)
            return
        }
        val acc = WbAccount(
            // P3R5（BUGS-v2.2）：与 MultiAccountsActivity 三处手动添加同口径用
            // AccountIds.stable(...)。时间戳+随机数在 uid 拿不到时会让每次登录
            // 都新建一个重复账号（upsert 的 uid 匹配也会失效）。
            id = AccountIds.stable(uid ?: "", at, domain ?: ""),
            uid = uid ?: "",
            enterpriseId = ent ?: "",
            accessToken = at,
            refreshToken = rt ?: "",
            domain = domain ?: "",
            variant = wbVariant(),
            createdAt = System.currentTimeMillis(),
        )
        WbAccountStore.upsert(this, acc)
    }

    private fun traeOauthFlow() {
        // 直接落到登录页，不再开营销首页（首页要用户自己找「登录」，而且大图很费流量）
        web.loadUrl("https://www.trae.cn/login")
        status.text = "在下方网页用手机号登录；登录后会自动读取 session（约 14 天）"
        web.postDelayed(object : Runnable {
            var times = 0
            override fun run() {
                if (saved) return
                // HttpOnly Cookie 落地比 localStorage 慢，轮询窗口拉到约 5 分钟
                if (times > 200) {
                    status.text = "没自动读到 session；可点「粘贴」手动填 X-Cloudide-Session"
                    return
                }
                times++; injectExtract(); web.postDelayed(this, 1500)
            }
        }, 1500)
    }

    /**
     * MiniMax 网页登录 —— 站内 WebView 打开**国内统一登录页** account.minimax.cn。
     *
     * 之前直接开 `agent.minimax.cn/login` 是错的：那个路由会渲染成海外/桌面版的
     * 「使用 Google 继续」单按钮页（实测 DOM 只有 `使用 Google 继续`），国内用户根本没法登。
     * 国内正确入口是账号中心：
     *   `https://account.minimax.cn/oauth2/authorize?client_id=agent-minimax
     *      &redirect_uri=https://agent.minimax.cn/auth/callback&response_type=code
     *      &source=agent_desktop&state=<base64(JSON{redirect_uri,csrf})>`
     * 该地址 302 到 `account.minimax.cn/unified-login`，渲染出「手机号登录 / 账号密码登录」
     * 两个 tab（实测 DOM：手机号登录、账号密码登录、获取验证码、立即登录，
     * bodyText 含「未注册的手机号将自动创建账号」「根据您的地区，已为您跳转至国内网站」）。
     *
     * **state 里的 redirect_uri 必须是首页 `/`，不能是 `/login`**（v46 的 bug）：
     * `/login` 不带 `sso=1` 时就是那个「使用 Google 继续」的海外页，登录成功回跳过去
     * 用户又看到 Google 页，`localStorage._token` 也没写成，签到必然失败。
     * 站点自己的 `9895-*.js` 里网页登录分支写的是
     * `state:btoa(JSON.stringify({redirect_uri:window.location.href, csrf:uuid()}))`，即当前页 `/`。
     *
     * 登录完成后 token 仍从 `agent.minimax.cn` 的 localStorage `_token` 取，
     * 所以要等回跳到 agent.minimax.cn 后再抓 —— mmJs() 已经在每 1.5 秒扫，不用改。
     */
    private fun mmOauthFlow() {
        web.loadUrl(mmAuthorizeUrl())
        status.text = "在下方网页用手机号登录 MiniMax；登录后自动读取 token"
        web.postDelayed(object : Runnable {
            var times = 0
            override fun run() {
                if (saved) return
                if (times > 200) {
                    status.text = "没自动读到 token；可点「粘贴」手动填"
                    return
                }
                times++
                // **只在「已经落到 agent.minimax.cn 的业务页」上扫 localStorage**。
                //
                // 这是用户 m13107 那个 bug 的正解：登录过程发生在 account.minimax.cn
                // （OAuth 授权页 / 手机号 + 验证码页），而 `_token` 属于
                // agent.minimax.cn 这个 origin。以前不判 origin 就 evaluateJavascript，
                // 等于去读**登录页自己的** localStorage —— 那里正写着 csrf、临时会话串、
                // 埋点长串，`looksLikeMmToken()` 一命中就当成 token 存下来并 finish()，
                // 于是用户刚点完「获取验证码」、人机验证还没出现就被弹回软件。
                //
                // 现在：URL 还停在授权域时连扫都不扫，页面一动不打扰。
                if (mmOnBusinessPage()) {
                    web.evaluateJavascript(mmJs(), null)
                    // 兜底：token 也可能被写进 Cookie（虽然实测服务端不认 Cookie，但先抓下来）
                    if (times % 4 == 0) {
                        val ck = readMmTokenFromCookies()
                        if (ck.isNotBlank()) persistMm(ck)
                    }
                }
                web.postDelayed(this, 1500)
            }
        }, 1500)
    }

    /**
     * 当前页面是否已经离开授权域、落到 MiniMax 的业务页。
     *
     * `_token` 存在 `agent.minimax.cn` 的 localStorage 里；登录页在
     * `account.minimax.cn`，两者的 localStorage 完全隔离。只有当前 origin
     * 是业务域时，扫出来的值才可能是真 token。
     */
    private fun mmOnBusinessPage(): Boolean {
        val u = currentUrl
        if (u.isBlank()) return false
        if (!u.contains("agent.minimax.cn")) return false
        // 业务域下的 /login 是「还没登录」的落地页，同样别扫
        if (u.contains("/login")) return false
        return true
    }

    /** 构造国内统一登录页地址（每次调用换新的 csrf / state） */
    private fun mmAuthorizeUrl(): String {
        val csrf = UUID.randomUUID().toString()
        val state = android.util.Base64.encodeToString(
            ("{\"redirect_uri\":\"https://agent.minimax.cn/\",\"csrf\":\"" + csrf + "\"}")
                .toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return buildString {
            append("https://account.minimax.cn/oauth2/authorize")
            append("?client_id=agent-minimax")
            append("&redirect_uri=").append(android.net.Uri.encode("https://agent.minimax.cn/auth/callback"))
            append("&response_type=code")
            append("&source=agent_desktop")
            append("&state=").append(android.net.Uri.encode(state))
        }
    }

    /**
     * 是不是「只有 Google 登录」的海外页。
     *
     * `agent.minimax.cn/login`（不带 `sso=1`）在移动 UA 下 DOM 只有
     * `使用 Google 继续` 一个按钮（实测 bodyText：「MiniMax / 创建账户或登录以继续 / 使用 Google 继续」）。
     * 带 `sso=1` 会 302 到 `account.minimax.cn/unified-login`，那是国内页，不能拦。
     */
    private fun isMmGoogleOnlyPage(url: String): Boolean =
        url.contains("agent.minimax.cn") && url.contains("/login") && !url.contains("sso=1")

    /**
     * 兜底：回跳 URL 上直接带凭据时直接取。
     *
     * 站点 SSO 分支（`9895-*.js`）登录成功是 `location.href = auth-callback?accessToken=...`
     * （或 `?token=`），此时 localStorage 还没写，但 URL 里已经有值了。
     */
    private fun mmTokenFromUrl(url: String): String {
        for (k in listOf("accessToken", "access_token", "token")) {
            val v = try {
                android.net.Uri.parse(url).getQueryParameter(k)
            } catch (_: Exception) { null }
            if (v.isNullOrBlank()) continue
            val t = try { android.net.Uri.decode(v) } catch (_: Exception) { v }
            // 以前这里用 `t.startsWith("mmoat_")` 判据 —— 但网页端的 `_token` 没有前缀
            // （扫完全部 28 个 JS，`mmoat_` 出现 0 次），这条判据会把真 token 也挡掉。
            // 改成「像不像 token」的结构判据：排除 UUID / 纯数字 / 含 JSON 字符或空白。
            if (looksLikeMmToken(t)) return t
        }
        return ""
    }

    // ---------- Qoder CN（官方 device flow） ----------

    /**
     * Qoder 网页登录 —— 走 Qoder 客户端自己的 device flow（PKCE + 轮询），不是抓 localStorage。
     *
     * 逆向自 `E:\Qoder CN\resources\app.asar`（prod 环境常量 @37088153）：
     *   · 登录页 W7t(): `${authBaseUrl}/device/selectAccounts?challenge=&challenge_method=S256&nonce=&machine_id=&client_id=&sourceType=Qoder`
     *   · PKCE  j7t(): 64 个 [A-Za-z0-9-._~]；V7t(): base64url(sha256(verifier))
     *   · 轮询  Q7t(): `GET ${openApiBaseUrl}/api/v1/deviceToken/poll?nonce=&verifier=&challenge_method=S256`
     *           每 1s 一次、最多 300s；**404 表示「还没登录」，继续等**（源码里就是 `if(d===404){continue}`）；
     *           拿到 `{token, refresh_token}` 即成功。
     *
     * 实测要点（本机验证过）：
     *   · `/api/v1/deviceToken/poll` 端点确实存在 —— 同域随便一个不存在的路径会返回 401 TOKEN_INVALID，
     *     而 poll 返回 404 NotFound，说明它被路由到了、只是判定「未登录」。
     *   · 登录页在手机浏览器/WebView 里能正常渲染（个人登录 / 企业登录 / 阿里云登录）。
     *   · 拿到 token 后签到时不需要 Cosy-MachineId（不带也 200），所以 machine_id 只用于这次登录。
     */
    private fun qoderOauthFlow() {
        val verifier = qdVerifier()
        val challenge = qdChallenge(verifier)
        val nonce = qdVerifier()
        val machineId = qdMachineId()
        qdVerifierValue = verifier
        qdNonceValue = nonce
        qdMachineValue = machineId

        val url = buildString {
            append(QD_AUTH_BASE).append("/device/selectAccounts")
            append("?challenge=").append(android.net.Uri.encode(challenge))
            append("&challenge_method=S256")
            append("&nonce=").append(android.net.Uri.encode(nonce))
            append("&machine_id=").append(android.net.Uri.encode(machineId))
            append("&client_id=").append(QD_CLIENT_ID)
            append("&sourceType=Qoder")
        }
        status.text = "在下方网页登录 Qoder；登录后会自动取回 token"
        web.loadUrl(url)
        startQdPoll()
    }

    /** 64 字符 verifier（与客户端同一字符集、同一长度） */
    private fun qdVerifier(): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(64)
        repeat(64) { sb.append(QD_VERIFIER_CHARS[rnd.nextInt(QD_VERIFIER_CHARS.length)]) }
        return sb.toString()
    }

    /** base64url(sha256(verifier))，去掉 = 填充（与客户端 digest("base64url") 一致） */
    private fun qdChallenge(verifier: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(verifier.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(
            dig,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun qdMachineId(): String = try {
        UUID.randomUUID().toString()
    } catch (_: Exception) { "0" }

    /** 轮询一次 deviceToken/poll。返回 (token, refreshToken) 或 null。 */
    private fun qdPollOnce(): Pair<String, String>? {
        val verifier = qdVerifierValue ?: return null
        val nonce = qdNonceValue ?: return null
        val url = "$QD_OPENAPI_BASE/api/v1/deviceToken/poll" +
            "?nonce=" + android.net.Uri.encode(nonce) +
            "&verifier=" + android.net.Uri.encode(verifier) +
            "&challenge_method=S256"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            }
            val code = conn.responseCode
            // 404 = 还没登录，属于正常态，不能当错误
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val tok = o.optString("token", "").ifBlank { o.optString("device_token", "") }.trim()
            val rt = o.optString("refresh_token", "").trim()
            if (tok.isNotBlank() && tok.length >= 10) Pair(tok, rt) else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 后台每 1 秒轮询，最多 5 分钟（与客户端 H7t=300s 一致） */
    private fun startQdPoll() {
        pollThread = Thread {
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            while (!saved && System.currentTimeMillis() < deadline) {
                val hit = qdPollOnce()
                if (hit != null) {
                    persistQoder(hit.first, hit.second)
                    return@Thread
                }
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
            ui {
                if (!saved) status.text = "登录超时（5 分钟）；也可点「粘贴」手动填 token"
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun persistQoder(tok: String, refresh: String = "") {
        if (saved) return
        val mid = qdMachineValue ?: ""
        if (toList) {
            // 多账号通道：追加到列表，不覆盖设置页里的主账号
            if (tok.isBlank() && refresh.isBlank()) {
                Toast.makeText(this, "没读到任何凭据，请重试", Toast.LENGTH_LONG).show(); return
            }
            val acc = QoderAccount(
                id = System.currentTimeMillis().toString() + (1000..9999).random(),
                token = tok, refresh = refresh, uid = "", machineId = mid, region = "cn",
            )
            val dup = QoderAccountStore.all(this).any {
                (tok.isNotBlank() && it.token == tok) || (refresh.isNotBlank() && it.refresh == refresh)
            }
            val stored = QoderAccountStore.upsert(this, acc)
            // 昵称是异步补的（用户 m16091）：接口要打一次上游，不能阻塞 finish
            fetchQoderNickname(stored.id, tok, refresh, mid)
            Toast.makeText(
                this,
                if (dup) "这个 Qoder 账号已经在列表里了（未新增）" else "已添加 Qoder 账号",
                Toast.LENGTH_LONG
            ).show()
            saved = true; finish()
            return
        }
        // 必须同步写完再 finish：否则 WebUiActivity 立刻恢复并发打 4 个接口，
        // 撞上 DataStore 写事务 → /api/config 报错 → 设置页空白（见 saveBlocking 注释）。
        val ok = saveBlocking { s ->
            s.copy(
                qoderToken = tok,
                // refreshToken 是关键：drt- 有效期一年、可反复换（实测同一个 drt- 反复换
                // 都能拿到新 dt-），存下来以后 token 过期也能自动续，不用再登录一次。
                qoderRefresh = if (refresh.isNotBlank()) refresh else s.qoderRefresh,
                qoderUid = s.qoderUid,
                qoderMachineId = if (mid.isNotBlank()) mid else s.qoderMachineId,
            )
        }
        Toast.makeText(
            this,
            if (ok) "已获取 Qoder 凭据" else "保存失败，请重试（点「粘贴」也能手动填）",
            Toast.LENGTH_LONG
        ).show()
        saved = true; finish()
    }

    private fun injectExtract() {
        // WB 走 OAuth 时不要用网页 dump 覆盖状态栏文案（OAuth 轮询才是主链路）
        // Qoder 走 device flow：token 由原生轮询 deviceToken/poll 拿，网页里没有可 dump 的东西
        if (provider == P_QD) return
        // 华为靠原生 CookieManager 采集，网页里没有可 dump 的凭据
        if (provider == P_HW) return
        // 豆包同理：整串 Cookie 由原生 CookieManager 采集
        if (provider == P_DB) return
        // 智谱 / 百度：整串 Cookie 由原生 CookieManager 采集，网页里没有可 dump 的凭据
        if (provider == P_ZP) return
        if (provider == P_QF) return
        if (provider != P_TRAE && provider != P_MM && !wbState.isNullOrBlank()) return
        // MiniMax 只在业务页扫：`onPageFinished` 会在授权页（account.minimax.cn）
        // 也触发一次，那一页的 localStorage 全是登录中间态，扫了就是误判（用户 m13107）。
        if (provider == P_MM && !mmOnBusinessPage()) return
        web.evaluateJavascript(
            when (provider) {
                P_TRAE -> traeJs()
                P_MM -> mmJs()
                else -> wbDumpJs()
            },
            null
        )
    }

    private fun extractNow() {
        if (provider == P_HW) {
            // 华为：点「确认」= 立刻采集一次 Cookie（不等页面再加载）。
            // 积分接口就在签到同域，所以这里只有「采 Cookie」一件事。
            if (saved) {
                status.text = "华为码道 Cookie 已保存，可以点「关闭」返回"
                return
            }
            status.text = "正在确认登录状态并采集 Cookie…"
            hwCapture(false)
            return
        }
        if (provider == P_DB) {
            // 豆包：点「确认」= 立刻采集一次 Cookie（不等页面再加载）
            if (saved) {
                status.text = "豆包 Cookie 已保存，可以点「关闭」返回"
                return
            }
            status.text = "正在采集豆包 Cookie…"
            dbCapture(false)
            return
        }
        if (provider == P_ZP) {
            if (saved) {
                status.text = "智谱 Cookie 已保存，可以点「关闭」返回"
                return
            }
            status.text = "正在确认登录状态并采集智谱 Cookie…"
            zpCapture(false)
            return
        }
        if (provider == P_QF) {
            if (saved) {
                status.text = "百度智能云 Cookie 已保存，可以点「关闭」返回"
                return
            }
            status.text = "正在确认登录状态并采集百度 Cookie…"
            qfCapture(false)
            return
        }
        if (provider != P_TRAE && provider != P_MM && !wbState.isNullOrBlank()) {
            // OAuth 模式：点「完成」= 立刻确认一次授权结果；还没授权就重新打开登录页
            status.text = "正在确认授权结果…"
            Thread {
                val st = wbState ?: return@Thread
                val tb = try { WbApi.oauthPoll(wbVariant(), st) } catch (e: Exception) { null }
                if (tb != null) {
                    val prof = try { WbApi.fetchProfile(wbVariant(), st, tb.accessToken, tb.domain) } catch (e: Exception) { null }
                    saveWbResult(
                        tb.accessToken, tb.refreshToken,
                        prof?.optString("uid", "") ?: "",
                        tb.domain,
                        (prof?.optString("enterpriseId", "") ?: "")
                            .ifBlank { prof?.optString("enterprise_id", "") ?: "" }
                    )
                    saved = true
                    ui {
                        Toast.makeText(
                            this@LoginWebActivity,
                            if (toList) "已添加 WorkBuddy 账号" else "主账号登录成功",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@Thread
                }
                ui {
                    status.text = "还没授权完成，已重新打开登录页"
                    try {
                        WbApi.oauthStart(wbVariant())?.let { s2 ->
                            wbState = s2.state
                            web.loadUrl(s2.authUrl)
                        }
                    } catch (_: Exception) {}
                }
            }.also { it.isDaemon = true }.start()
            return
        }
        injectExtract()
        if (provider == P_TRAE) {
            // 点「完成」= 立刻主动去 CookieStore 抓一次 session（HttpOnly 只能这样拿）
            val sess = readTraeSessionFromCookies()
            if (sess.isNotBlank()) {
                persistTrae(tokenOnlyCandidate, sess)
            } else {
                status.text = "还没读到 X-Cloudide-Session；请先在上方网页完成登录，或用「粘贴」手动填"
                Toast.makeText(this, "没找到 session，可点「粘贴」手动填", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (provider == P_MM) {
            // 点「确认」= 立刻再抓一次；还空就提示手动粘贴
            //
            // 但只在业务页抓：用户可能还在手机号/验证码页上就点了「确认」，
            // 那一页的 localStorage 里是 csrf 之类的中间态，抓了必然误判
            // （用户 m13107：点完「获取验证码」就被弹回软件）。
            if (!mmOnBusinessPage()) {
                status.text = "还没登录完（当前在授权页）。请先把手机号和验证码输完，跳回 MiniMax 页面后会自动保存"
                Toast.makeText(this, "请先完成登录", Toast.LENGTH_SHORT).show()
                return
            }
            web.evaluateJavascript(mmJs(), null)
            status.text = "已尝试读取 token；若没反应，请确认已在页面里登录，或点「粘贴」手动填"
            return
        }
        if (provider == P_QD) {
            // 点「确认」= 立刻再 poll 一次 device flow（正常情况后台每秒都在轮询）
            status.text = "正在确认 Qoder 授权结果…"
            Thread {
                val doc = qdPollOnce()
                if (doc != null) {
                    persistQoder(doc.first, doc.second)
                } else {
                    ui {
                        status.text = "还没完成授权；请在上方网页里用阿里云/手机号登录 Qoder"
                    }
                }
            }.also { it.isDaemon = true }.start()
            return
        }
        status.text = "已尝试读取；若没反应点「粘贴」手动保存"
    }

    // ---------- 结果处理 ----------
    private fun handleToken(json: String) = handleToken(json, manual = false)

    /**
     * @param manual 用户主动点「完成」/「粘贴」传 true：只有 token 也保存并退出。
     *               自动轮询传 false：只有 token 时**不退出**，继续等 HttpOnly session 到位，
     *               否则会出现「点登录立刻返回，提示拿到 token 但没有 session」。
     */
    private fun handleToken(json: String, manual: Boolean) {
        if (saved) return
        try {
            val o = JSONObject(json)
            if (o.optString("provider", provider) != P_TRAE) return
            val tok = o.optString("token", "")
            var sess = o.optString("session", "")
            // X-Cloudide-Session 是 HttpOnly Cookie，document.cookie 读不到。
            // 退回原生 CookieManager（能拿到 HttpOnly），覆盖 trae 的多个域。
            if (sess.isBlank()) sess = readTraeSessionFromCookies()

            if (sess.isNotBlank()) {
                persistTrae(tok, sess)
                return
            }
            if (tok.length < 10) return
            // 只有 token 没有 session
            tokenOnlyCandidate = tok
            if (!manual) {
                status.text = "已读到 token，还在等 session 落地（HttpOnly）…请在页面里完成登录"
                return
            }
            persistTrae(tok, "")
        } catch (_: Exception) {}
    }

    /**
     * 主账号（设置页）里那份真实机器号。
     *
     * **x-device-id 是「机器指纹」，不是「账号凭据」**：
     *   · 桌面客户端的 Aha 机器号来自 `%APPDATA%\TRAE*\User\globalStorage\storage.json`，
     *     一台机器只有一个；`docs/TRAE.md:112` 的「用了别人的设备号」指的是别人的机器号。
     *   · `docs/REVIEW.md:233`「设备号每次登录都变，等于每天换一台新设备在签到 → 9074」。
     * 所以同一台手机上所有 Trae 账号**理应共用一个机器号**。
     *
     * v2.4 曾改成「每账号 generate() 一个随机 16 位数」，结果用户截图里账号 2 的设备号
     * 变成 5252260219850605（不是桌面客户端那串 5250250676249340）仍然 9074 ——
     * 反证随机生成的号服务端不认。
     *
     * v2.6 定论：9074 = 这个设备号在该账号下**没有注册记录**（上游文案「当前参与用户太多」
     * 是误导）。所以设备号必须满足两条：① 一个账号一个号，绝不共用；② 永久稳定。
     * 做法是按账号凭据**确定性派生**（[DeviceId.derive]），同一账号无论重装、换机、
     * 过多久都算出同一串，一次注册长期有效；用户若手填了真实机器号则优先用他的。
     */
    private fun traeDeviceFor(tok: String, sess: String): String = DeviceId.derive(sess.ifBlank { tok })

    /**
     * 给一个 Trae 账号取设备号（v2.19）。
     *
     * 规则只有一条：**已经在列表里的账号，沿用它原来的号，绝不重算**。
     *
     * 旧实现在重新登录时无条件 `DeviceId.derive(sess.ifBlank { tok })`，而 token 约 8 小时
     * 轮换、session 约 14 天轮换 —— 于是同一个账号每次重登都会换一个新设备号。服务端看到
     * 「同一账号天天换设备」，命中的是 9074 = `ErrWorkRiskControlBanned`（风控封禁），
     * 不是「设备未注册」（那是 9095 `ErrCheckinCreditsDeviceAlreadyUsed`）。
     * 用户两张截图里设备号从 5170205103104225 变成 6210351913917244，就是这个 bug。
     */
    private fun traeDeviceForExisting(tok: String, sess: String): String {
        val old = TraeAccountStore.all(this).firstOrNull {
            (tok.length >= 10 && it.token == tok) || (sess.isNotBlank() && it.session == sess)
        }
        if (old != null && DeviceId.isValid(old.deviceId)) return old.deviceId
        return traeDeviceFor(tok, sess)
    }

    /**
     * 后台取一次 Trae 资料（昵称 + 手机号打码串）并写回账号列表（用户 m16091 / m16555）。
     *
     * 为什么异步：`GetUserInfo` 是一次真实网络请求（`x-cloudide-token` 那套鉴权），
     * 阻塞在 `persistTrae` 里会让登录页多转一圈；而昵称/手机号只是展示用的附加信息，
     * 晚一两秒写进去完全没关系。
     *
     * 为什么可能取不到：网页登录只拿到 HttpOnly 的 `X-Cloudide-Session`，
     * 而 `GetUserInfo` 只认 `x-cloudide-token`（实测带 `Authorization: Cloud-IDE-JWT`
     * 返 401 20310）。只有网页 localStorage 里恰好有 `Cloud-IDE-Token` 时才有值。
     */
    private fun fetchTraeProfile(accountId: String, tok: String, dev: String) {
        if (tok.length < 10) return
        Thread {
            val (name, phone) = try {
                runBlocking { LocalCheckin.traeProfile(tok, dev, false) }
            } catch (_: Exception) { Pair("", "") }
            if (name.isBlank() && phone.isBlank()) return@Thread
            try {
                val old = TraeAccountStore.all(this).firstOrNull { it.id == accountId } ?: return@Thread
                if (old.nickname == name && old.phone == phone) return@Thread
                TraeAccountStore.update(this, old.copy(
                    nickname = name.ifBlank { old.nickname },
                    phone = phone.ifBlank { old.phone },
                ))
            } catch (_: Exception) { }
        }.start()
    }

    /**
     * 后台取一次 Qoder 昵称并写回账号列表（用户 m16091）。
     *
     * `GET /api/v1/collaboration/users/me` 实测 200，返回 `data.nickname` / `data.displayName`。
     * 同样异步：不阻塞登录页收尾。
     */
    private fun fetchQoderNickname(accountId: String, tok: String, refresh: String, machineId: String) {
        if (tok.isBlank() && refresh.isBlank()) return
        Thread {
            val name = try {
                runBlocking { LocalCheckin.qoderNickname("cn", tok, machineId, "", refresh) }
            } catch (_: Exception) { "" }
            if (name.isBlank()) return@Thread
            try {
                val old = QoderAccountStore.all(this).firstOrNull { it.id == accountId } ?: return@Thread
                if (old.nickname == name) return@Thread
                QoderAccountStore.update(this, old.copy(nickname = name))
            } catch (_: Exception) { }
        }.start()
    }

    private fun persistTrae(tok: String, sess: String) {
        if (saved) return
        // 关键：存 session（约 14 天）而不只是 token（约 8 小时）。
        // 有 session 才能自动续期，否则 8 小时后必然 401。
        // 同步写完再 finish：避免 WebView 恢复时并发读 DataStore 抛错（见 saveBlocking 注释）。
        var added = false
        if (toList) {
            // 多账号通道：追加到列表，不覆盖设置页里的主账号
            val dev = traeDeviceForExisting(tok, sess)
            if (tok.isBlank() && sess.isBlank()) { Toast.makeText(this, "没读到任何凭据，请重试", Toast.LENGTH_LONG).show(); return }
            // 抓到的还是主账号 / 列表里已有账号的凭据 → 说明「记住登录」没被清掉，
            // 页面直接进了上一个号。此时**不能落库**（会把旧凭据当成新账号，或者把
            // 主账号的 session 复制一份），继续等用户在页面里换成另一个号。
            val main = try { runBlocking { SettingsStore.flow(this@LoginWebActivity).first() } } catch (_: Exception) { AppSettings() }
            val sameAsMain = (sess.isNotBlank() && sess == main.traeSession) ||
                (tok.length >= 10 && tok == main.traeToken)
            if (sameAsMain) {
                status.text = "页面进的还是主账号；请先退出登录，再用另一个 Trae 账号登录"
                return
            }
            // V2.78（用户 m05383「主账号签不上、账号 2 能签」）：这里是同一个上游账号的**重新登录**。
            //
            // 以前只判 session/token 完全相同（上面那段），所以「同一个 Trae 号重新登录一次」——
            // 换了新 token、新 session —— 会被当成一个新账号追加进列表，而主账号仍旧握着
            // 那份早已失效的旧凭据。结果就是两张卡指着同一个上游号：主账号那张每次签到必然
            // 失败（红叉），列表里那张成功（绿勾），余额还一模一样（都是同一个号的余额）。
            //
            // 判据用 JWT 里的 uid（sub）：同一个号重登会换 token + session，只有 uid 不变。
            // uid 相同时**刷新主账号凭据**，不追加列表项。设备号沿用主账号已有的合法号
            // （设备号必须永久稳定，换号等于换设备，会触发 9074 风控）。
            val mainUid = if (main.traeToken.length >= 10) LocalCheckin.jwtUid(main.traeToken) else ""
            val newUid = if (tok.length >= 10) LocalCheckin.jwtUid(tok) else ""
            if (newUid.isNotBlank() && newUid == mainUid) {
                saveBlocking { s ->
                    s.copy(
                        traeToken = if (tok.length >= 10) tok else s.traeToken,
                        traeSession = if (sess.isNotBlank()) sess else s.traeSession,
                        traeDeviceId = if (DeviceId.isValid(s.traeDeviceId)) s.traeDeviceId
                        else traeDeviceFor(tok, sess),
                    )
                }
                status.text = "这仍是主账号（uid $newUid）；已用它刚登录拿到的新凭据刷新主账号，未新增账号"
                Toast.makeText(this, "已刷新主账号 Trae 凭据（同一个账号，未新增）", Toast.LENGTH_LONG).show()
                saved = true; finish()
                return
            }
            val acc = TraeAccount(
                id = AccountIds.stable(tok, sess, dev),
                token = if (tok.length >= 10) tok else "",
                session = sess,
                deviceId = dev,
                global = false,
                // JWT 里的上游 uid：同一个号重新登录会换 token + session，
                // 只有 uid 不变。不记它，同一个号会被存成好几条（用户 m18704 的
                // 「每个账号积分都一致」就是三条记录指着同一个上游账号）。
                uid = if (tok.length >= 10) LocalCheckin.jwtUid(tok) else "",
            )
            val dup = TraeAccountStore.all(this).any {
                (acc.uid.isNotBlank() && it.uid == acc.uid) ||
                    (tok.length >= 10 && it.token == tok) ||
                    (sess.isNotBlank() && it.session == sess)
            }
            val stored = TraeAccountStore.upsert(this, acc)
            // 昵称异步补（用户 m16091）：GetUserInfo 只认 x-cloudide-token，
            // 网页登录只拿到 session 时拿不到，会返回空串、界面回落「账号 N」
            fetchTraeProfile(stored.id, tok, dev)
            added = true
            if (dup) {
                Toast.makeText(this, "这个 Trae 账号已经在列表里了（未新增）", Toast.LENGTH_LONG).show()
                saved = true; finish()
                return
            }
        } else {
            saveBlocking { s ->
                s.copy(
                    traeToken = if (tok.length >= 10) tok else s.traeToken,
                    traeSession = if (sess.isNotBlank()) sess else s.traeSession,
                    // 已有合法设备号就保留（用户手填的真实机器号最可靠），
                    // 没有才按凭据派生 —— 绝不再随机生成，否则等于每天换一台新设备。
                    traeDeviceId = if (DeviceId.isValid(s.traeDeviceId)) s.traeDeviceId
                    else traeDeviceFor(tok, sess),
                )
            }
        }
        val msg = when {
            added && sess.isNotBlank() -> "已添加 Trae 账号（含自动续期 session）"
            added -> "已添加 Trae 账号（仅 token，8 小时后需重登）"
            sess.isNotBlank() && tok.length >= 10 -> "已获取 Trae 凭据（含自动续期 session）"
            sess.isNotBlank() -> "已获取 Trae session（token 过期会自动换新）"
            else -> "只拿到 token（8 小时后需重登）；session 是 HttpOnly，可点「粘贴」手动填"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        saved = true; finish()
    }

    /**
     * 从原生 CookieManager 里找 X-Cloudide-Session。
     * WebView 的 document.cookie 拿不到 HttpOnly，只有系统 CookieStore 能拿到，
     * 而 trae 的 session 恰恰是 HttpOnly —— 这就是之前「提示已获取 token 但没有 session」的原因。
     */
    private fun readTraeSessionFromCookies(): String {
        val cm = CookieManager.getInstance()
        try { cm.flush() } catch (_: Exception) {}
        val urls = listOf(
            "https://www.trae.cn/",
            "https://trae.cn/",
            "https://api.trae.cn/",
            "https://work.trae.cn/",
            "https://www.trae.ai/",
            "https://api.trae.ai/",
            "https://work.trae.ai/",
        )
        for (u in urls) {
            val ck = try { cm.getCookie(u) } catch (_: Exception) { null } ?: continue
            if (ck.isBlank()) continue
            for (pair in ck.split(";")) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val k = pair.substring(0, i).trim()
                if (!k.equals("X-Cloudide-Session", ignoreCase = true)) continue
                val v = pair.substring(i + 1).trim()
                if (v.isNotBlank()) return try { android.net.Uri.decode(v) } catch (_: Exception) { v }
            }
        }
        return ""
    }

    private fun handleDump(json: String) {
        if (saved) return
        try {
            val o = JSONObject(json)
            val local = o.optString("ls", "")
            val session = o.optString("ss", "")
            val cookieJs = o.optString("ck", "")
            val cookieNative = try {
                CookieManager.getInstance().getCookie(web.url ?: "") ?: ""
            } catch (e: Exception) { "" }
            val all = listOf(local, session, cookieJs, cookieNative).joinToString("\n")

            val at = findVal(all, listOf("accessToken", "access_token", "access-token"))
            val rt = findVal(all, listOf("refreshToken", "refresh_token", "refresh-token"))
            val uid = findVal(all, listOf("uid", "user_id", "userId", "userid"))
            val domain = findVal(all, listOf("domain"))
            var token = at
            if (token.isNullOrBlank() || token.length < 20) {
                token = findJwt(all)
            }
            if (!token.isNullOrBlank() && token.length >= 20) {
                saveWb(token, rt, uid, domain)
                Toast.makeText(this, "已获取 WorkBuddy 凭据", Toast.LENGTH_LONG).show()
                saved = true; finish()
                return
            }
            lastCandidate = at ?: token ?: ""
            status.text = "已登录，但没自动识别到 accessToken；点「粘贴」可手动保存"
        } catch (_: Exception) {}
    }

    // ---------- MiniMax ----------

    /**
     * 处理网页回传的 MiniMax token。
     * 实测：网页端 `_token` 是不带前缀的裸串，服务端认裸 `token` 头（见 `_signed_request`）；
     * 桌面客户端的 `mmoat_` token 才走 `Authorization: Bearer`。两处判据已分开。
     * user_id / device_id 不参与鉴权（我用 user_id=0 + 默认 device_id 也拿到了完整签到面板），
     * 所以这里只要有 token 就存，userId/deviceId 能抓到就顺带补上，抓不到也不影响签到。
     */
    private fun handleMmToken(json: String) {
        if (saved) return
        try {
            val o = JSONObject(json)
            val tok = o.optString("token", "").trim()
            val uid = o.optString("userId", "").trim()
            val did = o.optString("deviceId", "").trim()
            val nick = o.optString("nickname", "").trim()
            if (nick.isNotBlank()) mmNickname = nick
            if (tok.isNotBlank() && tok.length >= 20) {
                persistMm(tok, uid, did)
                return
            }
            // 网页还没登录完，保持轮询即可，不打断用户
        } catch (_: Exception) {}
    }

    /**
     * 判断一个字符串像不像 MiniMax 的 accessToken。
     *
     * **不要用 `mmoat_` 前缀做判据**：桌面客户端 `auth.json` 里的 accessToken 确实是
     * `mmoat_` 开头，但**网页端的 `_token` 是不带前缀的裸串**（我扫完 agent.minimax.cn
     * 的全部 28 个 JS，共 2,630,423 字符，`mmoat_` 出现 0 次；Web 端把 localStorage
     * 的 `_token` 直接塞进请求头 `token`，见 axios 拦截器）。
     * 用前缀判据的后果：网页登录明明成功，却提示「这个不是 MiniMax 的 accessToken」，
     * 用户永远配不上（截图 m12027 那条就是）。
     *
     * 所以改成「排除明显不是 token 的东西」：UUID、纯数字、含 JSON 字符或空白的串。
     */
    private fun looksLikeMmToken(v: String): Boolean {
        val t = v.trim()
        if (t.length < 20 || t.length > 512) return false
        if (Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(t)) return false
        if (t.all { it.isDigit() }) return false
        if (t.any { it == '{' || it == '}' || it == '"' || it == ',' || it.isWhitespace() }) return false
        return true
    }

    /**
     * 落库前先在后台线程问一次服务端：这份 token 到底认不认。
     *
     * **为什么必须有这一步**（用户 m13107）：用户在手机号登录页刚点完「获取验证码」、
     * 人机验证还没出来，页面就自己弹回软件了，之后签到 401。根因是 `mmJs()` 的
     * 1.5 秒轮询在**登录页**上扫 localStorage —— 那时候站点已经开始往 localStorage
     * 里写东西（csrf、临时会话串），`looksLikeMmToken()` 只判「长得像 token」，
     * 判不了「是不是真 token」，于是一命中就 `saved = true; finish()`，把用户踢出登录页。
     *
     * 现在的流程：结构判据过 → 后台打一次只读的 `signin/status` →
     * 服务端返回 `base_resp.status_code == 0` 且有 `data` 才落库并关闭页面；
     * 否则只更新顶栏提示，**保持登录页打开**，让用户把验证码输完。
     *
     * 探针走网络，不能在 UI 线程做（会 ANR），所以起一个短线程；
     * `probeBusy` 保证同一时刻只有一个探针在飞，避免轮询把线程堆起来。
     */
    private fun persistMm(tok: String, uid: String = "", did: String = "", manual: Boolean = false) {
        if (saved) return
        // 以前的判据是 `if (!tok.startsWith("mmoat_")) return` —— 错在把桌面客户端的
        // token 形态当成了唯一形态。网页端 `_token` 没有前缀，被这句直接拦掉，
        // 界面显示「这个不是 MiniMax 的 accessToken（应以 mmoat_ 开头）」，
        // 而实际上那正是对的 token（截图 m12027）。
        //
        // 用户 m13898：顶栏这几条「过程提示」必须删掉。
        // 轮询每 1.5 秒跑一次，会在这几条文案之间来回切：
        //   ① 值不像 token（csrf / code / 纯数字）
        //   ② 已读到候选 token，正在向 MiniMax 校验…
        //   ③ 这段值 MiniMax 不认…
        // 三条字数差得多、行数 1~3 行不等，顶栏高度跟着变，下面的 WebView 被顶得
        // 一直上下跳（用户看到的「两个信息窗口在打架」）。这些过程信息对用户没有
        // 任何可操作性，自动路径下一律静默 —— 顶栏保持「在下方网页登录 MiniMax…」
        // 不动，只有真正落库（commitMm）或超时才改它。
        // 只有「粘贴」这条手动路径（manual=true）才需要告诉用户「这份值不行」，
        // 因为那是用户主动提交的动作，静默会让他以为按钮坏了。
        if (!looksLikeMmToken(tok)) {
            if (manual) {
                status.text = "这份值不像 accessToken（可能是 csrf / code / 纯数字）；请从 F12 → Local Storage 里复制 _token 的原值"
            }
            return
        }
        if (probeBusy) return
        probeBusy = true
        Thread {
            val verdict = try {
                runBlocking { LocalCheckin.minimaxProbe(tok, uid, did, "") }
            } catch (_: Exception) {
                null
            }
            ui {
                probeBusy = false
                if (saved) return@ui
                when (verdict) {
                    true -> commitMm(tok, uid, did)
                    // 明确不认：登录过程中的临时串，继续等真 token。**不 finish、不改文案**。
                    // 手动粘贴时给一句反馈，否则用户不知道点了没反应。
                    false -> if (manual) {
                        status.text = "这份值 MiniMax 不认（HTTP 401）；请确认复制的是 localStorage['_token'] 的原值"
                    }
                    // 网络不通 / 服务端 5xx / 我们自己的签名算错：问不出来就不能拦用户。
                    // 直接落库，让用户自己去点「测试签到」验证 —— 比把他卡在登录页强。
                    null -> commitMm(tok, uid, did)
                }
            }
        }.start()
    }

    /** 探针通过之后的真正落库动作（原来的 persistMm 主体）。 */
    private fun commitMm(tok: String, uid: String, did: String) {
        if (saved) return
        if (toList) {
            // 多账号通道：追加到列表，不覆盖设置页里的主账号
            //
            // 抓到的还是主账号（或列表里已有账号）的 token → 说明「记住登录」没被清掉，
            // 页面直接进了上一个号。此时**不能落库**：MiniMax 的 `_token` 存在 localStorage，
            // clearWebLoginState 虽然清了 WebStorage，但站点可能在清完之后又从 Cookie / SSO
            // 恢复出上一个账号的会话；直接把旧 token 当新账号存下来，签到必然 401
            // （截图里那条「响应非 JSON（HTTP 401）」就是这么来的）。
            val main = try { runBlocking { SettingsStore.flow(this@LoginWebActivity).first() } } catch (_: Exception) { AppSettings() }
            if (tok.isNotBlank() && tok == main.mmToken) {
                status.text = "页面进的还是主账号；请先在网页里退出登录，再用另一个 MiniMax 账号登录"
                return
            }
            if (uid.isNotBlank() && uid == main.mmUserId) {
                status.text = "页面进的还是主账号（user_id 相同）；请换另一个 MiniMax 账号登录"
                return
            }
            val acc = MmAccount(
                id = AccountIds.stable(uid, tok),
                token = tok, userId = uid, deviceId = did,
            )
            val dup = MmAccountStore.all(this).any {
                (tok.isNotBlank() && it.token == tok) || (uid.isNotBlank() && it.userId == uid)
            }
            val stored = MmAccountStore.upsert(this, acc)
            // MiniMax 没有可用的昵称接口（扫完 24 个 bundle 全是 404），
            // 这里只把网页里顺手 dump 到的名字塞进去；取不到就留空
            if (mmNickname.isNotBlank()) {
                MmAccountStore.update(this, stored.copy(nickname = mmNickname))
            }
            Toast.makeText(
                this,
                if (dup) "这个 MiniMax 账号已经在列表里了（未新增）" else "已添加 MiniMax 账号",
                Toast.LENGTH_LONG
            ).show()
            saved = true; finish()
            return
        }
        // 同步写完再 finish（同 persistQoder 的理由：避免 WebView 恢复时并发读 DataStore 抛错）
        val ok = saveBlocking { s ->
            s.copy(
                mmToken = tok,
                mmUserId = if (uid.isNotBlank()) uid else s.mmUserId,
                mmDeviceId = if (did.isNotBlank()) did else s.mmDeviceId,
            )
        }
        Toast.makeText(
            this,
            if (ok) "已获取 MiniMax token" else "保存失败，请重试（点「粘贴」也能手动填）",
            Toast.LENGTH_LONG
        ).show()
        saved = true; finish()
    }

    /** 兜底：从原生 Cookie 里找 _minimax_token / minimax_token（实测服务端不认，但先抓下来） */
    private fun readMmTokenFromCookies(): String {
        val cm = CookieManager.getInstance()
        try { cm.flush() } catch (_: Exception) {}
        val urls = listOf(
            "https://agent.minimax.cn/",
            "https://agent.minimaxi.com/",
            "https://account.minimax.cn/",
            "https://www.minimax.io/",
        )
        for (u in urls) {
            val ck = try { cm.getCookie(u) } catch (_: Exception) { null } ?: continue
            if (ck.isBlank()) continue
            for (pair in ck.split(";")) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val k = pair.substring(0, i).trim()
                if (!k.contains("token", ignoreCase = true)) continue
                val v = pair.substring(i + 1).trim()
                // 同 persistMm：不再用 `mmoat_` 前缀，改成结构判据。
                // 否则 `csrf_token` / `_csrfToken` 之类会被误判成 token（它们本来就该被排除，
                // 但排除它们靠的是「像不像 token」，不是「有没有前缀」）。
                val dec = try { android.net.Uri.decode(v) } catch (_: Exception) { v }
                if (looksLikeMmToken(dec)) return dec
            }
        }
        return ""
    }

    private fun saveWb(at: String, rt: String?, uid: String?, domain: String?, ent: String? = null) {        saveAsync { s -> s.copy(
            wbToken = at,
            wbRefreshToken = if (!rt.isNullOrBlank()) rt else s.wbRefreshToken,
            wbUid = if (!uid.isNullOrBlank()) uid else s.wbUid,
            wbDomain = if (!domain.isNullOrBlank()) domain else s.wbDomain,
            wbEnterpriseId = if (!ent.isNullOrBlank()) ent else s.wbEnterpriseId
        ) }
    }

    /** 同步保存（OAuth 命中后调用；写完才关页面，避免 DataStore 还没落盘界面就没了） */
    private fun saveWbBlocking(at: String, rt: String?, uid: String?, domain: String?, ent: String?) {
        try {
            runBlocking {
                val cur = SettingsStore.flow(this@LoginWebActivity).first()
                SettingsStore.save(
                    this@LoginWebActivity,
                    cur.copy(
                        wbToken = at,
                        wbRefreshToken = if (!rt.isNullOrBlank()) rt else cur.wbRefreshToken,
                        wbUid = if (!uid.isNullOrBlank()) uid else cur.wbUid,
                        wbDomain = if (!domain.isNullOrBlank()) domain else cur.wbDomain,
                        wbEnterpriseId = if (!ent.isNullOrBlank()) ent else cur.wbEnterpriseId,
                        // 区域跟登录入口走：从「国际版」按钮进来就置 true，从「主账号」进来置 false。
                        // 只在这个单账号通道写，多账号通道由 WbAccount.variant 各自记住。
                        wbGlobal = wbVariant() == "ai"
                    )
                )
            }
        } catch (_: Exception) {}
    }

    /** 在整段文本里按 key 找 "k":"v"、k=v、k:v 的值 */
    private fun findVal(text: String, keys: List<String>): String? {
        for (k in keys) {
            val esc = Regex.escape(k)
            val pats = listOf(
                Regex("\"$esc\"\\s*:\\s*\"([^\"]{6,})\""),
                Regex("[\\?&;\"\\s]$esc=([^;\"&#\\s]{6,})", RegexOption.IGNORE_CASE),
                Regex("\"$esc\"\\s*[:=]\\s*([A-Za-z0-9_\\-\\.]{20,})")
            )
            for (p in pats) {
                val m = p.find(text)
                if (m != null) return m.groupValues[1]
            }
        }
        return null
    }

    private fun findJwt(text: String): String? =
        Regex("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+").find(text)?.value

    private fun saveAsync(extra: (AppSettings) -> AppSettings) {
        Thread {
            runBlocking {
                try {
                    val cur = SettingsStore.flow(this@LoginWebActivity).first()
                    SettingsStore.save(this@LoginWebActivity, extra(cur))
                } catch (_: Exception) {}
            }
        }.start()
    }

    /**
     * 同步保存并等到真正落盘。
     *
     * 为什么必须有这个：`saveAsync` 是「起个线程写，然后立刻 finish()」。Activity 一关，
     * WebUiActivity 立刻 resume 并执行 `__signhubReload` → `loadAll()` 并发打
     * `/api/status` + `/api/config` + `/api/logs` + `/api/stats`。这四个请求各自
     * `SettingsStore.flow(ctx).first()`，正好撞上还在写的 DataStore 事务 ——
     * DataStore 在读写竞争时会抛异常，`LocalApi.handle()` 的 catch 把它变成
     * `{"error": ...}`，前端 `renderProviderEditors()` 因为拿不到 `cfg.providers`
     * 直接 return → **设置页签到配置整片空白**。
     *
     * 用户实测反馈就是这个现象：「返回软件直接到首页，设置页没有任何数据出来」。
     * 所以凡是「存完就 finish()」的路径，都必须用这个同步版本。
     */
    private fun saveBlocking(extra: (AppSettings) -> AppSettings): Boolean {
        return try {
            runBlocking {
                val cur = SettingsStore.flow(this@LoginWebActivity).first()
                SettingsStore.save(this@LoginWebActivity, extra(cur))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- 兜底：手动粘贴 ----------
    private fun pasteFallback() {
        val tip = when (provider) {
            P_TRAE ->
                "粘贴 trae.cn 的 X-Cloudide-Session（推荐，约 14 天，能自动续期）；" +
                    "也可以只粘 Cloud-IDE-Token，但它约 8 小时就过期。" +
                    "抓法：F12 → Application → Cookies → www.trae.cn → 复制 X-Cloudide-Session 的值。"
            P_MM ->
                "粘贴 MiniMax 的 accessToken（网页端 _token 的原值，没有固定前缀，通常 20~200 字符）。" +
                    "抓法：浏览器登录 agent.minimax.cn → F12 → Application → Local Storage → " +
                    "https://agent.minimax.cn → 复制键名 _token 的值。"
            P_QD ->
                "推荐直接粘贴 refreshToken（drt- 开头）：有效期一年，token 过期会自动续，只填它也能签到。" +
                    "也可以粘 accessToken（dt- 开头），但它 30 天就过期。" +
                    "抓法：电脑上登录 Qoder CN 客户端，在本软件设置里点「读取本机凭据」，把 token / refreshToken 抄过来。"
            P_HW ->
                "粘贴华为码道的整串 Cookie（必须含 devclouddevuibjJ_SESSION_ID）。" +
                    "抓法：浏览器登录 devcloud.cn-north-4.huaweicloud.com/chat/home → F12 → Network → " +
                    "任一带 PromptCenterService 的请求 → 复制请求头里的整条 Cookie。" +
                    "第二栏可填 codearts.huaweicloud.com 的 Cookie（只用于读积分余额，不填也能签到）。"
            P_DB ->
                "粘贴豆包网页版的整串 Cookie（必须含 sessionid）。" +
                    "抓法：浏览器登录 www.doubao.com → F12 → Application → Cookies → " +
                    "https://www.doubao.com → 全选复制成 name=value; name=value 形式。" +
                    "也可以 F12 → Network → 任一带 sessionid 的请求 → 复制请求头里的整条 Cookie。"
            P_ZP ->
                "粘贴智谱清言（chatglm.cn）的整串 Cookie（必须含 chatglm_token 与 chatglm_refresh_token）。" +
                    "抓法：浏览器登录 chatglm.cn → F12 → Application → Cookies → https://chatglm.cn → " +
                    "全选复制成 name=value; name=value 形式。" +
                    "注意：未登录游客也有一整套 token，粘进来会被判成游客态（JWT 里 is_guest=true），存不下。"
            P_QF ->
                "粘贴百度智能云的整串 Cookie（必须含 BDUSS 与 bce-user-info）。" +
                    "抓法：浏览器登录 console.bce.baidu.com → F12 → Application → Cookies → " +
                    "https://console.bce.baidu.com → 全选复制成 name=value; name=value 形式。" +
                    "其中 bce-user-info 会同时作为 csrftoken 请求头发出去，缺它领取会被拒。"
            else ->
                "粘贴 accessToken（WB 网页登录后 F12 里找 accessToken；第二栏可填 refreshToken 便于自动续期）"
        }
        val h1 = when (provider) {
            P_TRAE -> "X-Cloudide-Session（或 Cloud-IDE-Token）"
            P_MM -> "localStorage['_token'] 的值（无前缀）"
            P_QD -> "drt- 开头的 refreshToken（推荐）"
            P_HW -> "整串 Cookie（含 devclouddevuibjJ_SESSION_ID）"
            P_DB -> "整串 Cookie（含 sessionid）"
            P_ZP -> "整串 Cookie（含 chatglm_token）"
            P_QF -> "整串 Cookie（含 BDUSS）"
            else -> "accessToken"
        }
        val h2 = if (provider == P_QD) "dt- 开头的 accessToken（可选，不填也行）"
                 else if (provider == P_HW) "codearts.huaweicloud.com 的 Cookie（可选，只读积分）"
                 else "refreshToken（可选）"
        val fields = mutableListOf(SignDialog.Field(h1, lastCandidate, multiline = true))
        // 豆包 / 智谱 / 百度都只有一栏（整串 Cookie），第二栏无意义
        if (provider != P_TRAE && provider != P_MM && provider != P_DB &&
            provider != P_ZP && provider != P_QF) {
            fields.add(SignDialog.Field(h2, multiline = true))
        }

        SignDialog.input(
            this,
            kicker = "PASTE",
            title = "手动粘贴凭据",
            subtitle = tip,
            fields = fields,
            validate = { v -> if (v[0].length < 10) "凭据太短，至少 10 个字符" else null },
        ) { v ->
            val tok = v[0]
            val second = if (v.size > 1) v[1] else ""
            if (provider == P_TRAE) {
                // 粘贴的可能是 session 也可能是 JWT：JWT 有三段、以 eyJ 开头
                val isJwt = tok.startsWith("eyJ") && tok.count { it == '.' } == 2
                handleToken(
                    JSONObject().put("provider", "trae")
                        .put("token", if (isJwt) tok else "")
                        .put("session", if (isJwt) "" else tok)
                        .toString(),
                    manual = true
                )
            } else if (provider == P_MM) {
                persistMm(tok, manual = true)
            } else if (provider == P_QD) {
                // Qoder 两栏都收：第一栏优先当 refreshToken（drt-），
                // 如果第一栏是 dt- 就当 token，第二栏再补 refreshToken。
                when {
                    tok.startsWith("drt-") -> persistQoder("", tok)
                    tok.startsWith("dt-") -> persistQoder(tok, second)
                    second.startsWith("drt-") -> persistQoder(tok, second)
                    else -> persistQoder(tok, second)
                }
            } else if (provider == P_HW) {
                // 手动粘贴也走 probe 校验（BUGS-v2.41 P2-1）：华为对未登录访客也会发
                // 匿名 J_SESSION_ID，只查 cookie 名会把匿名会话存成「已就绪」。
                persistHwVerified(tok, second)
            } else if (provider == P_DB) {
                // 豆包不需要 probe：游客态一个 cookie 都拿不到（实测），
                // 有 sessionid 就等于已登录。只在本地做一次格式检查。
                val ck = tok.trim()
                if (!DoubaoWebCheckin.hasSession(ck)) {
                    Toast.makeText(
                        this,
                        "这段 Cookie 里没找到 sessionid / sid_tt / uid_tt / sid_guard，请重新复制整条 Cookie",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    persistDb(ck, auto = false)
                    Toast.makeText(this, "已保存豆包 Cookie", Toast.LENGTH_SHORT).show()
                }
            } else if (provider == P_ZP) {
                // 智谱：游客也有一整套 token，所以不能只判「有没有 chatglm_token」，
                // 必须让 ZhipuCheckin.hasSession 去看 JWT 里的 is_guest。
                val ck = tok.trim()
                if (!ZhipuCheckin.hasSession(ck)) {
                    Toast.makeText(
                        this,
                        "这段 Cookie 是游客态或缺少 chatglm_refresh_token：请先在 chatglm.cn 真的登录一次再复制",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    persistZp(ck)
                    Toast.makeText(this, "已保存智谱 Cookie", Toast.LENGTH_SHORT).show()
                }
            } else if (provider == P_QF) {
                // 百度：BDUSS 是登录态、bce-user-info 是 CSRF 令牌，缺任一个都不算登录
                val ck = tok.trim()
                if (!DumateCheckin.hasSession(ck)) {
                    Toast.makeText(
                        this,
                        "这段 Cookie 里缺少 BDUSS 或 bce-user-info：还没登录，请重新复制整条 Cookie",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    persistQf(ck)
                    Toast.makeText(this, "已保存百度智能云 Cookie", Toast.LENGTH_SHORT).show()
                }
            } else {
                saveWb(tok, second, null, null)
                Toast.makeText(this, "已保存 WorkBuddy 凭据", Toast.LENGTH_SHORT).show()
                saved = true; finish()
            }
        }
    }

    // ---------- 抓取脚本 ----------
    private fun wbDumpJs(): String = """
        (function(){
          try {
            function dumpStorage(st){
              var o={};
              try{ for(var i=0;i<st.length;i++){ var k=st.key(i); o[k]=st.getItem(k); } }catch(e){}
              return o;
            }
            var ls = dumpStorage(window.localStorage);
            var ss = dumpStorage(window.sessionStorage);
            var ck = document.cookie || '';
            window.SignHubLogin.onDump(JSON.stringify({ls:JSON.stringify(ls), ss:JSON.stringify(ss), ck:ck}));
          } catch(e){}
        })();
    """.trimIndent()

    /**
     * 读 Web 端 localStorage 里的 token。
     *
     * 已从 3.4MB 主 bundle（10489-*.js）与 27083-*.js 里核实清楚：
     *  - 存储键名常量表：`M="_token"`、`P="user_detail_agent"`、`L="UNIQUE_USER_ID"`、
     *    `N="tab_device_id"`；导出映射 `Xw -> M`、`rM -> P`、`FH -> L`。
     *  - 写入点：`function J(e){localStorage.setItem(F.Xw,e)}`（`_token` 是**裸字符串**，不是 JSON）。
     *  - 另一处权威来源：`user_detail_agent` 存 `{token, realUserID, op_ticket}`，
     *    bundle 里 `JSON.parse(localStorage.getItem(rM)||"{}").token` 就是这么兜底的。
     *  - 认证头：Web 端 axios 拦截器发的是 **`token: <值>`**（裸头），不是 `Authorization: Bearer`；
     *    客户端 token（`mmoat_` 开头）才走 Bearer。所以两处都要抓，认证头按形态二选一。
     *
     * **踩坑一**：早先版本用「长度 ≥ 24 且无空格」兜底扫全量 localStorage，结果抓到的是
     * `UNIQUE_USER_ID` 里的 UUID（36 字符，完全符合那个判据），拿 UUID 当 token 发出去必然 401。
     * 现在明确排除 UUID 形态与纯数字串。
     *
     * **踩坑二**：后来又把判据收紧成「必须 `mmoat_` 开头」—— 那是**桌面客户端**的 token 形态。
     * 扫完 agent.minimax.cn 的全部 28 个 JS（2,630,423 字符），`mmoat_` 出现 **0 次**；
     * 网页端 `_token` 就是不带前缀的裸串。用前缀判据的后果是网页登录明明成功却被拒，
     * 界面显示「这个不是 MiniMax 的 accessToken」（截图 m12027）。
     * 所以兜底扫描改成「只认结构像 token 的串」，不认前缀。
     */
    private fun mmJs(): String = """
        (function(){
          var UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}${'$'}/i;
          function ok(v){
            if (!v || typeof v !== 'string') return false;
            v = v.trim();
            if (v.length < 20 || v.length > 512) return false;
            if (UUID_RE.test(v)) return false;
            if (/^[0-9]+${'$'}/.test(v)) return false;
            if (v.indexOf('{') >= 0 || v.indexOf('"') >= 0 || v.indexOf(' ') >= 0) return false;
            return true;
          }
          function pick(){
            var a = '';
            try { a = (localStorage.getItem('_token') || '').trim(); } catch(e){}
            if (ok(a)) return a;
            try {
              var ud = localStorage.getItem('user_detail_agent');
              if (ud) {
                var o = JSON.parse(ud) || {};
                var b = String(o.token || o.access_token || '').trim();
                if (ok(b)) return b;
              }
            } catch(e){}
            try {
              for (var i=0;i<localStorage.length;i++){
                var k = localStorage.key(i);
                // 跳过明显不是 token 的键（埋点 / 版本 / 语言），避免误抓长串
                if (/^(_uetsid|_uetvid|_ga|_gid|_fbp|sawebjssdkchannel|mavis:|i18nextLng|version)/i.test(k)) continue;
                var v = (localStorage.getItem(k) || '').trim();
                if (ok(v)) return v;
              }
            } catch(e){}
            return '';
          }
          function digits(v){ return String(v||'').replace(/[^0-9]/g,''); }
          try {
            var tok = pick();
            var uid = '', did = '';
            try {
              var ud = localStorage.getItem('user_detail_agent');
              if (ud) { var o = JSON.parse(ud) || {}; uid = digits(o.realUserID || o.real_user_id || o.user_id); }
            } catch(e1){}
            // 实测 user_detail_agent 里通常没有 userId（于是账号名只能退化成 token 末 6 位，
            // 截图里那个「MiniMax · Tj48U0」就是这么来的）。站点自己的 bundle 把用户 ID
            // 存在 UNIQUE_USER_ID 里，这里补上它，账号名才有意义、多账号判重也更准。
            if (!uid) {
              try { uid = digits(localStorage.getItem('UNIQUE_USER_ID')); } catch(e4){}
            }
            if (!uid) {
              try { uid = digits(localStorage.getItem('unique_user_id')); } catch(e5){}
            }
            try { did = digits(localStorage.getItem('tab_device_id')); } catch(e3){}
            // 昵称（用户 m16091）：MiniMax 没有可用的昵称接口（24 个 bundle 扫完，
            // user 下的 15 个候选路径全是 404），只能从网页自己的存储里顺手捞。
            // 依次试 user_detail_agent 的 name/nickname/username、USER_NAME、nickname。
            var nick = '';
            try {
              var ud2 = localStorage.getItem('user_detail_agent');
              if (ud2) {
                var o2 = JSON.parse(ud2) || {};
                var n2 = o2.nickname || o2.name || o2.user_name || o2.username || o2.displayName || '';
                if (n2) nick = String(n2).trim();
              }
            } catch(e6){}
            if (!nick) {
              var keys = ['USER_NAME','user_name','nickname','NICKNAME','username','USER_NICKNAME'];
              for (var ki=0; ki<keys.length; ki++) {
                try {
                  var nv = (localStorage.getItem(keys[ki]) || '').trim();
                  if (nv && nv.length <= 40 && nv.indexOf('{') < 0) { nick = nv; break; }
                } catch(e7){}
              }
            }
            window.SignHubLogin.onMmToken(JSON.stringify({token:tok||'', userId:uid, deviceId:did, nickname:nick}));
          } catch(e){
            try { window.SignHubLogin.onMmToken(JSON.stringify({token:'', userId:'', deviceId:'', nickname:''})); } catch(e2){}
          }
        })();
    """.trimIndent()

    private fun traeJs(): String = """        (function(){
          try {
            var t = localStorage.getItem('Cloud-IDE-Token') || localStorage.getItem('cloud-ide-token') || '';
            if(!t){ for (var i=0;i<localStorage.length;i++){ var k=localStorage.key(i); if(/cloud-?ide-?token/i.test(k)){ t=localStorage.getItem(k); break; } } }
            var ck = document.cookie || '';
            var sm = ck.match(/X-Cloudide-Session=([^;]+)/);
            var ss = sm ? decodeURIComponent(sm[1]) : '';
            // 即使什么都没读到也要回调：session 是 HttpOnly，只能靠原生 CookieManager 取，
            // Kotlin 侧会在 session 为空时自己去 CookieStore 里找。
            window.SignHubLogin.onToken(JSON.stringify({provider:'trae',token:t||'',session:ss}));
          } catch(e){
            try { window.SignHubLogin.onToken(JSON.stringify({provider:'trae',token:'',session:''})); } catch(e2){}
          }
        })();
    """.trimIndent()

    /** 回主线程执行 UI 动作；Activity 已销毁就直接丢弃，不再碰视图。 */
    private fun ui(block: () -> Unit) {
        if (destroyed || isFinishing) return
        runOnUiThread {
            if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
            try { block() } catch (e: Exception) {
                android.util.Log.w("LoginWebActivity", "UI 回调失败（页面可能已关闭）", e)
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        try { pollThread?.interrupt() } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
