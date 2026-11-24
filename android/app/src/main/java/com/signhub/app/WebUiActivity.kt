package com.signhub.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 主界面：全屏 WebView 加载打包进 assets 的网页版（与 PWA 同一套 HTML/CSS/JS）。
 *
 * 两个桥：
 *   1. WebViewAssetLoader：assets 根映射到 https://appassets.androidplatform.net
 *   2. fetch shim：把 /api 请求转给 LocalApi（Kotlin 本地实现），网页 JS 零改动
 *
 * 注意：核心 shim 必须用 addDocumentStartJavaScript 在页面脚本之前注入。
 * 早期版本用 onPageFinished + evaluateJavascript，时机太晚会漏掉首屏请求，
 * 且脚本内含中文时部分 WebView 会静默失败 —— 那会导致页面直连真实网络，
 * 报 "Failed to fetch"。
 */
class WebUiActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var api: LocalApi

    /**
     * 当前页面 URL，由 onPageStarted / onPageFinished 在 UI 线程上写入。
     *
     * 为什么不能直接用 `web.url`：JS 桥方法（`@JavascriptInterface`）跑在 WebView
     * 的 JavaBridge 后台线程，而 WebView 不是线程安全的 —— 在非 UI 线程上调
     * `getUrl()` 会返回 null。V2.26 的来源校验直接读 `web.url`，于是恒判为不可信，
     * 所有 /api 接口都回 forbidden，整个界面「连接失败」。
     * 用这个字段（UI 线程写、任意线程读，加 @Volatile）替代。
     */
    @Volatile
    private var pageUrl: String = ""

    /** 本地接口的执行线程：异步桥与兜底拦截都跑在这儿，绝不占用 UI 线程。 */
    private val apiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * LocalApi 不是线程安全的（内部共享 SharedPreferences / SQLite / 缓存），
     * 单线程池已经保证了串行，这里再加一把锁是为了兜住同步桥与兜底拦截这两条旁路。
     */
    private val apiLock = Any()

    /**
     * 「从子页面回来必须重拉数据」的待办标记（用户 m16810）。
     *
     * 原来只在各个 launcher 的回调里直接 evaluateJavascript 一句 `__signhubReload()`。
     * 这条链有两个坑，导致「添加多账号后签到页不刷新」：
     *   1) 回调触发时 Activity 还在 onResume 恢复窗口里，WebView 可能正在重建，
     *      这一句会被排进队列然后随旧页面一起丢掉；
     *   2) 多账号管理页里还可能再套一层 LoginWebActivity（登录新账号），
     *      中间任何一次页面切换都可能让「返回时那一次刷新」落空。
     * 改成「打标记 → onResume 消费 → 延时补一次」，把「一定会刷」变成结构保证，
     * 而不是指望某一句 JS 恰好被执行。
     */
    @Volatile
    private var pendingReload = false

    /** 打标记：真正的刷新在 onResume 里做，那时 WebView 一定已经可见。 */
    private fun markReload() {
        pendingReload = true
    }

    private fun flushReload() {
        if (!pendingReload) return
        pendingReload = false
        reloadWeb(0L)
        // 补一次：页面若正在重建（onPageFinished 还没到），第一句会打空
        reloadWeb(600L)
    }

    private fun reloadWeb(delay: Long) {
        val js = "window.__signhubReload && window.__signhubReload()"
        if (delay <= 0L) {
            try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
            return
        }
        try {
            web.postDelayed({
                try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
            }, delay)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // 从多账号管理 / 登录页返回时，这里一定会走到
        flushReload()
        // 用户可能在后台期间切过系统深浅色，而 Activity 未被重建时
        // onConfigurationChanged 不一定补发 —— 回前台再刷一次主题（幂等）。
        pushTheme()
    }

    /**
     * 系统深浅色切换（「跟随系统」主题，用户 m27113 / m27260）。
     *
     * Manifest 里给 WebUiActivity 声明了 `uiMode`，所以这里会被调用而不是重建 Activity。
     * 但**只靠 WebView 自己的 `prefers-color-scheme` 在鸿蒙上不可靠**：Activity 自己
     * 吃掉 config change 之后，WebView 内部那份 Configuration 不会跟着更新，
     * matchMedia 会一直返回旧值（用户实测「切深色不变星夜、关深色不变晨雾」，
     * 两种方向都不变 —— 正是「值恒定」而不是「事件没来」）。
     *
     * 所以真正的判据走 [Bridge.sysDark]（读原生 `uiMode`），网页侧优先用它。
     * 这里只负责「通知网页重算」，判据在网页那边。
     *
     * 不调 reloadWeb：重载会白屏 + 丢滚动位置，切个深浅色不值得。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        pushTheme()
    }

    /** 让网页按「当前系统深浅色」重算一次主题；用户手选的星夜不会被系统改掉。 */
    private fun pushTheme() {
        try {
            web.evaluateJavascript(
                "(function(){try{var s=window.__signhub;" +
                    "if(s&&s.applyTheme)s.applyTheme(s.currentThemeId());}catch(e){}})();",
                null
            )
        } catch (_: Exception) {
        }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒了也能用，只是收不到签到通知 */ }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 登录页返回后刷新，让本地引擎读到新凭据
        markReload()
        reloadWeb(0L)
    }

    /**
     * 多账号管理页（WorkBuddy / Trae / Qoder / MiniMax 共用）。
     *
     * 必须用 launcher 而不是裸 startActivity：那边签完会写 HistoryStore，
     * 返回首页时要立刻重拉 /api/status，否则卡片还停在「今日尚未执行」，
     * 得等 25 秒轮询才更新（用户 m09082 报的就是这个现象）。
     */
    private val accountsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        markReload()
        reloadWeb(0L)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = LocalApi(this)

        // Android 13+ 必须先拿通知权限，否则后台签到成功也不会有通知
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Edge-to-edge 沉浸式：内容延伸进状态栏/导航栏，网页用 env(safe-area-inset-*) 自适配。
        // 系统栏底色透明；深浅色图标随网页主题（网页切主题时通过 JS 桥同步）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        // 系统深色模式决定图标颜色；网页主题加载后再由 JS 桥覆盖
        val sysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        applySystemBarAppearance(light = !sysDark)

        // 确保自动签到已排上：用 ensureScheduled（KEEP 策略），
        // 不会把「今天还没跑」的那次顶成明天。
        try {
            AutoCheckinScheduler.ensureScheduled(this)
        } catch (_: Exception) {
        }

        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setContentView(
            web,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            // assets 根布局：/static/app.js -> assets/static/app.js
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            // P1-2（BUGS-v2.25）：关掉 content:// 访问。默认是 true，页面里的
            // <a href="content://..."> 能把外部 provider 的数据拉进这个 WebView，
            // 而本页装了 JS 桥 —— 等于给陌生内容开了本机 API 的入口。
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // 必须尊重 index.html 的 meta viewport，否则按 980px 桌面排版
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        /*
         * ★ 必须**关掉** algorithmic darkening（用户 m27452）。
         *
         * V2.56 我错开了 true，理由是「让 WebView 算出真实的 prefers-color-scheme」——
         * 那是把两件事混成一件：algorithmic darkening 的作用是「页面没声明支持深色时，
         * WebView 帮你把浅色页面自动反色」，它并不会让 matchMedia 更准。
         *
         * 打开的后果（截图 m27449 实测）：用户在设置页选「晨雾」（浅色），
         * 界面仍然是深色 —— 因为 app.css 当时没有任何 `color-scheme` 声明，
         * WebView 认定「这页只支持浅色」，于是系统处于深色时把整页强行暗化。
         * 用户看到的现象就成了「跟随系统不管用 / 选了浅色也不变」。
         *
         * 我们的配色完全由 `html[data-theme]` 自己管（含 app.css 里显式的
         * `color-scheme` 声明），不需要也不允许 WebView 代劳。
         */
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
            }
        } catch (_: Exception) {
        }
        web.isLongClickable = false
        web.setOnLongClickListener { true }

        // 桥必须在 loadUrl 之前装好
        web.addJavascriptInterface(Bridge(), "SignHubApi")

        // 核心：文档开始前注入 fetch shim（纯 ASCII，避免编码问题）
        val coreShim = coreShimJs()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                web, coreShim, setOf("https://appassets.androidplatform.net")
            )
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val path = url.path ?: return loader.shouldInterceptRequest(url)
                // 兜底：万一 shim 没跑起来（老 WebView 不支持 documentStart，
                // 且 onPageStarted 注入也失败），接口仍由本地处理。
                //
                // 但这条路径有两个硬限制，必须区别对待：
                //   1) 拿不到 POST body，只能当 GET 用；
                //   2) 它在 WebView 的 IO 线程上同步返回，而 LocalApi 内部是
                //      runBlocking + 真实网络请求 —— 签到一家 1~3 秒，一键四家 10 秒以上，
                //      在这条路径上同步跑完，WebView 渲染进程早就被判无响应了。
                // 所以：只读接口照旧同步处理（快、必要），写接口直接回一条明确的错误，
                // 让前端提示用户刷新，而不是把整个 WebView 卡死。
                if (path.startsWith("/api/")) {
                    // M5（BUGS-v2.61）：这条兜底路径原来只判 method 与 isFastPath，
                    // 不判「请求是不是主文档发起的」、也不看 Origin。于是页面里任何
                    // iframe（或已被导航走的第三方页）都能靠 `<img src="/api/status">`
                    // 这类跨源子资源请求把本机 API 的数据读走 —— 响应体虽然是 JSON，
                    // 但接口本身会执行真实副作用（/api/wb_assets 会打上游）。
                    // 现在只放行主文档（isForMainFrame）发起的请求。
                    if (request.isForMainFrame != true) {
                        return jsonResponse("""{"error":"forbidden"}""")
                    }
                    val method = (request.method ?: "GET").uppercase()
                    if (method != "GET" && method != "HEAD") {
                        return jsonResponse(
                            """{"error":"shim 未加载：写操作（${method} ${path}）需要页面脚本桥，请下拉刷新页面重试"}"""
                        )
                    }
                    if (!isFastPath(path)) {
                        return jsonResponse(
                            """{"error":"shim 未加载：$path 需要页面脚本桥，请下拉刷新页面后重试"}"""
                        )
                    }
                    // P1-3（BUGS-v1.63）：必须把 query 一起传下去。
                    // LocalApi.handle 是从 rawPath 里 substringAfter('?') 取 query 的，
                    // 只传 url.path 会让 /api/logs?limit=50&provider=qoder 的过滤全部失效、
                    // /api/wb_assets?refresh=1 不刷新、/api/growth?refresh=1 不重拉 ——
                    // 静默功能降级，最难排查。
                    val full = url.query?.takeIf { it.isNotBlank() }?.let { "$path?$it" } ?: path
                    val body = synchronized(apiLock) { api.handle(method, full, "") }
                    return jsonResponse(body)
                }
                return loader.shouldInterceptRequest(url)
            }

            override fun onPageStarted(
                view: WebView?, url: String?, favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                // 记录当前页面 URL（V2.27 修 v2.26 的 forbidden）：
                // 桥方法跑在 JavaBridge 后台线程，那里读 web.url 恒为 null，
                // 只能靠这两个回调在 UI 线程上把 URL 抄出来给 trusted() 用。
                pageUrl = url ?: ""
                // 老 WebView 不支持 documentStart 时补注入（仍早于 app.js 首次调用）
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(coreShim, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) pageUrl = url
                // UI 补丁（含中文，放这里不影响功能）
                view?.evaluateJavascript(uiPatchJs(), null)
                // 页面重建（WebView 被回收 / 登录页整页跳转）后补一次刷新：
                // 之前 launcher 回调里那一句可能随旧页面一起丢了（用户 m16810）。
                flushReload()
            }

            /**
             * P1-2（BUGS-v2.25）：拦掉一切离开本页的导航。
             *
             * 本页装了 JS 桥（`addJavascriptInterface(Bridge(), "SignHubApi")`），
             * 而 index.html 里有 `<a href="https://github.com/Ayanguser" target="_blank">`
             * 这类外链。原来没有这个回调，点一下就在**同一个 WebView** 里加载 github.com ——
             * 之后该页的 JS 可以直接调 `SignHubApi.request("GET","/api/config","")`
             * 读配置、`request("POST","/api/config", ...)` 改配置。
             * 现在外部链接一律交给系统浏览器，WebView 只承载 appassets 里的页面。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return true
                val scheme = (u.scheme ?: "").lowercase()
                val host = (u.host ?: "").lowercase()
                if (scheme == "https" && host == "appassets.androidplatform.net") return false
                return try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (_: Exception) {
                    true   // 没有可处理的应用：直接吞掉，绝不让它进本 WebView
                }
            }
        }

        web.loadUrl("https://appassets.androidplatform.net/index.html")
    }

    /**
     * 兜底拦截路径专用：把字符串包成 WebResourceResponse。
     * 状态码恒 200 是 WebResourceResponse 的限制（它只能给一个 HTTP 状态，
     * 而我们的错误信息在 JSON body 里），前端统一看 body.error 判断。
     */
    private fun jsonResponse(text: String): WebResourceResponse =
        WebResourceResponse("application/json", "utf-8", text.byteInputStream())

    /**
     * 兜底拦截路径（无 body、在 WebView 的 IO 线程上同步执行）**只放行毫秒级接口**。
     *
     * 原来是黑名单（`isWritePath` 列出会打上游的路径），漏了 `/api/test_notify`
     * （真的推 webhook）、`/api/growth/room`、`/api/growth/claim`、
     * `/api/growth/daily/run`、`/api/growth/claim` —— 它们同样会打上游/写库
     * （BUGS-v1.63 P2-5）。而且黑名单天生追不上新增接口。
     *
     * 更关键的是 P1-4：`/api/status` 本身不写库，但它会做 10+ 次 SQLite 查询
     * （recordsOf + recordsOfDays 7 天 + statsAll + recordsOfProvider × 4 + streak），
     * 在 WebView IO 线程上同步跑完，渲染进程可能被判无响应。
     *
     * 所以改成白名单：只有这几个接口明确是「读一个内存/小表就返回」的才放行，
     * 其余一律让前端走 JS 桥（异步、后台线程、有超时）。
     */
    private fun isFastPath(path: String): Boolean {
        val p = path.substringBefore('?')
        // N4（BUGS-v2.1）：/api/stats 与 /api/auto_status 已从白名单移除 ——
        //   · /api/stats 会 store.recentLogs(2000) 把 2000 行拉进内存逐条聚合，
        //     再 statsAll() 全表扫一次，根本不是「读一个小表」；
        //   · /api/auto_status 里 wm.getWorkInfosForUniqueWork(...).get() 是无超时
        //     阻塞 Future，WorkManager 的 binder 调用在极端情况（DB 锁）会卡住。
        // 这两条放行等于把 P1-4 想避免的同步重活又搬回 WebView IO 线程。
        return p == "/api/ping" ||
            p == "/api/config" ||
            p == "/api/logs" ||
            p == "/api/notify_logs" ||
            p == "/api/notify_history"
    }

    /**
     * 核心 shim：只做 fetch 劫持，不含任何中文/特殊字符。
     * 由 addDocumentStartJavaScript 在页面自身脚本之前执行。
     */
    private fun coreShimJs(): String = """
        (function(){
          if (window.__signhubShim) { return; }
          window.__signhubShim = true;
          // 异步桥的回调登记表：Kotlin 侧处理完调 __signhubResolve(id, text) 兑现 Promise
          window.__signhubPending = {};
          window.__signhubSeq = 0;
          window.__signhubResolve = function(id, text){
            var f = window.__signhubPending && window.__signhubPending[id];
            if (!f) { return; }
            delete window.__signhubPending[id];
            try { f(text); } catch(e) {}
          };
          // 以前这里一律 new Response(text, {status: 200})，导致 app.js 的
          // `if (!r.ok) throw` 永不触发 —— 本地模式下所有 HTTP 语义错误被当成成功。
          // 现在从 body 里还原状态：优先显式 __http_status，其次按 error 字段推断。
          window.__signhubResp = function(text){
            var st = 200;
            try {
              var o = JSON.parse(text);
              if (o && typeof o === 'object') {
                if (typeof o.__http_status === 'number') {
                  st = o.__http_status;
                } else if (o.error) {
                  st = (o.error === 'not found') ? 404 : 400;
                }
              }
            } catch(e) {}
            return new Response(text, {
              status: st,
              headers: {'Content-Type': 'application/json'}
            });
          };
          var origFetch = window.fetch ? window.fetch.bind(window) : null;
          window.fetch = function(input, init){
            var url = (typeof input === 'string') ? input : ((input && input.url) || '');
            var abs = null;
            try { abs = new URL(url, location.href); } catch(e) { abs = null; }
            if (abs && abs.pathname.indexOf('/api/') === 0) {
              var method = (init && init.method) || 'GET';
              var body = '';
              if (init && init.body) {
                if (typeof init.body === 'string') {
                  body = init.body;
                } else {
                  // P3-7（BUGS-v1.63）：原来只认字符串 body，FormData / Blob / URLSearchParams
                  // 会被**静默丢掉**，后端收到空 body 却返回成功（保存配置时表现为「填了没保存」）。
                  // 明确报错比静默丢数据好。
                  var kind = (init.body && init.body.constructor && init.body.constructor.name) || typeof init.body;
                  return Promise.resolve(window.__signhubResp(JSON.stringify({
                    error: 'body 只支持字符串（收到 ' + kind + '）；请用 JSON.stringify(...) 传，或改走 SignHubApi.requestAsync',
                    __http_status: 400
                  })));
                }
              }
              var path = abs.pathname + (abs.search || '');
              // 优先走异步桥：Kotlin 在后台线程执行（含网络请求），不阻塞 WebView 的 JS 线程，
              // 避免签到/体检这类慢请求把界面卡住（原同步桥是 ANR 隐患）。
              if (window.SignHubApi && window.SignHubApi.requestAsync) {
                return new Promise(function(resolve){
                  var id = 'r' + (++window.__signhubSeq);
                  var settled = false;
                  function done(text){
                    if (settled) { return; }
                    settled = true;
                    resolve(window.__signhubResp(text));
                  }
                  window.__signhubPending[id] = done;
                  // 兜底：桥没回话也不能让 Promise 永远挂着。
                  // P2-7（BUGS-v1.63）：必须比 LocalApi.parallel 的 TASK_TIMEOUT_SEC（60s）大，
                  // 否则 shim 先给前端一个 timeout 错误、后端还在跑到 60 秒；用户看到失败就重试，
                  // 此时 CheckinGate 的 8 秒 TTL 早已过期 → 同一账号被签两次。
                  setTimeout(function(){
                    if (settled) { return; }
                    delete window.__signhubPending[id];
                    done(JSON.stringify({error: 'timeout: 本地接口 75 秒未返回'}));
                  }, 75000);
                  try {
                    window.SignHubApi.requestAsync(id, method, path, body);
                  } catch (e) {
                    delete window.__signhubPending[id];
                    done(JSON.stringify({error: String(e)}));
                  }
                });
              }
              // 老路径：只有同步桥时退回（旧缓存页面）
              var text = '';
              try {
                text = window.SignHubApi.request(method, path, body);
              } catch (e) {
                text = JSON.stringify({error: String(e)});
              }
              return Promise.resolve(window.__signhubResp(text));
            }
            if (origFetch) { return origFetch(input, init); }
            return Promise.reject(new Error('no native fetch'));
          };
        })();
    """.trimIndent()

    /**
     * UI 补丁：隐藏本地模式用不上的区块、修正输入框提示、把注入按钮接到原生。
     * 在 onPageFinished 注入即可（纯界面，晚一点没关系）。
     */
    private fun uiPatchJs(): String = """
        (function(){
          window.__signhubReload = function(){
            try { if (window.__signhub && window.__signhub.loadAll) window.__signhub.loadAll(); } catch(e) {}
            patch();
          };
          function hidePanels(){
            try {
              Array.prototype.forEach.call(document.querySelectorAll('.panel'), function(p){
                var h = p.querySelector('h2');
                var t = h ? (h.textContent || '').trim() : '';
                if (t === '连接电脑端' || t === '访问口令') { p.style.display = 'none'; }
              });
            } catch(e) {}
          }
          function fixHints(){
            try {
              var map = {
                '.f-wbtoken': '已内置，无需填写；换账号时才粘贴新 accessToken',
                '.f-wbuid': '已内置（账号 uid）',
                '.f-wbdomain': '已内置：www.workbuddy.cn',
                '.f-wbent': '留空即可',
                '.f-traedevice': '留空自动生成 16 位设备号',
                '.f-traetoken': '点上面「登录 Trae 账号」会自动抓取 X-Cloudide-Session（约 14 天，能自动续期）；也可手动粘贴 session 或 Cloud-IDE-Token',
                '.f-qd-token': 'dt- 开头的 accessToken，可留空（30 天过期）',
                '.f-qd-refresh': 'drt- 开头的 refreshToken，推荐只填这个：有效期一年，token 过期自动续',
                '.f-qd-uid': '账号 uid（01a1… 开头），选填',
                '.f-qd-machine': '设备指纹 UUID，选填（签到不校验）',
                '.f-mm-token': 'mmoat_ 开头的 accessToken；点「网页登录 MiniMax」可自动填',
                '.f-mm-uid': 'realUserID（18 位数字，选填）',
                '.f-mm-device': 'deviceID（18 位数字），选填'
              };
              Object.keys(map).forEach(function(sel){
                Array.prototype.forEach.call(document.querySelectorAll(sel), function(el){
                  el.setAttribute('placeholder', map[sel]);
                });
              });
            } catch(e) {}
          }
          function patch(){ hidePanels(); fixHints(); fixAutoText(); injectNativeButtons(); injectWbLogin(); injectMultiLogin(); hideUnsupportedModes(); }

          // 手机本地引擎只支持直连；接口/浏览器模式在手机上不可用 → 用「全局样式」永久隐藏，
          // 这样即使卡片被定时刷新重建，模式按钮也不会再冒出来。
          // 用户 m25043：手机端读不到电脑客户端的登录态（LocalApi 对 /api/local_creds 恒返
          // 「手机上读不到电脑客户端凭据」），那两个「读取本机凭据」按钮留着只会误导，
          // 一并藏掉；凭据一律走上面的「登录主账号 / 多账号管理」在 App 内网页登录。
          function hideUnsupportedModes(){
            try {
              if (document.getElementById('shModeStyle')) return;
              var s = document.createElement('style'); s.id = 'shModeStyle';
              s.textContent = '[data-seg] button[data-mode="http"]{display:none!important}'
                + '[data-seg] button[data-mode="browser"]{display:none!important}'
                + '.pv-http,.pv-browser{display:none!important}'
                + '.f-qd-read,.f-mm-read{display:none!important}';
              (document.head || document.documentElement).appendChild(s);
            } catch(e) {}
          }

          // 登录区已统一由网页侧渲染（app.js 的 loginBlockHTML，四张卡结构一致：
          // 绿=登录主账号 / 蓝=多账号管理，样式走 app.css 的 .lg-*）。
          // 这两个函数只保留给「旧缓存页面」兜底：新页面里 .lg-block 已存在，
          // 这里判断到就直接返回，绝不会再插出第二组按钮。
          function injectWbLogin(){
            try {
              var card = document.querySelector('.prov-card[data-key="workbuddy"]');
              if (!card) return;
              if (card.querySelector('.lg-block')) return;
              var body = card.querySelector('.pv-body-inner') || card.querySelector('.prov-body') || card;

              // ① 登录主账号（单账号，写 Settings.wbToken）
              if (!card.querySelector('.sh-wb-main')) {
                var m = document.createElement('button');
                m.className = 'sh-wb-main';
                m.textContent = '登录主账号';
                m.style.cssText = 'width:100%;margin:0 0 8px;padding:12px;border-radius:12px;border:1px solid rgba(55,214,122,.55);background:rgba(55,214,122,.18);color:#e6fff2;font-size:13.5px;font-weight:700';
                body.insertBefore(m, body.firstChild);
              }

              // ② 多账号管理（OAuth 多账号列表）
              if (!card.querySelector('.sh-wb-login')) {
                var b = document.createElement('button');
                b.className = 'sh-wb-login';
                b.textContent = '多账号管理';
                b.style.cssText = 'width:100%;margin:0 0 10px;padding:11px;border-radius:12px;border:1px solid rgba(108,140,255,.62);background:rgba(108,140,255,.22);color:#eef2ff;font-size:13.5px;font-weight:700';
                var mainBtn = card.querySelector('.sh-wb-main');
                if (mainBtn && mainBtn.nextSibling) body.insertBefore(b, mainBtn.nextSibling);
                else body.insertBefore(b, body.firstChild);
              }
            } catch(e) {}
          }

          // 同上：新页面已由 .lg-block 统一渲染，这里只兜底旧缓存。
          function injectMultiLogin(){
            try {
              var keys = ['traesolo', 'qoder', 'minimax', 'huawei'];
              keys.forEach(function(key){
                var card = document.querySelector('.prov-card[data-key="'+key+'"]');
                if (!card) return;
                if (card.querySelector('.lg-block')) return;
                if (card.querySelector('.sh-multi-login')) return;
                var body = card.querySelector('.pv-body-inner') || card.querySelector('.prov-body') || card;
                var b = document.createElement('button');
                b.className = 'sh-multi-login';
                b.setAttribute('data-provider', key);
                b.textContent = '多账号管理';
                b.style.cssText = 'width:100%;margin:0 0 10px;padding:11px;border-radius:12px;border:1px solid rgba(108,140,255,.62);background:rgba(108,140,255,.22);color:#eef2ff;font-size:13.5px;font-weight:700';
                body.insertBefore(b, body.firstChild);
              });
            } catch(e) {}
          }

          // 持续守卫：卡片被定时刷新重建后，重新补注入按钮/说明/隐藏模式（MutationObserver 驱动）
          if (!window.__signhubObserver && window.MutationObserver) {
            window.__signhubObserver = new MutationObserver(function(){
              try { injectWbLogin(); injectMultiLogin(); hideUnsupportedModes(); hidePanels(); injectNativeButtons(); fixHints(); fixAutoText(); } catch(e) {}
            });
            try {
              window.__signhubObserver.observe(document.body, { childList: true, subtree: true });
            } catch(e) {}
          }

          // 往「高级」面板里塞两个原生按钮（后台签到相关）
          function injectNativeButtons(){
            try {
              if (document.getElementById('shNativeBox')) return;
              var panels = document.querySelectorAll('.panel');
              var target = null;
              Array.prototype.forEach.call(panels, function(p){
                var h = p.querySelector('h2');
                if (h && (h.textContent || '').trim() === '高级') target = p;
              });
              if (!target) return;
              var box = document.createElement('div');
              box.id = 'shNativeBox';
              box.style.cssText = 'display:flex;flex-direction:column;gap:8px;margin:10px 0 0';
              var mk = function(label, fn){
                var b = document.createElement('button');
                b.textContent = label;
                b.style.cssText = 'width:100%;padding:12px;border-radius:12px;border:1px solid rgba(108,140,255,.35);background:rgba(108,140,255,.12);color:#dfe6ff;font-size:14px;font-weight:600';
                b.addEventListener('click', function(ev){
                  ev.preventDefault(); ev.stopPropagation();
                  try { fn(); } catch(e) {}
                });
                return b;
              };
              box.appendChild(mk('立即测试后台签到', function(){ window.SignHubApi.runAutoNow(); }));
              box.appendChild(mk('关闭电池优化（保后台）', function(){ window.SignHubApi.openBatterySettings(); }));
              target.appendChild(box);
            } catch(e) {}
          }

          // 自动签到面板的文案是电脑版口径，手机上不适用
          function fixAutoText(){
            try {
              Array.prototype.forEach.call(document.querySelectorAll('.muted.small'), function(el){
                var t = (el.textContent || '');
                if (t.indexOf('需电脑端常开') >= 0 || t.indexOf('电脑端常开') >= 0) {
                  el.textContent = '每天到点由系统后台自动签到（WorkBuddy / Trae）。手机关机或没网时会顺延。';
                }
              });
            } catch(e) {}
          }
          patch();
          setTimeout(patch, 800);
          setTimeout(patch, 2000);

          if (!window.__signhubClickBound) {
            window.__signhubClickBound = true;
            document.addEventListener('click', function(ev){
              var t = ev.target;
              if (!t || !t.closest) { return; }

                        // Trae 的「登录 Trae 账号」：打开 App 内登录页，登录后自动读 Cloud-IDE-Token
              var tl = t.closest('.f-trae-login');
              if (tl) {
                ev.preventDefault(); ev.stopPropagation();
                try { window.SignHubApi.openLogin('trae'); } catch(e) {}
                return false;
              }
              // WorkBuddy「登录主账号」按钮：打开 codebuddy.cn 网页登录，抓 accessToken 写单账号设置
              var wm = t.closest('.sh-wb-main');
              if (wm) {
                ev.preventDefault(); ev.stopPropagation();
                try { window.SignHubApi.openLogin('workbuddy'); } catch(e) {}
                return false;
              }
              // MiniMax「网页登录」按钮：打开 agent.minimax.cn，登录后读 localStorage['_token']
              var mm = t.closest('.f-mm-login');
              if (mm) {
                ev.preventDefault(); ev.stopPropagation();
                try { window.SignHubApi.openLogin('minimax'); } catch(e) {}
                return false;
              }
              // Qoder「网页登录」按钮：走官方 device flow（打开授权页 + 轮询 deviceToken/poll）
              var ql = t.closest('.f-qd-login');
              if (ql) {
                ev.preventDefault(); ev.stopPropagation();
                try { window.SignHubApi.openLogin('qoder'); } catch(e) {}
                return false;
              }
              // WorkBuddy「多账号管理」按钮
              var wl = t.closest('.sh-wb-login');
              if (wl) {
                ev.preventDefault(); ev.stopPropagation();
                try { window.SignHubApi.openAccounts(); } catch(e) {}
                return false;
              }
              // Trae / Qoder / MiniMax「多账号管理」按钮
              var ml = t.closest('.sh-multi-login');
              if (ml) {
                ev.preventDefault(); ev.stopPropagation();
                try { window.SignHubApi.openMultiAccounts(ml.getAttribute('data-provider') || 'traesolo'); } catch(e) {}
                return false;
              }

              // 「一键全部签到」只跑四家 provider，不再拉起任何外部 App。
            }, true);
          }
        })();
    """.trimIndent()

    /** JS 桥：网页 fetch 被改写到这儿。同步接口保留给旧缓存页面，新页面走 requestAsync。 */
    inner class Bridge {
        /**
         * P1-2（BUGS-v2.25）：桥只能被本机资源页调用。
         *
         * `addJavascriptInterface` 对整个 WebView 生效 —— 只要这个 WebView 里加载过
         * 任何外部页面（外链、重定向、被注入的 iframe），那页的 JS 就拥有与本 App
         * 完全相同的 API 权限。原来没有任何来源校验（对比 LoginWebActivity 的三处
         * `if (!isTrustedOrigin()) return`），现在补齐：只认 appassets 这个域。
         *
         * ⚠️ V2.27 修正（用户 m18499「这一版完全用不了」）：这里**不能读 `web.url`**。
         * 桥方法跑在 JavaBridge 后台线程，WebView 非线程安全，`getUrl()` 在非 UI 线程
         * 返回 null → 恒判不可信 → 所有 /api 接口都回 forbidden，
         * 界面顶部直接「连接失败：forbidden」。改用 onPageStarted/onPageFinished
         * 在 UI 线程抄下来的 [pageUrl]。
         */
        private fun trusted(): Boolean {
            val u = pageUrl
            if (u.isEmpty()) {
                // M5（BUGS-v2.61）：原来这里 return true（「宁可少拦一次」），
                // 但 pageUrl 为空恰恰是「页面还没起来」或「WebView 已被导航走、
                // 回调没来得及更新」这两个最危险的时刻 —— 此时任何能执行 JS 的
                // 上下文（被劫持的第三方页、注入的 iframe）都能直接调桥读写
                // /api（含凭据与签到触发）。默认拒绝，让首屏多一次重试的代价
                // 远小于「空串即全通」。
                return false
            }
            return u.startsWith("https://appassets.androidplatform.net/") ||
                u == "https://appassets.androidplatform.net"
        }

        @JavascriptInterface
        fun request(method: String, path: String, body: String): String =
            if (!trusted()) """{"error":"forbidden"}"""
            else synchronized(apiLock) { api.handle(method, path, body) }

        /**
         * 异步桥：Kotlin 在后台线程跑 LocalApi（含网络请求），跑完把结果回灌给页面的 Promise。
         *
         * 为什么必须异步：JavascriptInterface 的方法在 WebView 的 JS 线程上同步执行，
         * 一次「一键全部签到」要串好几家的 HTTP 请求，同步跑会把 JS 线程连同界面一起冻住，
         * 主线程 watchdog 一超时就是 ANR。改成后台线程 + 回调后，页面只挂一个 Promise。
         */
        @JavascriptInterface
        fun requestAsync(id: String, method: String, path: String, body: String) {
            if (!trusted()) {
                runOnUiThread {
                    try {
                        web.evaluateJavascript(
                            "window.__signhubResolve && window.__signhubResolve(" +
                                org.json.JSONObject.quote(id) + "," +
                                org.json.JSONObject.quote("""{"error":"forbidden"}""") + ")",
                            null
                        )
                    } catch (_: Exception) {
                    }
                }
                return
            }
            apiExecutor.execute {
                val text = try {
                    synchronized(apiLock) { api.handle(method, path, body) }
                } catch (e: Exception) {
                    """{"error":${org.json.JSONObject.quote(e.message ?: "unknown")}}"""
                }
                runOnUiThread {
                    try {
                        web.evaluateJavascript(
                            "window.__signhubResolve && window.__signhubResolve(" +
                                org.json.JSONObject.quote(id) + "," +
                                org.json.JSONObject.quote(text) + ")",
                            null
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }

        /** 打开 App 内登录页获取凭据：provider = workbuddy / trae / minimax / qoder */
        @JavascriptInterface
        fun openLogin(provider: String) {
            runOnUiThread {
                try {
                    loginLauncher.launch(
                        Intent(this@WebUiActivity, LoginWebActivity::class.java)
                            .putExtra(LoginWebActivity.EXTRA_PROVIDER, provider)
                    )
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@WebUiActivity, "打开登录页失败：" + e.message, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /** 打开多账号管理（WorkBuddy） */
        @JavascriptInterface
        fun openAccounts() {
            runOnUiThread {
                try {
                    accountsLauncher.launch(Intent(this@WebUiActivity, WbAccountsActivity::class.java))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@WebUiActivity, "打开失败：" + e.message, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /** 打开多账号管理（Trae Solo / Qoder CN / MiniMax Code 通用页） */
        @JavascriptInterface
        fun openMultiAccounts(provider: String) {
            runOnUiThread {
                try {
                    accountsLauncher.launch(Intent(this@WebUiActivity, MultiAccountsActivity::class.java)
                        .putExtra(MultiAccountsActivity.EXTRA_PROVIDER, provider))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@WebUiActivity, "打开失败：" + e.message, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // H2（BUGS-v2.61）：openAccessibility 桥方法已移除 —— 它指向的
        // AutoClickService 已整体删除（占位包名 + 可读全机窗口 + 前端零调用）。
        // 这里保留一个空实现只为兼容老缓存页面里可能残留的 bridge.openAccessibility()
        // 调用，不打开任何系统设置页。
        @JavascriptInterface
        fun openAccessibility() {
            // no-op：无障碍服务已移除（V2.62）
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread {
                android.widget.Toast.makeText(this@WebUiActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        /** 网页主题切换时同步系统栏图标颜色：light=true 表示浅色背景配深色图标。 */
        @JavascriptInterface
        fun setSystemBars(light: Boolean) {
            runOnUiThread {
                try { applySystemBarAppearance(light) } catch (_: Exception) {}
            }
        }

        /** 立刻跑一次后台签到任务（和定时走同一条路径，用于验证）。 */
        @JavascriptInterface
        fun runAutoNow() {
            runOnUiThread {
                try {
                    AutoCheckinScheduler.runNow(this@WebUiActivity)
                    android.widget.Toast.makeText(
                        this@WebUiActivity, "已提交后台签到，完成后会有通知",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@WebUiActivity, "提交失败：" + e.message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /** 引导用户关闭电池优化，否则国产 ROM 可能不跑后台任务。 */
        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                try {
                    val pm = getSystemService(android.os.PowerManager::class.java)
                    val already = pm?.isIgnoringBatteryOptimizations(packageName) == true
                    if (!already) {
                        @SuppressLint("BatteryLife")
                        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                        startActivity(i)
                    } else {
                        android.widget.Toast.makeText(
                            this@WebUiActivity, "已经在白名单里了，后台不会被限制",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
        }

        /**
         * 引导用户授予「精确闹钟」权限（V2.51）。
         *
         * Android 14+ 全新安装默认不授予 SCHEDULE_EXACT_ALARM，没有它
         * `setExactAndAllowWhileIdle` 会抛 SecurityException、降级成 `setAndAllowWhileIdle`，
         * 息屏后可能被推迟几小时 —— 这是「设了 8:30 却中午才签」的最常见原因。
         *
         * 注意：我们同时声明了 USE_EXACT_ALARM（安装即授予、不可撤销），
         * 所以大多数设备进这里时已经是 true，只有个别 ROM 或用户手动关掉才需要引导。
         */
        @JavascriptInterface
        fun openExactAlarmSettings() {
            runOnUiThread {
                try {
                    if (android.os.Build.VERSION.SDK_INT < 31) {
                        android.widget.Toast.makeText(
                            this@WebUiActivity, "当前系统不需要这项授权", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@runOnUiThread
                    }
                    if (AutoCheckinScheduler.canExact(this@WebUiActivity)) {
                        android.widget.Toast.makeText(
                            this@WebUiActivity, "已允许精确定时，息屏也能准点签到",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@runOnUiThread
                    }
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    // 个别 ROM 没有这个设置页，退到应用详情页（用户可自行找「闹钟和提醒」）
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(
                            this@WebUiActivity, "打开设置失败：" + e.message,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        /** 打开本应用的自启动/后台管理页（国产 ROM 各家路径不同，能开哪个开哪个）。 */
        @JavascriptInterface
        fun openAutoStartSettings() {
            runOnUiThread {
                val candidates = listOf(
                    // MIUI / HyperOS
                    android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    ),
                    // ColorOS / realme UI
                    android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    ),
                    android.content.ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                    ),
                    // vivo
                    android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    ),
                    // 华为 / 荣耀
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    ),
                    android.content.ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    ),
                )
                for (cn in candidates) {
                    try {
                        startActivity(Intent().setComponent(cn)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@runOnUiThread
                    } catch (_: Exception) {
                        // 这家 ROM 没有这个页面，试下一个
                    }
                }
                // 全都不认：退到应用详情页，用户可以在「电池 / 后台管理」里自己找
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(
                        this@WebUiActivity, "当前系统没有可打开的后台管理页",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /**
         * 系统栏实际占位（dp），给网页兜底 `env(safe-area-inset-*)`。
         *
         * 为什么需要：网页靠 `env(safe-area-inset-top/bottom)` 自适应刘海与手势条，
         * 但**鸿蒙（HarmonyOS）的 WebView 对这两个变量返回 0**，而 `setDecorFitsSystemWindows(false)`
         * 已经让内容延伸进了状态栏/导航栏区域 → 顶部齿轮被系统状态栏盖住点不到、
         * 底部 tab 栏和系统手势小横条重叠（用户 m26465）。
         *
         * 这里从 `WindowInsets` 直接读真实值，网页用 `max(env(...), var(--inset-*))` 取较大者，
         * 两种来源哪个有值就用哪个 —— 不需要判断 ROM。
         */
        @JavascriptInterface
        fun insets(): String {
            return try {
                val d = resources.displayMetrics.density
                var top = 0
                var bottom = 0
                var cutout = 0
                val v = window?.decorView
                val wi = if (v != null) androidx.core.view.ViewCompat.getRootWindowInsets(v) else null
                if (wi != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val bars = wi.getInsets(
                            android.view.WindowInsets.Type.systemBars()
                                or android.view.WindowInsets.Type.displayCutout()
                        )
                        top = bars.top
                        bottom = bars.bottom
                        cutout = wi.getInsets(android.view.WindowInsets.Type.displayCutout()).top
                    } else {
                        @Suppress("DEPRECATION")
                        top = wi.systemWindowInsetTop
                        @Suppress("DEPRECATION")
                        bottom = wi.systemWindowInsetBottom
                        @Suppress("DEPRECATION")
                        cutout = wi.displayCutout?.safeInsetTop ?: 0
                    }
                }
                // 降级：某些 ROM（含部分鸿蒙版本）在 Activity 刚创建时
                // getRootWindowInsets 返回 null 或全 0，用系统资源里的栏高兜底。
                if (top <= 0) top = statusBarHeightPx()
                if (bottom <= 0) bottom = navBarHeightPx()
                org.json.JSONObject().apply {
                    put("top", Math.round(top / d))
                    put("bottom", Math.round(bottom / d))
                    put("cutout", Math.round(cutout / d))
                    put("sdk", android.os.Build.VERSION.SDK_INT)
                }.toString()
            } catch (e: Exception) {
                """{"top":0,"bottom":0,"cutout":0,"sdk":0,"error":"${e.message}"}"""
            }
        }

        /** 状态栏高度（px）：`WindowInsets` 拿不到时的兜底。 */
        private fun statusBarHeightPx(): Int {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) {
                val h = resources.getDimensionPixelSize(id)
                if (h > 0) return h
            }
            return Math.round(24 * resources.displayMetrics.density)
        }

        /**
         * 导航栏 / 手势条高度（px）：`WindowInsets` 拿不到时的兜底。
         *
         * 注意：全面屏手势条的 dimen 名在不同 ROM 上不一样，逐个试；
         * 全都不认时按「手势导航」给 24dp —— 宁可多留一点也不能让 tab 栏被压住。
         */
        private fun navBarHeightPx(): Int {
            val names = listOf(
                "navigation_bar_height",
                "navigation_bar_height_landscape",
                "navigation_bar_gesture_height",
            )
            for (n in names) {
                val id = resources.getIdentifier(n, "dimen", "android")
                if (id > 0) {
                    val h = resources.getDimensionPixelSize(id)
                    if (h > 0) return h
                }
            }
            return Math.round(24 * resources.displayMetrics.density)
        }

        /**
         * 系统当前是不是深色模式（「跟随系统」主题的唯一可信判据）。
         *
         * 为什么不直接用网页的 `matchMedia('(prefers-color-scheme: dark)')`：
         * 我们在 Manifest 里声明了 `uiMode`，Activity 自己吃掉 config change 之后，
         * WebView 内部那份 Configuration **不会跟着更新**，matchMedia 会一直返回旧值。
         * 用户 m27260 实测「手机切深色没变星夜、关掉深色没变晨雾」—— 两个方向都不变，
         * 正是「值恒定」的特征，而不是「change 事件没来」。
         *
         * 这里读的是原生 `resources.configuration.uiMode`，Activity 已收到新的
         * Configuration，所以一定是最新值。网页侧拿到 "1"/"0" 后优先用它，
         * 桥不可用（电脑端浏览器）才回落 matchMedia。
         */
        @JavascriptInterface
        fun sysDark(): String {
            return try {
                val night = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (night) "1" else "0"
            } catch (e: Exception) {
                ""
            }
        }

        /** 后台可靠性现状，给设置页面板渲染用。 */
        @JavascriptInterface
        fun bgStatus(): String {
            return try {
                val pm = getSystemService(android.os.PowerManager::class.java)
                val batteryOk = pm?.isIgnoringBatteryOptimizations(packageName) == true
                org.json.JSONObject().apply {
                    put("sdk", android.os.Build.VERSION.SDK_INT)
                    put("exact_ok", AutoCheckinScheduler.canExact(this@WebUiActivity))
                    put("battery_ok", batteryOk)
                    put("next_pick", AutoCheckinScheduler.nextPickAt(this@WebUiActivity))
                }.toString()
            } catch (e: Exception) {
                """{"sdk":0,"exact_ok":false,"battery_ok":false,"next_pick":0,"error":"${e.message}"}"""
            }
        }

        /**
         * 重置某家的登录态（用户 m21385）。
         *
         * 只清凭据是不够的：WebView 的 CookieManager 是进程级全局，网站还有
         * 「记住登录」的 localStorage —— 下次点「登录主账号」会直接进上一个号，
         * 用户看起来像「重置没生效」。这里把该 provider 的域名 cookie 全部过期掉。
         *
         * 不动别的 provider 的域：一次重置只影响一家。
         */
        @JavascriptInterface
        fun resetCreds(provider: String) {
            val domains = when (provider) {
                "workbuddy" -> listOf("codebuddy.cn", "workbuddy.cn", "workbuddy.ai",
                    "copilot.tencent.com", "download.codebuddy.ai")
                "traesolo" -> listOf("trae.cn", "trae.ai", "api.trae.cn", "api.trae.ai",
                    "work.trae.cn", "www.trae.cn", "www.trae.ai")
                "qoder" -> listOf("qoder.cn", "qoder.com", "qoder.sh", "openapi.qoder.com.cn",
                    "openapi.qoder.sh", "account.aliyun.com")
                "minimax" -> listOf("minimax.cn", "minimaxi.com", "minimax.io", "agent.minimax.cn",
                    "account.minimax.cn", "agent.minimax.io", "account.minimax.io")
                "huawei" -> listOf("huaweicloud.com", "devcloud.cn-north-4.huaweicloud.com",
                    "codearts.huaweicloud.com")
                else -> emptyList()
            }
            val cm = android.webkit.CookieManager.getInstance()
            var n = 0
            for (d in domains) {
                for (host in listOf(d, "." + d, "www." + d)) {
                    val url = "https://$host/"
                    // 先读出该域现有的 cookie 名，逐个置为过期（Max-Age=0）。
                    // 直接 setCookie("signhub_reset=1; Max-Age=0") 只会删掉这个不存在的名字，
                    // 真正的会话 cookie 一个都不会动。
                    val cur = try { cm.getCookie(url) } catch (_: Exception) { null }
                    if (cur.isNullOrBlank()) continue
                    for (pair in cur.split(";")) {
                        val name = pair.substringBefore("=").trim()
                        if (name.isBlank()) continue
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$d")
                        n++
                    }
                }
            }
            cm.flush()
            // MiniMax / Qoder 的登录态不只在 cookie 里：Web 端的 token 存在
            // localStorage（MiniMax 是 `_token`、Qoder 是 auth 记录）。CookieManager
            // 没有「按 origin 清 localStorage」的 API，只能整体清。
            // 代价可接受：凭据本身存在 DataStore 里，网页侧只是「记住登录」，
            // 清掉之后下次点登录会要求重新输一次，这正是「重置」想要的。
            if (provider == "minimax" || provider == "qoder") {
                try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@WebUiActivity,
                    if (n > 0) "已清除该站的网页登录状态" else "该站本来就没有登录状态",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** 系统栏图标深浅：默认跟系统深色模式；网页主题切换后由 JS 桥覆盖。 */
    private var systemBarsLight = false

    private fun applySystemBarAppearance(light: Boolean = systemBarsLight) {
        systemBarsLight = light
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        } catch (_: Exception) {}
    }

    // H2（BUGS-v2.61）：无障碍兜底执行器 AutoClickService 已整体删除。
    // 理由：它的目标包名 com.workbuddy.app / com.trae.solo 是**从未验证的占位值**
    // （docs/REVIEW.md P3-14、README.md:505 都写了「按实际包名改」），而配置里
    // canRetrieveWindowContent=true + flagRetrieveInteractiveWindows 且没有
    // packageNames —— 等于声明「能读全机所有应用的窗口内容」，却一个真实目标都
    // 打不中。前端也从未调用 openAccessibility()。保留它只有风险没有功能。
    // 相关文件（AndroidManifest service 声明、res/xml/accessibility_service_config.xml、
    // strings 里的 accessibility_desc）一并移除。

    override fun onDestroy() {
        try { apiExecutor.shutdownNow() } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
