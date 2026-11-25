package com.signhub.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 本地 API 层：在 APK 内实现网页版依赖的 /api 接口，让内置网页（assets/web）无需改动即可运行。
 *
 * 调用路径：网页 app.js 的 fetch 被 JS shim 接管 → SignHubApi.request() → 这里。
 * 全部同步返回 JSON 字符串（JS 桥是同步的）。
 */
class LocalApi(private val ctx: Context) {

    // 必须是 val，不能是 get()：
    // 以前写 `private val store get() = HistoryStore(ctx)`，每次访问都 new 一个
    // SQLiteOpenHelper（status() 一次就 new 10+ 个）。虽然底层连接被 SQLite 缓存，
    // 但每 new 一个就多一个 helper 对象 + 一次 getDatabaseLocked 的开销，
    // 首页每 25 秒轮询一次，白白制造 GC 压力。
    private val store: HistoryStore by lazy { HistoryStore(ctx) }

    init {
        // 口径升级：丢掉按「活动累计发放」写下的旧积分缓存
        try { migrateTotalCache() } catch (_: Exception) {}
        // 一次性把历史设备号修成「按账号凭据确定性派生」（用户 m12150，v2.6）。
        // 只修写入路径不够：老用户存下来的账号设备号是随机生成的，升级后照样 9074，
        // 看起来像「没修」。这里在首次构造时逐个检查：已有合法号一律保留（用户手填的
        // 真实机器号最可靠），只有空/非法的才按该账号凭据重算。
        try { TraeAccountStore.migrateDevices(ctx) } catch (_: Exception) {}
        // 主账号自己的设备号同理：空/非法就按它的 session/token 派生一个并写回。
        try {
            val s0 = kotlinx.coroutines.runBlocking { SettingsStore.flow(ctx).first() }
            if (!DeviceId.isValid(s0.traeDeviceId)) {
                val seed = s0.traeSession.ifBlank { s0.traeToken }
                if (seed.isNotBlank()) {
                    val d = DeviceId.derive(seed)
                    kotlinx.coroutines.runBlocking { SettingsStore.save(ctx, s0.copy(traeDeviceId = d)) }
                }
            }
        } catch (_: Exception) {}
        // V2.78（用户 m05383「主账号签不上、账号 2 能签」）：把主账号与多账号列表里
        // 「同一个上游 Trae 号」的条目合并掉，顺便用新鲜那份凭据救活主账号。
        try { collapseMainTrae() } catch (_: Exception) {}
    }

    /**
     * V2.78：合并「主账号 + 列表里同一个 uid 的 Trae 条目」。
     *
     * 背景（用户 m05383）：旧版 [LoginWebActivity.persistTrae] 在多账号通道里只判
     * session/token 是否完全相同。同一个 Trae 号重新登录一次会换一枚 token + 一条新
     * session，于是被判成「新账号」追加进列表；主账号仍旧握着早已失效的旧凭据。
     * 界面上就出现两张指着同一个上游号的卡：主账号那张每次签到必然失败（红叉）、
     * 列表里那张成功（绿勾），而且两张卡的余额一模一样（本来就是同一个号）。
     *
     * 这里在启动时自愈一次：
     * ① 列表里 uid 与主账号相同的条目 = 同一个号 → 一律从列表移除（不再多一张卡）；
     * ② 若主账号凭据已失效（既没有 session，token 也过期/为空），把列表那条的新凭据
     *    接过来写回主账号 —— 用户不需要重新登录就能恢复签到；
     * ③ 设备号沿用主账号已有的合法号（设备号必须永久稳定，换号 = 换设备 → 9074）。
     *
     * uid 取不到（老版本没记、或 token 不是 JWT）时**什么都不做** —— 判不了就别乱合并，
     * 宁可多一张卡，也不能把两个真账号并成一个（漏签比多签更严重）。
     */
    private fun collapseMainTrae() {
        val s0 = kotlinx.coroutines.runBlocking { SettingsStore.flow(ctx).first() }
        val mainUid = if (s0.traeToken.length >= 10) LocalCheckin.jwtUid(s0.traeToken) else ""
        if (mainUid.isBlank()) return
        val all = TraeAccountStore.all(ctx)
        val same = all.filter { it.uid.isNotBlank() && it.uid == mainUid }
        if (same.isEmpty()) return

        val now = System.currentTimeMillis() / 1000
        val mainExp = LocalCheckin.jwtExpiresAt(s0.traeToken)
        val mainAlive = s0.traeSession.isNotBlank() || (mainExp > 0 && mainExp > now + 60)

        // 挑一份最新鲜的凭据来刷新主账号：优先「有 session 的」（session 能自动续期），
        // 其次 token 剩余有效期最长的。
        val donor = same
            .filter { it.token.isNotBlank() || it.session.isNotBlank() }
            .maxByOrNull { if (it.session.isNotBlank()) Long.MAX_VALUE else LocalCheckin.jwtExpiresAt(it.token) }

        // 只在「主账号凭据已失效」时才接管 donor 的凭据 —— 主账号还活着就说明它能自己签，
        // 不覆盖（避免把一份能用的 session 换成不确定的）。
        if (!mainAlive && donor != null) {
            kotlinx.coroutines.runBlocking {
                SettingsStore.save(
                    ctx,
                    s0.copy(
                        traeToken = donor.token.ifBlank { s0.traeToken },
                        traeSession = donor.session.ifBlank { s0.traeSession },
                    ),
                )
            }
        }
        // 这些条目与主账号是同一个上游号（uid 相同）→ 一律移除：主账号已经承载了这个号，
        // 再留一条只会多出一张「余额一样、状态却不同」的卡（用户 m05383 看到的就是这个）。
        // 凭据已经在上面按需接走，这里移除不会丢凭据。
        same.forEach { TraeAccountStore.remove(ctx, it.id) }
    }

    companion object {
        /** 北京时区的今天（yyyy-MM-dd），供 Worker 等场景复用。 */
        fun todayStatic(): String =
            LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_DATE)

        /**
         * 并行执行用的线程池：各家服务互相独立，串行等就是白白排队。
         *
         * 之前「一键全部签到」是 WorkBuddy → Trae → Qoder → MiniMax 一家一家串，
         * 单家 1~3 秒，四家加起来 6~10 秒，用户看到的就是「点了没反应」。
         * 现在四家同时发，总耗时 ≈ 最慢那家。
         *
         * 注意：池子里的任务只做网络请求，**不碰 SQLite / SharedPreferences**——
         * 写库全部回到调用线程（LocalApi 由 WebUiActivity 的单线程 apiExecutor 串行驱动）。
         */
        private val NET_POOL = Executors.newFixedThreadPool(8) { r ->
            Thread(r, "signhub-net").apply { isDaemon = true }
        }

        private const val TASK_TIMEOUT_SEC = 60L

        /**
         * 一批任务的总时限（秒）。
         *
         * 用户 m22011：点「刷新余额」后界面弹出「连不上电脑端 / timeout: 本地接口
         * 75 秒未返回」。根因不是某一个接口慢，而是 [parallel] 以前**按顺序逐个
         * `f.get(60s)`** —— 六个任务同时提交、同时跑，但只要有一个上游 hang 住
         * （OkHttp 的 callTimeout 是 35s，DNS 卡住时更久），调用方就要在这个位置上
         * 干等满 60 秒，后面的 futrue 即使早就跑完也只能排队等。
         *
         * 现在改成「共享一个截止时刻」：每个 future 的等待时间 = 剩余预算，而不是
         * 各自 60 秒。整批最多 42 秒返回，剩下的交给下一轮刷新。
         */
        private const val BATCH_BUDGET_SEC = 42L

        /**
         * 并行跑一批任务，按传入顺序返回结果；单个任务异常收成 null 不拖累其它。
         *
         * 必须带超时：以前是裸 `f.get()`，只要有一家上游 hang 住（DNS 卡住、
         * TCP 连上但服务端不回），这个线程就永久占在池子里；池子只有 6 个线程，
         * 而 LocalApi 全部调用都来自 WebUiActivity 的 apiExecutor ——
         * 一旦卡死，整个 App 的本地接口（含首页刷新）全部排队到超时。
         *
         * P2-3：`f.cancel(true)` 对阻塞在 OkHttp `execute()` 的任务是无效的
         * （`Call.execute()` 不响应线程中断，只设置中断位）。真正的兜底是
         * `LocalCheckin` 的 `callTimeout(35s)` —— 比这里更早触发，
         * 让线程自己退出、把池子还回来。这里保留 `cancel(true)` 作为兜底。
         */
        private fun <T> parallel(tasks: List<() -> T>): List<T?> {
            if (tasks.isEmpty()) return emptyList()
            val deadline = System.currentTimeMillis() + BATCH_BUDGET_SEC * 1000
            val futures = tasks.map { t -> NET_POOL.submit(Callable { try { t() } catch (e: Exception) { null } }) }
            return futures.map { f ->
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    f.cancel(true)
                    return@map null
                }
                try {
                    f.get(left, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    f.cancel(true)
                    null
                } catch (e: Exception) {
                    null
                }
            }
        }

        /**
         * 与 [parallel] 相同，但额外返回「哪几个位置是超时导致的 null」。
         *
         * P2-8：`parallel` 把「超时」和「任务本身抛异常」都收成 null，调用方
         * 只能统一兜底成「缺少凭据」—— 上游 hang 住 60 秒时用户看到的是
         * 「缺少凭据：填 mmoat_ 开头的 token」，真实原因（超时）被完全掩盖。
         * 这里把超时的下标一并返回，调用方可以给出准确文案。
         *
         * [timeoutSec] 允许按任务下标给不同上限：Trae 的多账号现在是**串行 + 错峰**
         * 跑的（见 `checkinAll` 里的注释，claim 并发会互相挤成 9074），
         * N 个账号最坏要 N×25 秒的间隔加上每家的网络耗时，用统一的 60 秒会
         * 在第 3 个账号就开始超时，把「还没轮到」误报成「上游超时」。
         */
        private fun <T> parallelDetail(
            tasks: List<() -> T>,
            timeoutSec: (Int) -> Long = { TASK_TIMEOUT_SEC },
        ): Pair<List<T?>, Set<Int>> {
            if (tasks.isEmpty()) return Pair(emptyList(), emptySet())
            val futures = tasks.map { t -> NET_POOL.submit(Callable { try { t() } catch (e: Exception) { null } }) }
            val out = ArrayList<T?>(futures.size)
            val timeouts = HashSet<Int>()
            futures.forEachIndexed { i, f ->
                out.add(
                    try {
                        f.get(timeoutSec(i), TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        f.cancel(true)
                        timeouts.add(i)
                        null
                    } catch (e: Exception) {
                        null
                    }
                )
            }
            return Pair(out, timeouts)
        }
    }

    fun handle(method: String, rawPath: String, body: String): String {
        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "")
        return try {
            when {
                path == "/api/ping" -> """{"ok":true,"local":true,"appVersion":"${BuildConfig.VERSION_NAME}","versionCode":${BuildConfig.VERSION_CODE}}"""
                path == "/api/status" -> status()
                path == "/api/logs" -> logs(query)
                path == "/api/stats" -> stats()
                // POST /api/config 必须排在 GET 之前：when 顺序求值，否则保存永远被只读分支吞掉
                method == "POST" && path == "/api/config" -> saveConfig(body)
                path == "/api/config" -> config()
                // 重置某家的登录凭据（用户 m21385）。手机端凭据在 DataStore，
                // 与电脑端的 config.json 是两套存储，所以必须各清各的。
                method == "POST" && path == "/api/reset_creds" -> resetCreds(body)
                path == "/api/doctor" -> doctor()
                // 原来是硬编码空数组（假实现）。App 内本来就把推送日志写进
                // SharedPreferences 的 signhub_notify_log，直接复用 notifyHistory()。
                path == "/api/notify_logs" -> notifyHistory()
                // 写操作必须校验 method：以前 /api/checkin/all 不区分 GET/POST，
                // 浏览器预取、SW 的 fetch、用户直接在地址栏敲一下，都会真的跑一轮签到。
                // 签到是打上游接口的副作用操作，不能靠 GET 触发。
                method == "POST" && path == "/api/checkin/all" -> checkinAll()
                method == "POST" && path.startsWith("/api/checkin/") ->
                    checkinOne(path.removePrefix("/api/checkin/"))
                method == "POST" && path.startsWith("/api/test/") ->
                    checkinOne(path.removePrefix("/api/test/"), log = false)
                path == "/api/checkin/all" -> methodNotAllowed("POST")
                path.startsWith("/api/checkin/") -> methodNotAllowed("POST")
                path.startsWith("/api/test/") -> methodNotAllowed("POST")
                path.startsWith("/api/probe/") -> probe(path.removePrefix("/api/probe/"))
                // 手机上没有电脑的客户端凭据：Qoder 的 auth.v1.dat 是 Windows DPAPI + AES-GCM 加密的，
                // MiniMax 的 auth.json 在电脑的用户目录里。这里给一条明确提示，别让前端报「未知错误」。
                //
                // P3-1（BUGS-v2.25）：原来用 startsWith，`/api/local_creds/anything`、
                // `/api/local_creds/../../etc` 都会命中同一句提示 —— 前端无法区分
                // 「接口存在但手机不支持」与「路径写错」，排查时被这句话误导过。
                // 改成精确匹配两个已知 key，其余落到最后的 not found。
                path == "/api/local_creds/qoder" || path == "/api/local_creds/minimax" -> """{"ok":false,"error":"手机上读不到电脑客户端凭据：Qoder 请点「网页登录 Qoder」走官方授权，MiniMax 请点「网页登录 MiniMax」"}"""
                // 推送：本地模式直接在 App 内发（按 webhook host 自适应各渠道）
                // P3 N5（BUGS-v2.3）：测试推送同样会真的打 webhook，是副作用操作，
                // 必须和签到一样校验 method —— 否则浏览器预取 / 地址栏直接敲一下就发一条。
                method == "POST" && path == "/api/test_notify" -> testNotify(body)
                path == "/api/test_notify" -> methodNotAllowed("POST")
                path == "/api/notify_history" -> notifyHistory()
                // WorkBuddy 成长中心
                path == "/api/growth" -> growth(needRefresh(query))
                method == "POST" && path == "/api/growth/room" -> growthRunTasks()
                method == "POST" && path == "/api/growth/claim" -> growthClaimAll()
                path == "/api/growth/daily" -> growthDaily(needRefresh(query))
                method == "POST" && path == "/api/growth/daily/run" -> growthRunDaily()
                // 积分资产 / 过期预警
                path == "/api/wb_assets" -> wbAssets(needRefresh(query))
                // 成长中心账号切换（用户 m14411）：GET 列表，POST 切换
                method == "POST" && path == "/api/wb_accounts" -> setWbAccount(body)
                path == "/api/wb_accounts" -> wbAccounts()
                path == "/api/heat" -> heat()
                // P1-2（BUGS-v1.63）：refresh_totals 会并发打四家上游并写库，是副作用操作。
                // 以前挂在无 method 校验的分支上，浏览器预取 / SW 重放 / 地址栏直接敲
                // 都会真跑一轮。现在只接受 POST，GET 明确回 405（前端已同步改成 POST）。
                method == "POST" && path == "/api/refresh_totals" -> refreshTotals()
                path == "/api/refresh_totals" -> methodNotAllowed("POST")
                path == "/api/auto_status" -> autoStatus()
                // P2 N4（BUGS-v2.3）：SecretBox.reset() 以前是零调用方的死 API。
                // 前端「知道了」按钮现在会打这里 —— 用户手动确认后复位加密失败计数，
                // 再刷新一次 crypto_ok，让「本机凭据未加密」黄条能真正消掉
                // （否则只能等 Keystore 恰好自己恢复）。
                method == "POST" && path == "/api/crypto_reset" -> {
                    SecretBox.reset()
                    JSONObject().apply {
                        put("ok", true)
                        put("crypto_ok", SecretBox.healthy())
                        put("failures", SecretBox.failures())
                    }.toString()
                }
                path == "/api/crypto_reset" -> methodNotAllowed("POST")
                path == "/api/trae_probe" -> traeProbe()
                path == "/api/trae_login" -> """{"ok":true,"message":"请到设置里填 Cloud-IDE-Token"}"""
                else -> """{"error":"not found"}"""
            }
        } catch (e: Exception) {
            // 兜底：/api/config 一旦失败，前端 renderProviderEditors() 会因为拿不到
            // cfg.providers 直接 return → 设置页「签到配置」整片空白（用户实测就是这个现象）。
            // 这里返回一份最小可用配置，至少让 4 张卡渲染出来、凭据字段留空可填。
            if (path == "/api/config") {
                return configFallback(e.message ?: "unknown")
            }
            // P3-6（BUGS-v1.63）：带 __http_status=500。shim 的 __signhubResp 会把
            // 所有非 not-found 的 error 判成 400，前端分不清「请求写错了」与「服务端炸了」。
            """{"error":${JSONObject.quote(e.message ?: "unknown")},"__http_status":500}"""
        }
    }

    /** 写接口被 GET 打到时的统一响应（前端能看出「是方法错了」而不是「服务坏了」）。 */
    private fun methodNotAllowed(expect: String): String =
        """{"error":"method not allowed","expect":"$expect","__http_status":405}"""

    /** /api/config 异常时的最小可用配置：5 个 provider 全 enabled、凭据留空。 */
    private fun configFallback(reason: String): String {
        val providers = JSONObject()
        val fams = mutableListOf(
            "workbuddy" to "wb",
            "traesolo" to "trae",
            "qoder" to "qoder",
            "minimax" to "minimax",
            "huawei" to "huawei",
            "zhipu" to "zhipu",
            "qianfan" to "qianfan",
        )
        // 豆包 V2.75 起搁置（DoubaoWebCheckin.ENABLED=false）：兜底配置里也不列，
        // 否则电脑端/前端会多出一张点了就报错的空卡片。
        if (DoubaoWebCheckin.ENABLED) fams.add("doubao" to "doubao")
        fams.forEach { (k, mode) ->
            providers.put(k, JSONObject().apply {
                put("enabled", true)
                put("mode", mode)
                when (mode) {
                    "wb" -> put("wb", JSONObject().put("region", "cn"))
                    "trae" -> put("trae", JSONObject().put("region", "cn"))
                    "qoder" -> put("qoder", JSONObject().put("region", "cn"))
                    "minimax" -> put("minimax", JSONObject())
                    "huawei" -> put("huawei", JSONObject())
                    "doubao" -> put("doubao", JSONObject())
                    "zhipu" -> put("zhipu", JSONObject())
                    "qianfan" -> put("qianfan", JSONObject())
                    else -> {}
                }
            })
        }
        return JSONObject().apply {
            put("providers", providers)
            put("degraded", true)
            put("error", reason)
            put("notify", JSONObject().put("enabled", false).put("kind", "wecom"))
            // L4 / P2R3（BUGS-v2.2）：降级时也要把这两个字段带上，
            // 否则 showCredsAlert 读不到值 → 凭据失效横幅在降级状态下不显示。
            // settings() 内部已有 4 次重试 + 回落 AppSettings()，这里再包一层 runCatching
            // 是为了「配置接口都挂了」的极端情况下兜底不抛。
            val lost = runCatching { settings().credsLost }.getOrDefault(false)
            put("server", JSONObject()
                .put("local_mode", true)
                .put("creds_lost", lost)
                .put("crypto_ok", SecretBox.healthy()))
            put("scheduler", JSONObject().apply {
                put("enabled", true); put("times", JSONArray().put("08:30"))
                put("jitter_min", 30); put("timezone_offset_hours", 8)
            })
        }.toString()
    }

    // ── 基础工具 ──────────────────────────────────────────────────────
    private fun today(): String =
        LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_DATE)

    /**
     * 读设置。
     *
     * DataStore 在「一边写一边读」时会抛异常（`IllegalStateException` / `IOException`）。
     * 真实踩到的场景：登录页刚把凭据写进 DataStore、WebView 立刻恢复并并发打
     * `/api/status` + `/api/config` + `/api/logs` + `/api/stats`，四个请求各自 `flow().first()`，
     * 只要有一个撞上写入窗口就抛 → `handle()` 的 catch 把它变成 `{"error": ...}` →
     * 前端 `renderProviderEditors()` 拿不到 `cfg.providers` 直接 return →
     * **设置页签到配置整片空白**，而且不会自愈（下一轮 25s 才重试）。
     *
     * 这里重试几次，最后仍失败就回落默认值：宁可显示「未配置」，也不要整页空。
     */
    private fun settings(): AppSettings {
        repeat(4) { i ->
            try {
                return runBlocking { SettingsStore.flow(ctx).first() }
            } catch (e: Exception) {
                if (i >= 3) return AppSettings()
                try { Thread.sleep(60L) } catch (_: InterruptedException) {}
            }
        }
        return AppSettings()
    }

    private fun cfg(): LocalConfig = LocalConfig.load(ctx)

    /** 查询串里带 refresh=1 时强制绕过缓存。 */
    private fun needRefresh(query: String): Boolean =
        Regex("(?:^|&)refresh=1").containsMatchIn(query)

    /**
     * 取当前用于「成长中心 / 积分资产」的 WorkBuddy 账号。
     *
     * 用户 m14411：成长中心要能切到本机保存的其它账号做任务，所以这里先看
     * `WbAccountStore.currentId()` 有没有「用户明确选中的账号」；
     * 没选过（空串）时保持老行为：主账号优先，否则多账号列表第一个。
     */
    private fun wbAccount(): WbAccount? {
        val s = settings()
        val main: WbAccount? = if (s.wbToken.isNotBlank()) {
            WbAccount(
                id = "default",
                uid = s.wbUid,
                nickname = "主账号",
                enterpriseId = s.wbEnterpriseId,
                accessToken = s.wbToken,
                refreshToken = s.wbRefreshToken,
                domain = s.wbDomain,
                variant = if (s.wbGlobal) "ai" else "cn",
            )
        } else null
        val accs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
        // ① 用户显式选中的账号优先（成长中心的「账号切换」写这里）
        val picked = try { WbAccountStore.currentId(ctx) } catch (e: Exception) { "" }
        if (picked.isNotBlank()) {
            if (picked == "default" && main != null) return main
            accs.firstOrNull { it.id == picked && it.accessToken.isNotBlank() }?.let { return it }
            // 选中的账号被删了 / 凭据被清空：落回默认，别让成长中心一直报「未配置」
            try { WbAccountStore.setCurrent(ctx, "") } catch (e: Exception) { }
        }
        if (main != null) return main
        return accs.firstOrNull { it.accessToken.isNotBlank() }
    }

    /**
     * 成长中心的账号切换（用户 m14411）。
     *
     * 返回本机保存的全部 WB 账号（含主账号）+ 当前选中的那个。
     * 前端只负责展示与选择，切换通过 POST /api/wb_account 写回。
     */
    private fun wbAccounts(): String {
        val s = settings()
        val accs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
        val cur = try { WbAccountStore.currentId(ctx) } catch (e: Exception) { "" }
        val arr = JSONArray()
        // 主账号（设置页里那一组扁平字段）恒排第一，id 固定 "default"
        if (s.wbToken.isNotBlank()) {
            arr.put(JSONObject().apply {
                put("id", "default")
                put("name", "主账号")
                put("uid", s.wbUid)
                put("current", cur.isBlank() || cur == "default")
            })
        }
        accs.filter { it.accessToken.isNotBlank() }.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.displayName())
                put("uid", a.uid)
                put("current", cur == a.id)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("list", arr)
            put("current", cur)
            put("count", arr.length())
        }.toString()
    }

    /** 切换成长中心当前账号（用户 m14411）。body: {"id":"..."} */
    private fun setWbAccount(body: String): String {
        val id = try { JSONObject(body.ifBlank { "{}" }).optString("id", "") } catch (e: Exception) { "" }
        if (id.isBlank()) return """{"ok":false,"error":"缺少 id"}"""
        val accs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
        val exists = if (id == "default") {
            settings().wbToken.isNotBlank()
        } else {
            accs.any { it.id == id && it.accessToken.isNotBlank() }
        }
        if (!exists) return """{"ok":false,"error":"账号不存在或凭据已清空"}"""
        WbAccountStore.setCurrent(ctx, id)
        // 切换后缓存全部作废：成长中心的缓存键带 uid，换账号必须重新拉
        val acc = wbAccount()
        return JSONObject().apply {
            put("ok", true)
            put("current", id)
            put("name", acc?.displayName() ?: "")
        }.toString()
    }

    /**
     * 单账号（设置里的 wbToken）在签到记录里的账号键。
     *
     * 用 uid 而不是固定 "default"：同一台手机上「主账号」与多账号列表里的同一个人
     * 应该落到同一条记录，否则会重复计数。uid 读不到时退回 "default"。
     */
    private fun wbAccountKey(s: AppSettings): String =
        s.wbUid.ifBlank { HistoryStore.DEFAULT_ACCOUNT }

    /**
     * 多账号列表里的这条，是不是就是设置里那个「主账号」？
     *
     * 和 AutoCheckinWorker.isSameAsMainAccount 同一判据：uid 相同 → 同一人；
     * 否则 accessToken 完全相同 → 同一人。宁可漏判成两个账号（多签一次），
     * 也不能把两个真账号误判成一个（漏签）。
     */
    private fun isSameAsMainAccount(acc: WbAccount, s: AppSettings): Boolean {
        if (s.wbToken.isBlank()) return false
        // 区域不同就是不同账号：workbuddy.ai 与 codebuddy.cn 是两套账号体系，
        // uid 撞车（都从 0 开始编号）完全可能，不能因为 uid 相同就判成同一个人，
        // 否则国际版账号会被当成主账号的重复项直接丢掉（m19236）。
        val mainVariant = if (s.wbGlobal) "ai" else "cn"
        if (acc.variant != mainVariant) return false
        if (s.wbUid.isNotBlank() && acc.uid.isNotBlank() && s.wbUid == acc.uid) return true
        return acc.accessToken == s.wbToken
    }

    // ── Trae / Qoder / MiniMax 的「单账号 + 多账号」合并视图 ──────────────    //
    // 三家原来的凭据只有 DataStore 里一组扁平字段（等于单账号）。多账号列表是
    // SharedPreferences 里的 JSONArray（MultiAccounts.kt）。这里把两者合并成
    // 一个待签列表：设置里那个「主账号」永远排第一、账号键用 DEFAULT_ACCOUNT，
    // 列表里的账号用自己的 id 作账号键，同一个人不重复签。
    //
    // 判重取向与 WorkBuddy 一致：宁可多签一次，也不能把两个真账号判成一个（漏签）。

    private fun sameTrae(a: TraeAccount, b: TraeAccount): Boolean =
        // uid 优先（用户 m18704）：同一个 Trae 账号重新登录一次就会换一枚 token + 一条新
        // session，只比这两样会把同一个号认成三个号 —— 三张卡拿着同一账号的三枚 token
        // 去查余额，界面就出现「每个账号积分都一致」。JWT 里的 uid 不会变。
        (a.uid.isNotBlank() && a.uid == b.uid) ||
            (a.session.isNotBlank() && a.session == b.session) ||
            (a.token.isNotBlank() && a.token == b.token)

    private fun sameQoder(a: QoderAccount, b: QoderAccount): Boolean =
        (a.refresh.isNotBlank() && a.refresh == b.refresh) ||
            (a.token.isNotBlank() && a.token == b.token) ||
            (a.uid.isNotBlank() && a.uid == b.uid)

    private fun sameMm(a: MmAccount, b: MmAccount): Boolean =
        (a.token.isNotBlank() && a.token == b.token) ||
            (a.userId.isNotBlank() && a.userId == b.userId)

    /** 主账号 + 多账号列表（去重） */
    private fun traeList(s: AppSettings): List<TraeAccount> {
        val out = mutableListOf<TraeAccount>()
        if (s.traeToken.isNotBlank() || s.traeSession.isNotBlank()) {
            out.add(
                TraeAccount(
                    id = HistoryStore.DEFAULT_ACCOUNT,
                    token = s.traeToken, session = s.traeSession,
                    deviceId = s.traeDeviceId, global = s.traeGlobal,
                    // 主账号也带上 JWT uid：列表里如果存着同一个号的另一枚 token
                    // （重新登录过），只有 uid 能把它认出来并去重（用户 m18704）。
                    uid = if (s.traeToken.length >= 10) LocalCheckin.jwtUid(s.traeToken) else "",
                )
            )
        }
        runCatching { TraeAccountStore.all(ctx) }.getOrDefault(mutableListOf())
            .filter { it.token.isNotBlank() || it.session.isNotBlank() }
            .filter { acc -> out.none { sameTrae(it, acc) } }
            .forEach { out.add(it) }
        return out
    }

    private fun qoderList(s: AppSettings): List<QoderAccount> {
        val out = mutableListOf<QoderAccount>()
        if (s.qoderToken.isNotBlank() || s.qoderRefresh.isNotBlank()) {
            out.add(
                QoderAccount(
                    id = HistoryStore.DEFAULT_ACCOUNT,
                    token = s.qoderToken, refresh = s.qoderRefresh,
                    uid = s.qoderUid, machineId = s.qoderMachineId,
                    region = s.qoderRegion.ifBlank { "cn" },
                )
            )
        }
        runCatching { QoderAccountStore.all(ctx) }.getOrDefault(mutableListOf())
            .filter { it.token.isNotBlank() || it.refresh.isNotBlank() }
            .filter { acc -> out.none { sameQoder(it, acc) } }
            .forEach { out.add(it) }
        return out
    }

    private fun mmList(s: AppSettings): List<MmAccount> {
        val out = mutableListOf<MmAccount>()
        if (s.mmToken.isNotBlank()) {
            out.add(
                MmAccount(
                    id = HistoryStore.DEFAULT_ACCOUNT,
                    token = s.mmToken, userId = s.mmUserId, deviceId = s.mmDeviceId,
                )
            )
        }
        runCatching { MmAccountStore.all(ctx) }.getOrDefault(mutableListOf())
            .filter { it.token.isNotBlank() }
            .filter { acc -> out.none { sameMm(it, acc) } }
            .forEach { out.add(it) }
        return out
    }

    /**
     * 华为码道账号列表（用户 m24258：华为新增多账号）。
     *
     * **去重键是 `HwAccount.identityOf`（优先 `agencyID`），不是整条 Cookie**
     * ——BUGS-v2.48 P2-1：v2.48 把 `HwAccountStore.upsert` 与 `persistHw` 的判重都改成
     * agencyID 优先，唯独这个「所有签到路径的入口」还在比整条 Cookie。后果是主账号
     * `s.hwCookie` 与多账号列表里同一个号（agencyID 相同、Cookie 不同）会同时留下：
     * 首页多一张重复卡、`checkinAll` 派两个 job、`hwExpect` 算成 2、落库时主账号写
     * `default`、列表账号写 `acc.id`，同一天同一个人两条历史记录。
     *
     * 主账号恒排第一（`HistoryStore.DEFAULT_ACCOUNT`），多账号列表排其后。
     */
    private fun hwList(s: AppSettings): List<HwAccount> {
        val out = mutableListOf<HwAccount>()
        val seen = mutableSetOf<String>()
        if (s.hwCookie.isNotBlank()) {
            out.add(
                HwAccount(
                    id = HistoryStore.DEFAULT_ACCOUNT,
                    cookie = s.hwCookie,
                    lastStatus = "",
                    lastMessage = "",
                )
            )
            seen.add(HwAccount.identityOf(s.hwCookie))
        }
        runCatching { HwAccountStore.all(ctx) }.getOrDefault(mutableListOf())
            .filter { it.cookie.isNotBlank() }
            .filter { seen.add(HwAccount.identityOf(it.cookie)) }
            .forEach { out.add(it) }
        return out
    }

    /**
     * 这份华为 Cookie 是否「能用」（BUGS-v2.46 P2）。
     *
     * 判据 = 有会话名 **且** 没有被证实是未登录：
     * - `hwHasSession` 只判字符串包含，华为对未登录访客也发匿名 `J_SESSION_ID`，
     *   单靠它会让「打开首页、连手机号都没输」的账号显示绿色就绪；
     * - 真判据是 `hwProbe`，但它要打上游，不能放进 `/api/status` 的 25 秒轮询里。
     *   所以采集/验证那一刻的结论由 [HwVerified] 存下来，这里只读本地。
     *
     * `HwVerified.get` 返回 `null`（没有结论）时按**可用**处理 —— 老版本账号没这条记录，
     * 升级后不能突然全变「未就绪」。
     */
    private fun hwUsable(cookie: String): Boolean = HwVerified.usable(ctx, cookie)

    /**
     * 多账号签到的账号键。
     *
     * P3-3：这里原本写成 `if (id == DEFAULT_ACCOUNT) DEFAULT_ACCOUNT else id`，
     * 是个恒等函数（死代码）。保留函数是为了「语义命名」——调用处读起来是
     * 「这个账号该用哪个 key 落库」，但实现必须能容纳后续把空 id 归一化的情况，
     * 所以补上 `ifBlank`：账号列表里 id 为空（老数据 / 解析异常）时不能写出空
     * account_key，否则会和「主账号」那一行撞成同一条（UNIQUE(provider, day, account_key)）。
     */
    private fun acctKey(id: String): String = id.ifBlank { HistoryStore.DEFAULT_ACCOUNT }

    /**
     * 一张卡对应一个「已配置的账号」。
     *
     * 用户 m16298：多账号登录后必须直接出现在签到中心，不能等点了「一键全部签到」才冒出来。
     * 旧实现是拿**当天签到记录**（`recordsOfProvider`）反推卡片，于是「刚登录、今天还没签」
     * 的新账号根本没有记录 → 没有卡；只有当天已经签过的账号才有卡。现在改成**以账号列表为准**，
     * 记录只用来填状态与积分（有就用，没有就按「待签」占位）。
     */
    private data class AcctRef(val key: String, val label: String, val sub: String = "")

    // ── /api/status ───────────────────────────────────────────────────
    private fun status(): String {
        val s = settings()
        val c = cfg()
        val day = today()
        val recs = store.recordsOf(day)

        val rows = JSONArray()

        // WorkBuddy：单账号（设置里的 wbToken）或多账号列表任一有 token 即视为就绪
        val wbAccs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
        val wbMulti = wbAccs.count { it.accessToken.isNotBlank() }
        // 与 allDoneToday / checkinAll 共用同一判据（P3 N8，BUGS-v2.3）
        val wbReady = isReady("workbuddy", s)
        val wbIssues = if (wbReady) emptyList() else listOf("未登录：点「登录主账号」或「多账号管理」添加账号")
        // 卡片以账号列表为准（用户 m16298）：主账号排第一，其后是列表里非主账号的多账号
        val wbAcctRefs = buildList {
            if (s.wbToken.isNotBlank()) {
                // 主账号：设置页是扁平字段，没有昵称可读，用与 wbAccount() 一致的名字。
                // 国际版要显式标出来：两个站的账号体系互不相通，用户在卡片上分不清
                // 「这个主账号是国内还是国际」就会以为是同一个号（m19236）。
                add(AcctRef(wbAccountKey(s), if (s.wbGlobal) "主账号 · 国际版" else "主账号", "WorkBuddy"))
            }
            wbAccs.filter { it.accessToken.isNotBlank() && !isSameAsMainAccount(it, s) }
                .forEach { a ->
                    val nm = a.displayName().ifBlank { "账号 " + (size + 1) }
                    add(AcctRef(acctKey(a.id), if (a.variant == "ai") "$nm · 国际版" else nm, "WorkBuddy"))
                }
        }
        rows.put(providerRow(
            key = "workbuddy", label = "WorkBuddy", mode = "wb",
            ready = wbReady, issues = wbIssues,
            rec = recs["workbuddy"], day = day,
            acctCount = maxOf(1, wbMulti + (if (s.wbToken.isNotBlank()) 1 else 0)),
            accts = wbAcctRefs
        ))
        // 积分还是未知（今天没签过 + 从没读到过）→ 后台补一次，不阻塞首页
        if (wbReady && cachedTotal("workbuddy") < 0) backfillWbTotal(s)
        // Trae：token（约 8 小时）或 session（约 14 天，可自动续期）任一即可。
        // 多账号列表里任何一条有凭据也算就绪。
        val traeAccs = traeList(s)
        val traeHasAuth = traeAccs.any { it.token.isNotBlank() || it.session.isNotBlank() }
        val traeReady = isReady("traesolo", s)
        val traeIssues = buildList {
            if (!traeHasAuth) add("缺少凭据：点「登录主账号」，或在设置里填 X-Cloudide-Session")
        }
        rows.put(providerRow(
            key = "traesolo", label = "Trae Solo", mode = "trae",
            ready = traeReady, issues = traeIssues,
            rec = recs["traesolo"], day = day, acctCount = traeAccs.size,
            accts = traeAccs.mapIndexed { i, a ->
                AcctRef(acctKey(a.id), a.displayName().ifBlank { "账号 " + (i + 1) }, a.metaLine())
            }
        ))
        // Trae 同样：积分未知就后台补一次（Trae 的持有积分要单独查 ide_user_ent_usage）
        if (traeReady && cachedTotal("traesolo") < 0) backfillTraeTotal(s)

        // 历史记录仍留在本地库里，统计/热力图照常显示，只是不再新增。

        // Qoder CN：每日积分活动（dt- token 或 drt- refreshToken 都行）
        //
        // 设备指纹 Cosy-MachineId 曾被当成必需项，导致手机上永远「未配置」——
        // 实测（六种组合打 campaigns）：real token + 随机 UUID / 不带头 / machine=0
        // 全部返回 200，服务端只校验 token，machineId 只是可选的埋点字段。
        // 另外 refreshToken（drt-）单独也能用：token 过期时 POST
        // /api/v1/deviceToken/refresh 就能换新的，有效期一年、可反复换。
        // 所以这里只判「token 或 refreshToken 有一个」，两者都没有才算未就绪。
        val qdAccs = qoderList(s)
        val qdReady = isReady("qoder", s)
        val qdIssues = buildList {
            if (!qdReady)
                add("缺少凭据：填 dt- 的 accessToken，或 drt- 的 refreshToken（推荐，有效期一年）")
            else if (qdAccs.all { it.token.isBlank() })
                add("只用 refreshToken（会自动续期）")
            else if (qdAccs.all { it.machineId.isBlank() })
                add("未填设备指纹（选填，不影响签到）")
        }
        rows.put(providerRow(
            key = "qoder", label = "Qoder CN", mode = "qoder",
            ready = qdReady, issues = qdIssues,
            rec = recs["qoder"], day = day, acctCount = qdAccs.size,
            accts = qdAccs.mapIndexed { i, a ->
                AcctRef(acctKey(a.id), a.displayName().ifBlank { "账号 " + (i + 1) }, a.metaLine())
            }
        ))

        // MiniMax Code：每日签到
        //
        // user_id / device_id 同样不参与鉴权（实测 user_id=0 + 默认 device_id 也返回
        // 200 与完整 7 天面板），所以只判 token；填了是更好，没填也照样能签。
        val mmAccs = mmList(s)
        val mmReady = isReady("minimax", s)
        val mmIssues = buildList {
            if (!mmReady)
                add("缺少 accessToken：填 mmoat_ 开头的 token（电脑端登录 MiniMax Code 后可读）")
            else if (mmAccs.all { it.userId.isBlank() })
                add("未填 user_id（选填，不影响签到）")
        }
        rows.put(providerRow(
            key = "minimax", label = "MiniMax Code", mode = "minimax",
            ready = mmReady, issues = mmIssues,
            rec = recs["minimax"], day = day, acctCount = mmAccs.size,
            accts = mmAccs.mapIndexed { i, a ->
                AcctRef(acctKey(a.id), a.displayName().ifBlank { "账号 " + (i + 1) }, a.metaLine())
            }
        ))

        // 华为码道：站内登录后采集 Cookie，纯 HTTP 签到（delivery → claim → confirm）
        //
        // 会话 Cookie 静置 30~60 分钟就失效，所以 ready 只看「有没有 Cookie」，
        // 不为了判活去打上游 —— 每次 /api/status 都 ping 一次会把会话耗得更快，
        // 而且失效时用户看到的就是「未签到」，点签到会拿到明确的 401 文案。
        //
        // 用户 m24258：华为改成多账号。每个账号一份 Cookie，判重优先 agencyID
        // （BUGS-v2.46 P1：整条 Cookie 会随重登变，用它判重会把同一账号拆成两条）。
        val hwAccs = hwList(s)
        // P2（BUGS-v2.46）：就绪不能只看「Cookie 里有会话名」—— 华为对未登录访客也发
        // 匿名 J_SESSION_ID。用 [hwUsable]（会话名 + 采集时的 hwProbe 结论）判。
        val hwUsableAccs = hwAccs.filter { hwUsable(it.cookie) }
        val hwReady = hwUsableAccs.isNotEmpty()
        val hwIssues = buildList {
            if (hwAccs.isEmpty())
                add("缺少华为会话 Cookie：点「登录华为码道」在站内登录一次即可自动采集")
            else if (hwUsableAccs.isEmpty())
                add("这份 Cookie 是未登录状态（华为对未登录访客也发匿名会话）：请重新登录一次")
            // 积分接口与签到同域（/chat/snap-manager/v1/statistics/plugin），
            // 用同一份 cookie —— 只要会话在就能读，不再需要额外的门户 cookie。
        }
        rows.put(providerRow(
            key = "huawei", label = "华为码道", mode = "huawei",
            ready = hwReady, issues = hwIssues,
            rec = recs["huawei"], day = day, acctCount = hwAccs.size,
            accts = hwAccs.mapIndexed { i, a ->
                AcctRef(
                    key = if (a.id == HistoryStore.DEFAULT_ACCOUNT) HistoryStore.DEFAULT_ACCOUNT else acctKey(a.id),
                    label = if (hwAccs.size > 1) "账号 ${i + 1}" else "主账号",
                    sub = "devcloud",
                )
            },
        ).also { row ->
            // 字段名保留 portal_ok（前端与电脑端都在读），语义已改为
            // 「积分能不能读」= 「会话是否可用」。
            row.put("portal_ok", hwUsableAccs.isNotEmpty())
            // V2.79：会话过期后能否自动续期（auth 域凭据在 = 能，全程不用输手机号）。
            row.put("auto_renew", s.hwAuthCookie.isNotBlank() || hwAccs.any { it.authCookie.isNotBlank() })
        })

        // 智谱清言（V2.76 接入）：纯 HTTP 签到，签名可本地算。
        //
        // 就绪判据必须用 ZhipuCheckin.hasSession（refresh token 存在 **且** 不是游客）：
        // 游客态也有一套完整 token，只看「有没有 chatglm_token」会把游客认成已登录。
        // 这里刻意不真打上游：/api/status 每 25 秒跑一次，每次都 refresh 会白耗配额。
        val zpReady = isReady("zhipu", s)
        val zpIssues = buildList {
            if (s.zpCookie.isBlank())
                add("缺少 Cookie：点「登录智谱清言」在站内登录一次即可自动采集")
            else if (!zpReady)
                add("这份 Cookie 是游客态（JWT 里 is_guest=true）：请重新登录一次")
        }
        rows.put(providerRow(
            key = "zhipu", label = "智谱清言", mode = "zhipu",
            ready = zpReady, issues = zpIssues,
            rec = recs["zhipu"], day = day, acctCount = if (zpReady) 1 else 0,
            accts = if (zpReady) listOf(
                AcctRef(key = HistoryStore.DEFAULT_ACCOUNT, label = "主账号", sub = "chatglm.cn")
            ) else emptyList(),
        ))

        // 百度智能云 / 百度搭子 DuMate（V2.76 接入）：Cookie + csrftoken 纯 HTTP。
        val qfReady = isReady("qianfan", s)
        val qfIssues = buildList {
            if (s.qfCookie.isBlank())
                add("缺少 Cookie：点「登录百度智能云」在站内登录一次即可自动采集")
            else if (!qfReady)
                add("Cookie 里没有 BDUSS / bce-user-info：这是游客态，请重新登录一次")
        }
        rows.put(providerRow(
            key = "qianfan", label = "百度千帆", mode = "qianfan",
            ready = qfReady, issues = qfIssues,
            rec = recs["qianfan"], day = day, acctCount = if (qfReady) 1 else 0,
            accts = if (qfReady) listOf(
                AcctRef(key = HistoryStore.DEFAULT_ACCOUNT, label = "主账号", sub = "bce.baidu.com")
            ) else emptyList(),
        ))

        // 豆包（V2.66 重新接入；**V2.75 起搁置** —— DoubaoWebCheckin.ENABLED=false 时整块跳过）。
        //
        // 与华为的区别：豆包游客态一个 cookie 都拿不到（实测），所以
        // 「Cookie 里有 sessionid」本身就是可信的就绪判据，不需要额外的 probe 结论表。
        // 这里也刻意不真打上游：/api/status 每 25 秒跑一次，每次都打会白白耗会话。
        //
        // 搁置原因见 DoubaoWebCheckin.ENABLED 的注释：三个公共 query 补齐后
        // landing/checkin 仍返 710010702，需真实可签账号才能继续验，手上没有。
        if (DoubaoWebCheckin.ENABLED) {
            val dbReady = s.dbCookie.isNotBlank() && DoubaoWebCheckin.hasSession(s.dbCookie)
            val dbIssues = buildList {
                if (s.dbCookie.isBlank())
                    add("缺少豆包 Cookie：点「登录豆包」在站内登录一次即可自动采集")
                else if (!dbReady)
                    add("Cookie 里没找到 sessionid / sid_tt / uid_tt / sid_guard：请重新登录一次")
            }
            rows.put(providerRow(
                key = "doubao", label = "豆包", mode = "doubao",
                ready = dbReady, issues = dbIssues,
                rec = recs["doubao"], day = day, acctCount = if (dbReady) 1 else 0,
                accts = if (dbReady) listOf(AcctRef(key = HistoryStore.DEFAULT_ACCOUNT, label = "主账号", sub = "doubao.com")) else emptyList(),
            ))
        }

        // 近 7 天：一条 SQL 取完，避免 7 次独立查询（/api/status 每 25 秒跑一次）
        val weekDays = (6 downTo 0).map { i ->
            LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(i.toLong()).format(DateTimeFormatter.ISO_DATE)
        }
        val weekRecs = store.recordsOfDays(weekDays)
        val week = JSONArray()
        weekDays.forEach { ds ->
            val r = weekRecs[ds] ?: emptyMap()
            val d = LocalDate.parse(ds)
            val statuses = JSONObject()
            var done = 0
            var active = 0
            r.forEach { (k, v) ->
                statuses.put(k, v.status)
                // idle = 该服务当天只刷了余额、没签到，不算漏签也不计入分母
                if (v.status != "idle") {
                    active++
                    if (v.status == "success" || v.status == "already") done++
                }
            }
            week.put(JSONObject().apply {
                put("day", ds)
                put("weekday", "日一二三四五六"[(d.dayOfWeek.value % 7)])
                put("done", done)
                put("total", active)
                put("statuses", statuses)
                put("ok", active > 0 && done == active)
            })
        }

        val st = store.statsAll()
        return JSONObject().apply {
            put("day", day)
            put("providers", rows)
            // N2（BUGS-v2.1）：把「凭据解密失败」与「本机加密是否健康」透出给前端。
            //
            // 只做 P1-1 的「不覆盖密文」是不够的：数据虽然保住了，但界面依旧显示
            // 「未配置」，用户完全不知道密钥已经变了、需要重新登录。必须有一条
            // 显式信号让 app.js 能弹出提示横幅。
            put("creds_lost", s.credsLost)
            put("crypto_ok", SecretBox.healthy())
            put("scheduler", JSONObject().apply {
                put("enabled", c.schedulerEnabled)
                put("times", JSONArray().put(c.schedulerTime))
                put("jitter_min", c.schedulerJitter)
                put("timezone_offset_hours", 8)
            })
            put("assets", assetsForCard())
            put("notify", JSONObject().apply {
                val n = Notify.load(ctx)
                put("enabled", n.enabled)
                put("kind", n.kind)
                put("configured", n.url.isNotBlank())
            })
            put("stats", st)
            put("week", week)
            put("streak", bestStreak(recs))
            put("local_mode", true)
        }.toString()
    }

    /**
     * 积分资产卡：WorkBuddy 余额 + 将过期积分。
     * 只读缓存，缓存过期时由前端显式 /api/wb_assets?refresh=1 触发真实查询，
     * 避免每次刷新首页都打三次上游接口。
     */
    private fun assetsForCard(): JSONObject {
        val acc = wbAccount() ?: return JSONObject().put("ok", false).put("error", "未配置 WorkBuddy")
        val cached = WbAssets.loadCache(ctx, "card_${acc.uid}")
            ?: return JSONObject().put("ok", false).put("pending", true)
        return cached
    }

    private fun providerRow(
        key: String, label: String, mode: String, ready: Boolean,
        issues: List<String>, rec: HistoryStore.Rec?, day: String,
        acctCount: Int = 0, accts: List<AcctRef> = emptyList()
    ): JSONObject {
        // 多账号明细（用户 m08264 第 ① 条）：首页同一服务有 N 个账号时要出 N 张卡并堆叠。
        // 主账号（account_key == "default"）恒排第一 —— 它就是牌堆的头卡。
        val acctRows = try {
            store.recordsOfProvider(day, key)
        } catch (e: Exception) {
            emptyList()
        }.sortedBy { if (it.accountKey == HistoryStore.DEFAULT_ACCOUNT) 0 else 1 }
        val byKey = acctRows.associateBy { it.accountKey }
        val accounts = JSONArray()

        // 以「已配置的账号列表」为准（用户 m16298）：刚登录、今天还没签的账号也要立刻有卡，
        // 不能等签到落库才出现。记录只用来填状态和积分。
        if (accts.isNotEmpty()) {
            accts.forEachIndexed { i, a ->
                val r = byKey[a.key]
                accounts.put(JSONObject().apply {
                    put("account_key", a.key)
                    // 昵称优先，取不到才退回「账号 N」（用户 m16091）
                    put("label", a.label.ifBlank { "账号 " + (i + 1) })
                    if (a.sub.isNotBlank()) put("sub", a.sub)
                    put("status", r?.status ?: "idle")
                    put("message", r?.message ?: "今日尚未执行")
                    put("points", r?.points ?: "")
                    put("total_points",
                        if (r != null && r.totalPoints >= 0) r.totalPoints else JSONObject.NULL)
                    put("used_points",
                        if (r != null && r.usedPoints >= 0) r.usedPoints else JSONObject.NULL)
                })
            }
        } else if (acctRows.isNotEmpty()) {
            acctRows.forEachIndexed { i, r ->
                accounts.put(JSONObject().apply {
                    put("account_key", r.accountKey)
                    // 头卡也标「账号 1」，与后面几张完全一致，只靠编号区分（用户 m08666）
                    put("label", "账号 " + (i + 1))
                    put("status", r.status)
                    put("message", r.message)
                    put("points", r.points)
                    put("total_points", if (r.totalPoints >= 0) r.totalPoints else JSONObject.NULL)
                    put("used_points", if (r.usedPoints >= 0) r.usedPoints else JSONObject.NULL)
                })
            }
        } else if (acctCount > 0) {
            // 今天还没签过、又拿不到账号列表：先按「待签」占位，保证多账号也能立刻看到 N 张卡
            for (i in 0 until acctCount) {
                accounts.put(JSONObject().apply {
                    put("account_key", if (i == 0) HistoryStore.DEFAULT_ACCOUNT else "pending_" + i)
                    put("label", "账号 " + (i + 1))
                    put("status", "idle")
                    put("message", "今日尚未执行")
                    put("points", "")
                    put("total_points", JSONObject.NULL)
                    put("used_points", JSONObject.NULL)
                })
            }
        }
        val msg = when {
            rec != null && rec.status == "fail" && !ready -> "未配置：请到设置填写后重试"
            rec != null -> rec.message
            // 重置凭据后当天记录已删、凭据也没了：这里必须直说「未配置」，
            // 否则卡片写着「今日尚未执行」，看起来像还能签（用户 m06802）。
            !ready -> "未配置：请到设置填写后重试"
            else -> "今日尚未执行"
        }
        // 账号持有积分：优先当天签到结果；否则用上次缓存的读数（跨天仍可显示）
        //
        // 用户 m13804：只展示**主账号**的数，多账号不求和。
        // 原来这里取 rec.totalPoints（recordsOf 取的「最差那条」）或 cachedTotal（求和值），
        // 多账号时会显示成「2 个号相加」，既跟资产卡对不上，也跟刷新提示对不上。
        val cached = cachedTotal(key)
        // 「主账号那行」的定位（用户 m18105）：
        // 先按 accts 的第一个（构造时主账号恒排第一，见 status() 的 wbAcctRefs / xxxList()），
        // 再退回 DEFAULT_ACCOUNT。只按 "default" 找是不够的 —— WorkBuddy 主账号的
        // account_key 是 uid（见 wbAccountKey），永远不等于 "default"，
        // 于是这里会落到 acctRows.firstOrNull()，多账号时可能取到别人的读数。
        val mainKey = accts.firstOrNull()?.key
        val mainRow = (mainKey?.let { k -> acctRows.firstOrNull { it.accountKey == k } })
            ?: acctRows.firstOrNull { it.accountKey == HistoryStore.DEFAULT_ACCOUNT }
            ?: acctRows.firstOrNull()
        val total = when {
            mainRow != null && mainRow.totalPoints >= 0 -> mainRow.totalPoints
            rec != null && rec.totalPoints >= 0 -> rec.totalPoints
            cached >= 0 -> cached
            else -> -1
        }
        // 只有「拿不到账号维度」时才用 rec 回写缓存（用户 m18105）：
        // rec 是 recordsOf() 取的「最差那条」，多账号时它可能属于任意一个账号，
        // 拿它回写会把主账号的缓存值覆盖成别人的读数。
        if (accts.isEmpty() && rec != null && rec.totalPoints >= 0 && rec.totalPoints != cached) {
            cacheTotal(key, rec.totalPoints)
        }
        return JSONObject().apply {
            put("key", key); put("label", label)
            put("enabled", true); put("mode", mode)
            put("dry_run", false)
            put("ready", ready)
            put("issues", JSONArray(issues))
            put("status", rec?.status ?: "idle")
            put("message", msg)
            put("points", rec?.points ?: "")
            put("time", rec?.time ?: "")
            if (total >= 0) put("total_points", total) else put("total_points", JSONObject.NULL)
            // 累计已用：资产卡翻面读这个（-1/缺省 = 未知，前端显示「—」）
            // 与 total_points 同口径：多账号时只取主账号那一个，不做求和（用户 m13804）
            val used = when {
                mainRow != null && mainRow.usedPoints >= 0 -> mainRow.usedPoints
                rec != null && rec.usedPoints >= 0 -> rec.usedPoints
                else -> cachedUsed(key)
            }
            if (used >= 0) put("used_points", used) else put("used_points", JSONObject.NULL)
            // 上游自己给的连续天数：有就透出，前端优先用它（本地 streak 只算「三服务全签」）
            //
            // P3-4：多账号时 `rec` 是 recordsOf() 取的「最差那条」，直接用它会把
            // 失败账号的天数（常见是 0）当成整家的连续天数。改成对 accounts 取最大值。
            val sd = if (acctRows.isNotEmpty()) {
                acctRows.map { it.streakDays }.filter { it >= 0 }.maxOrNull() ?: -1
            } else {
                rec?.streakDays ?: -1
            }
            if (sd >= 0) put("streak_days", sd) else put("streak_days", JSONObject.NULL)
            put("accounts", accounts)
        }
    }

    /**
     * 首页顶部的连续天数：优先取上游真实天数（WB 的 streak_days），
     * 没有才回退本地「每天都签」的统计。
     */
    private fun bestStreak(recs: Map<String, HistoryStore.Rec>): Int {
        val fromUpstream = recs.values.mapNotNull { r -> r.streakDays.takeIf { it > 0 } }.maxOrNull()
        if (fromUpstream != null) return fromUpstream
        return store.streak()
    }

    // ── 账号持有积分缓存（SharedPreferences，per provider） ────────────
    /**
     * 缓存版本：口径一改就 +1，旧缓存自动作废。
     * v2 = 从「活动累计发放 total_credits」改成「余额接口逐包 min() 求和」，
     * 旧版存下的 2200 这类虚高值必须丢掉，否则首页一直显示假数字。
     */
    private val TOTAL_CACHE_VER = 2

    /**
     * 积分补数的失败退避（P2-4）：上游持续失败时，cachedTotal 永远是 -1，
     * /api/status 每 25 秒轮询一次就会每 25 秒起一个新线程打上游。
     * 失败后 5 分钟内不再重试。
     */
    private val BACKFILL_BACKOFF_MS = 5 * 60 * 1000L
    @Volatile private var wbBackfilling = false
    @Volatile private var wbBackfillFailAt = 0L
    @Volatile private var traeBackfilling = false
    @Volatile private var traeBackfillFailAt = 0L

    private fun cachedTotal(key: String): Int {
        val p = ctx.getSharedPreferences("signhub_totals", Context.MODE_PRIVATE)
        if (p.getInt("__ver", 0) != TOTAL_CACHE_VER) return -1
        return p.getInt(key, -1)
    }

    private fun cacheTotal(key: String, v: Int) {
        ctx.getSharedPreferences("signhub_totals", Context.MODE_PRIVATE).edit()
            .putInt(key, v).putInt("__ver", TOTAL_CACHE_VER).apply()
    }

    /** 重置凭据时把该家的余额/已用缓存清掉，否则首页卡片继续显示上一个账号的数字。 */
    private fun clearCachedTotal(key: String) {
        ctx.getSharedPreferences("signhub_totals", Context.MODE_PRIVATE).edit()
            .remove(key).putInt("__ver", TOTAL_CACHE_VER).apply()
        ctx.getSharedPreferences("signhub_used", Context.MODE_PRIVATE).edit()
            .remove(key).apply()
    }

    /** 累计已用缓存：与 total 分开一个文件，口径升级不必互相作废。 */
    private fun cachedUsed(key: String): Int =
        ctx.getSharedPreferences("signhub_used", Context.MODE_PRIVATE).getInt(key, -1)

    private fun cacheUsed(key: String, v: Int) {
        if (v < 0) return
        ctx.getSharedPreferences("signhub_used", Context.MODE_PRIVATE).edit()
            .putInt(key, v).apply()
    }

    /**
     * 迁移旧口径缓存：把 __ver 升到当前版本并清空所有 provider 的旧值，
     * 让它们在下次 status() 时走 backfillWbTotal 重新按余额口径取数。
     */
    private fun migrateTotalCache() {
        val p = ctx.getSharedPreferences("signhub_totals", Context.MODE_PRIVATE)
        if (p.getInt("__ver", 0) == TOTAL_CACHE_VER) return
        p.edit().clear().putInt("__ver", TOTAL_CACHE_VER).apply()
    }

    /**
     * 积分兜底补数：老版本写下的记录里 total_points 是 -1（字段名对不上），
     * 首页就会一直空着。这里在后台问一次上游把值补进缓存，不动今天的签到记录。
     *
     * P2-4（BUGS-v1.63）：必须加「进行中」标记 + 失败退避。原来只要
     * `cachedTotal("workbuddy") < 0` 就起线程 —— 上游持续失败时这个值永远是 -1，
     * 而 /api/status 每 25 秒被轮询一次，于是每 25 秒就多一个线程打上游，越堆越多。
     */
    private fun backfillWbTotal(s: AppSettings) {
        if (wbBackfilling) return
        if (System.currentTimeMillis() - wbBackfillFailAt < BACKFILL_BACKOFF_MS) return
        wbBackfilling = true
        Thread {
            try {
                var total = -1
                if (s.wbToken.isNotBlank()) {
                    total = WbApi.fetchTotal(
                        WbAccount(
                            id = HistoryStore.DEFAULT_ACCOUNT, uid = s.wbUid, accessToken = s.wbToken,
                            domain = s.wbDomain, enterpriseId = s.wbEnterpriseId,
                            variant = if (s.wbGlobal) "ai" else "cn"
                        )
                    )
                }
                // 缓存口径是「主账号自己」的读数（用户 m13804 / m18105）：
                // 主账号读不到时才退回多账号求和，且绝不拿别的账号的数当主账号的数。
                if (total < 0) {
                    total = WbAccountStore.all(ctx)
                        .filter { it.accessToken.isNotBlank() }
                        .map { a -> a.totalPoints.takeIf { it >= 0 } ?: WbApi.fetchTotal(a) }
                        .filter { it >= 0 }
                        .sum()
                        .takeIf { it > 0 } ?: -1
                }
                if (total >= 0) {
                    cacheTotal("workbuddy", total)
                    wbBackfillFailAt = 0L
                } else {
                    wbBackfillFailAt = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                wbBackfillFailAt = System.currentTimeMillis()
            } finally {
                wbBackfilling = false
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Trae 积分兜底补数：Trae 的持有积分要单独查 `ide_user_ent_usage`，
     * 从没签到过 / 签到记录里读不到时会一直是 -1，首页就空着。
     */
    /**
     * 后台补一次 Trae 持有积分。
     *
     * 必须走 traeHeldAuto：只拿已保存的 token 去查不行 —— 那枚 JWT 8 小时就过期，
     * 过期后接口直接 401，补数永远失败，界面就一直是「积分稍后刷新」。
     * 换到的新 token 就地落盘，否则下一轮还是拿旧的撞 401。
     */
    private fun backfillTraeTotal(s: AppSettings) {
        if (traeBackfilling) return
        if (System.currentTimeMillis() - traeBackfillFailAt < BACKFILL_BACKOFF_MS) return
        traeBackfilling = true
        Thread {
            try {
                var renewed: String? = null
                val v = runBlocking {
                    LocalCheckin.traeHeldAuto(
                        s.traeToken, s.traeDeviceId, s.traeGlobal, s.traeSession
                    ) { fresh -> renewed = fresh }
                }
                if (v >= 0) {
                    cacheTotal("traesolo", v)
                    traeBackfillFailAt = 0L
                } else {
                    traeBackfillFailAt = System.currentTimeMillis()
                }
                renewed?.let { fresh ->
                    runCatching {
                        runBlocking { SettingsStore.save(ctx, s.copy(traeToken = fresh)) }
                    }
                }
            } catch (_: Exception) {
                traeBackfillFailAt = System.currentTimeMillis()
            } finally {
                traeBackfilling = false
            }
        }.apply { isDaemon = true }.start()
    }

    // ── /api/logs ─────────────────────────────────────────────────────
    private fun logs(query: String): String {
        // limit 必须夹取：以前 ?limit=999999 会把整张表拉出来再序列化，
        // 记录攒到几千条时这一下就是几十 MB 的字符串，直接 OOM。
        val raw = Regex("limit=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 60
        val limit = raw.coerceIn(1, 500)
        // provider 名里可能有数字和下划线（traesolo / workbuddy 目前没有，但别写死假设）
        val provider = Regex("provider=([a-z0-9_]+)").find(query)?.groupValues?.get(1)
        val arr = JSONArray()
        store.recentLogs(limit).forEach { r ->
            if (provider != null && r.provider != provider) return@forEach
            arr.put(JSONObject().apply {
                put("id", r.id); put("provider", r.provider); put("day", r.day)
                // 本地库没有存 http_status / detail：以前硬编码 200 和空串，
                // 前端虽然没用这两个字段，但「假的 200」会误导排查。改成 null 表示未知。
                put("status", r.status); put("http_status", JSONObject.NULL)
                put("message", r.message); put("points", r.points)
                put("detail", ""); put("created_at", r.time)
            })
        }
        return JSONObject().put("logs", arr).toString()
    }

    // ── /api/stats ────────────────────────────────────────────────────
    private fun stats(): String {
        val all = store.recentLogs(2000)
        val per = JSONObject()
        val monthDays = JSONObject()
        val month = today().substring(0, 7)
        val seen = mutableSetOf<String>()

        all.forEach { r ->
            val o = per.optJSONObject(r.provider) ?: JSONObject().also { per.put(r.provider, it) }
            o.put(r.status, o.optInt(r.status, 0) + 1)
            if (r.day.startsWith(month) && (r.status == "success" || r.status == "already")) {
                val k = "${r.provider}|${r.day}"
                if (seen.add(k)) monthDays.put(r.provider, monthDays.optInt(r.provider, 0) + 1)
            }
        }
        return JSONObject().apply {
            put("by_provider", per)
            put("month_done_days", monthDays)
            put("month", month)
            put("totals", store.statsAll())
        }.toString()
    }

    /**
     * 凭据掩码：只回传「够认出来是哪一条」的前后几位。
     *
     * 以前 /api/config 把 wbToken / traeToken / traeSession / qoderToken /
     * qoderRefresh / mmToken / notify.secret 原样回传，而代码注释写的是
     * 「这里只回传掩码后的形态做「已配置」提示」—— 注释和实现相反。
     * 虽然本地 WebView 同源、外泄面有限，但只要 WebView 开了远程调试、
     * 或者页面里任何一个 XSS 点，凭据就是明文可读的。
     * 前端所有用到这些字段的地方都只判「非空」（ready / 已配置），
     * 保存时也已经有 contains("***") 跳过逻辑，所以改成掩码不影响功能。
     */
    private fun mask(v: String): String {
        if (v.isBlank()) return ""
        // P2-9（BUGS-v1.63）：原来 length <= 6 时返回纯 "***"，用户不动它直接保存会被
        // saveConfig 的 contains("***") 跳过 —— 界面显示 *** 让人以为已保存，实际库里还是旧值。
        // 现在短值也保留首尾各 1 位，既不至于泄露，又能让用户看出「这是我填的那串」。
        if (v.length <= 6) return v.take(1) + "***" + v.takeLast(1)
        return v.take(3) + "***" + v.takeLast(2)
    }

    // ── /api/config ───────────────────────────────────────────────────
    private fun config(): String {
        val s = settings()
        val c = cfg()
        val providers = JSONObject()

        providers.put("workbuddy", JSONObject().apply {
            put("enabled", true); put("mode", "wb")
            put("wb", JSONObject().apply {
                put("region", if (s.wbGlobal) "global" else "cn")
                put("accessToken", mask(s.wbToken))
                put("uid", s.wbUid)
                put("domain", s.wbDomain)
                put("enterpriseId", s.wbEnterpriseId)
            })
        })

        providers.put("traesolo", JSONObject().apply {
            put("enabled", true); put("mode", "trae")
            put("trae", JSONObject().apply {
                put("region", if (s.traeGlobal) "global" else "cn")
                put("host", "")
                put("token", mask(s.traeToken))
                put("deviceId", s.traeDeviceId)
                put("session", mask(s.traeSession))
            })
        })

        providers.put("qoder", JSONObject().apply {
            // 默认参与签到：凭据缺失时只是「未就绪」，不该显示成「已禁用」
            put("enabled", true)
            put("mode", "qoder")
            put("qoder", JSONObject().apply {
                put("region", s.qoderRegion.ifBlank { "cn" })
                put("token", mask(s.qoderToken))
                put("refreshToken", mask(s.qoderRefresh))
                put("uid", s.qoderUid)
                put("machineId", s.qoderMachineId)
                put("proxy", "")
            })
        })

        providers.put("minimax", JSONObject().apply {
            put("enabled", true)
            put("mode", "minimax")
            put("minimax", JSONObject().apply {
                put("accessToken", mask(s.mmToken))
                put("refreshToken", "")
                put("userId", s.mmUserId)
                put("deviceId", s.mmDeviceId)
                put("uuid", "")
                put("proxy", "")
            })
        })

        providers.put("huawei", JSONObject().apply {
            put("enabled", true)
            put("mode", "huawei")
            put("huawei", JSONObject().apply {
                put("cookie", mask(s.hwCookie))
                // portalCookie 保留字段但恒空：积分已改读签到同域接口，不需要门户会话。
                put("portalCookie", "")
                // V2.79：auth 域 Cookie 存在与否 = 静默重登能否用（前端据此显示
                // 「到期自动续期：已就绪 / 缺 auth 域凭据」）。只回布尔，不回内容。
                put("authCookieReady", s.hwAuthCookie.isNotBlank())
                put("proxy", "")
            })
        })

        // 豆包 V2.75 起搁置（DoubaoWebCheckin.ENABLED=false）：配置里不返回豆包，
        // 前端 knownKeys 过滤后自然不画这张卡。
        if (DoubaoWebCheckin.ENABLED) {
            providers.put("doubao", JSONObject().apply {
                put("enabled", true)
                put("mode", "doubao")
                put("doubao", JSONObject().apply {
                    // 整串 Cookie 是凭据，一律掩码；saveConfig 见到 *** 会跳过，不会把掩码存回去
                    put("cookie", mask(s.dbCookie))
                    put("scene", s.dbScene)
                    put("proxy", "")
                })
            })
        }

        // 智谱清言（V2.76 接入）：纯 OkHttp，签名能本地算，不需要 WebView。
        providers.put("zhipu", JSONObject().apply {
            put("enabled", true)
            put("mode", "zhipu")
            put("zhipu", JSONObject().apply {
                put("cookie", mask(s.zpCookie))
                put("deviceId", s.zpDeviceId)
                put("proxy", "")
            })
        })

        // 百度智能云 / 百度搭子 DuMate（V2.76 接入）：Cookie + csrftoken，纯 OkHttp。
        providers.put("qianfan", JSONObject().apply {
            put("enabled", true)
            put("mode", "qianfan")
            put("qianfan", JSONObject().apply {
                put("cookie", mask(s.qfCookie))
                put("proxy", "")
            })
        })

        return JSONObject().apply {
            put("providers", providers)
            put("notify", JSONObject().apply {
                val n = Notify.load(ctx)
                put("enabled", n.enabled)
                put("kind", n.kind)
                // webhook_url 保持明文：设置页要把整条 URL 显示在输入框里供用户编辑
                // （app.js:2527 直接赋值），掩码后会变成一串星号没法改。它本身含 token，
                // 但这是「可编辑的配置项」，风险与凭据不同，单独保留。
                put("webhook_url", n.url)
                // 密钥一律只回传掩码；saveConfig 见到 *** 会跳过，不会把掩码存回去
                put("secret", mask(n.secret))
                put("has_url", n.url.isNotBlank())
            })
            put("server", JSONObject().apply {
                put("api_token", ""); put("api_token_set", false); put("local_mode", true)
                // N2（BUGS-v2.1）：设置页读这两个字段决定是否弹「请重新填写凭据」横幅
                put("creds_lost", s.credsLost)
                put("crypto_ok", SecretBox.healthy())
            })
            put("scheduler", JSONObject().apply {
                put("enabled", c.schedulerEnabled)
                put("times", JSONArray().put(c.schedulerTime))
                put("jitter_min", c.schedulerJitter)
                put("timezone_offset_hours", 8)
            })
        }.toString()
    }

    /** 网页版保存配置时回写本地凭据。 */
    private fun saveConfig(body: String): String {
        val o = try { JSONObject(body) } catch (e: Exception) { return """{"ok":false}""" }
        val s = settings()
        var ns = s

        o.optJSONObject("providers")?.let { ps ->
            ps.optJSONObject("workbuddy")?.optJSONObject("wb")?.let { wb ->
                ns = ns.copy(
                    wbGlobal = wb.optString("region", "cn") == "global",
                    wbUid = wb.optString("uid", ns.wbUid),
                    wbDomain = wb.optString("domain", ns.wbDomain),
                    wbEnterpriseId = wb.optString("enterpriseId", ns.wbEnterpriseId),
                )
                val t = wb.optString("accessToken", "")
                if (t.isNotBlank() && !t.contains("***")) ns = ns.copy(wbToken = t)
            }
            ps.optJSONObject("traesolo")?.optJSONObject("trae")?.let { tr ->
                ns = ns.copy(traeGlobal = tr.optString("region", "cn") == "global")
                val t = tr.optString("token", "")
                if (t.isNotBlank() && !t.contains("***")) ns = ns.copy(traeToken = t)
                val d = tr.optString("deviceId", "")
                if (d.isNotBlank() && !d.contains("***")) ns = ns.copy(traeDeviceId = d)
                val sess = tr.optString("session", "")
                if (sess.isNotBlank() && !sess.contains("***")) ns = ns.copy(traeSession = sess)
            }

            ps.optJSONObject("qoder")?.optJSONObject("qoder")?.let { qd ->
                fun pick(k: String, cur: String): String {
                    val v = qd.optString(k, "")
                    return if (v.isNotBlank() && !v.contains("***")) v else cur
                }
                ns = ns.copy(
                    qoderRegion = qd.optString("region", "").ifBlank { ns.qoderRegion },
                    qoderToken = pick("token", ns.qoderToken),
                    qoderRefresh = pick("refreshToken", ns.qoderRefresh),
                    qoderUid = pick("uid", ns.qoderUid),
                    qoderMachineId = pick("machineId", ns.qoderMachineId),
                )
            }

            ps.optJSONObject("minimax")?.optJSONObject("minimax")?.let { mm ->
                fun pick(k: String, cur: String): String {
                    val v = mm.optString(k, "")
                    return if (v.isNotBlank() && !v.contains("***")) v else cur
                }
                ns = ns.copy(
                    mmToken = pick("accessToken", ns.mmToken),
                    mmUserId = pick("userId", ns.mmUserId),
                    mmDeviceId = pick("deviceId", ns.mmDeviceId),
                )
            }

            ps.optJSONObject("huawei")?.optJSONObject("huawei")?.let { hw ->
                fun pick(k: String, cur: String): String {
                    val v = hw.optString(k, "")
                    return if (v.isNotBlank() && !v.contains("***")) v else cur
                }
                ns = ns.copy(
                    hwCookie = pick("cookie", ns.hwCookie),
                    hwPortalCookie = pick("portalCookie", ns.hwPortalCookie),
                )
            }

            if (DoubaoWebCheckin.ENABLED) {
                ps.optJSONObject("doubao")?.optJSONObject("doubao")?.let { db ->
                    fun pick(k: String, cur: String): String {
                        val v = db.optString(k, "")
                        return if (v.isNotBlank() && !v.contains("***")) v else cur
                    }
                    ns = ns.copy(
                        dbCookie = pick("cookie", ns.dbCookie),
                        dbScene = pick("scene", ns.dbScene),
                    )
                }
            }

            ps.optJSONObject("zhipu")?.optJSONObject("zhipu")?.let { zp ->
                fun pick(k: String, cur: String): String {
                    val v = zp.optString(k, "")
                    return if (v.isNotBlank() && !v.contains("***")) v else cur
                }
                ns = ns.copy(
                    zpCookie = pick("cookie", ns.zpCookie),
                    zpDeviceId = pick("deviceId", ns.zpDeviceId),
                )
            }

            ps.optJSONObject("qianfan")?.optJSONObject("qianfan")?.let { qf ->
                fun pick(k: String, cur: String): String {
                    val v = qf.optString(k, "")
                    return if (v.isNotBlank() && !v.contains("***")) v else cur
                }
                ns = ns.copy(qfCookie = pick("cookie", ns.qfCookie))
            }
        }

        o.optJSONObject("scheduler")?.let { sc ->
            val times = sc.optJSONArray("times")
            val cur = cfg()
            val t0 = if (times != null && times.length() > 0) times.getString(0) else cur.schedulerTime
            val enabled = sc.optBoolean("enabled", true)
            val jitter = sc.optInt("jitter_min", cur.schedulerJitter).coerceIn(0, 240)
            LocalConfig.save(ctx, LocalConfig(enabled, t0, jitter))
            // 关键：网页上点「保存定时」时真正排上后台自动签到
            AutoCheckinScheduler.schedule(ctx, enabled, t0, jitter)
        }

        o.optJSONObject("notify")?.let { n ->
            val cur = Notify.load(ctx)
            val url = n.optString("webhook_url", "")
            val sec = n.optString("secret", "")
            Notify.save(ctx, Notify.Cfg(
                enabled = n.optBoolean("enabled", cur.enabled),
                kind = Notify.normalize(n.optString("kind", cur.kind)),
                // 掩码值（含 ***）不回写，避免把「已配置」覆盖成星号
                url = if (url.isNotBlank() && !url.contains("***")) url else cur.url,
                secret = if (sec.isNotBlank() && !sec.contains("***")) sec else cur.secret,
                // P1R1（BUGS-v2.2）：必须把 secretLost 透传下去。
                // 少了这一行，Notify.save() 里 `!(c.secretLost && c.secret.isEmpty())`
                // 守卫因为 secretLost 恒为默认 false 而永远成立 —— 用户只要在设置页
                // 改个 webhook 地址（不动密钥框），空串就会把磁盘上完好的密文覆盖掉。
                secretLost = cur.secretLost,
            ))
        }

        runBlocking { SettingsStore.save(ctx, ns) }
        return """{"ok":true}"""
    }

    /**
     * 重置某家 provider 的登录凭据（用户 m21385）。
     *
     * 为什么要单开一个接口：前端一直用「掩码占位」表达「已保存」（`dt-***lU`），
     * 而 `saveConfig` 里所有 `pick()` 都跳过含 `***` 的值 —— 于是用户把输入框
     * 清空再点保存，等于「什么都不改」，凭据永远清不掉，换号只能卸载重装。
     *
     * 只清凭据，保留 region / mode / proxy / deviceId 之类「怎么连」的偏好：
     * 清掉 region 会让国际版账号下次连到国内域名，症状是 401 而不是「未配置」。
     */
    private fun resetCreds(body: String): String {
        val key = try { JSONObject(body).optString("key", "") } catch (e: Exception) { "" }
        if (key.isBlank()) return """{"ok":false,"error":"missing key"}"""
        val s = settings()
        var ns = s
        val cleared = mutableListOf<String>()
        fun had(v: String): Boolean = v.isNotBlank()

        when (key) {
            "workbuddy" -> {
                if (had(s.wbToken)) cleared.add("wb.accessToken")
                if (had(s.wbRefreshToken)) cleared.add("wb.refreshToken")
                ns = ns.copy(wbToken = "", wbRefreshToken = "", wbUid = "", wbEnterpriseId = "", wbDomain = "")
            }
            "traesolo" -> {
                if (had(s.traeToken)) cleared.add("trae.token")
                if (had(s.traeSession)) cleared.add("trae.session")
                if (had(s.traeDeviceId)) cleared.add("trae.deviceId")
                ns = ns.copy(traeToken = "", traeSession = "", traeDeviceId = "")
            }
            "qoder" -> {
                if (had(s.qoderToken)) cleared.add("qoder.token")
                if (had(s.qoderRefresh)) cleared.add("qoder.refreshToken")
                ns = ns.copy(qoderToken = "", qoderRefresh = "", qoderUid = "", qoderMachineId = "")
            }
            "minimax" -> {
                if (had(s.mmToken)) cleared.add("minimax.accessToken")
                ns = ns.copy(mmToken = "", mmUserId = "", mmDeviceId = "")
            }
            "huawei" -> {
                if (had(s.hwCookie)) cleared.add("huawei.cookie")
                if (had(s.hwAuthCookie)) cleared.add("huawei.authCookie")
                // V2.79：auth 域 Cookie 也必须一起清 —— 它是「静默重登」的钥匙，
                // 留着等于重置后还能拿旧凭据自动续期，用户以为已经退干净了。
                // 「今天已签」与 probe 结论只清主账号那一桶（按旧 Cookie 算 key），
                // 不能 clearAll —— 那会把多账号的结论一起抹掉。
                runCatching { HwTodayMark.clear(ctx, s.hwCookie) }
                runCatching { HwVerified.clear(ctx, s.hwCookie) }
                ns = ns.copy(hwCookie = "", hwAuthCookie = "", hwPortalCookie = "")
            }
            "doubao" -> {
                if (had(s.dbCookie)) cleared.add("doubao.cookie")
                ns = ns.copy(dbCookie = "", dbScene = "")
            }
            "zhipu" -> {
                if (had(s.zpCookie)) cleared.add("zhipu.cookie")
                ns = ns.copy(zpCookie = "")
            }
            "qianfan" -> {
                if (had(s.qfCookie)) cleared.add("qianfan.cookie")
                ns = ns.copy(qfCookie = "")
            }
            else -> return """{"ok":false,"error":"unknown provider:$key"}"""
        }
        // 积分缓存也要清，否则首页卡片继续显示上一个账号的数字。
        runCatching { clearCachedTotal(key) }
        // 当天的主账号记录也必须删（用户 m06802）：providerRow 在没有账号列表时会
        // 拿当天历史记录反推卡片，只清凭据不删记录，那张卡会带着旧状态继续画在首页
        // （「已签到 773」），用户点完「重置凭据」看到卡片还在，只能以为没生效。
        //
        // 只删主账号那一条 —— 多账号不受重置影响（用户 m06802 明确要求）。
        // 主账号的 account_key 不一定是 "default"：WorkBuddy 用的是 uid（wbAccountKey）。
        val mainAcctKey = if (key == "workbuddy") wbAccountKey(s) else HistoryStore.DEFAULT_ACCOUNT
        val deleted = runCatching {
            store.deleteAccount(today(), key, mainAcctKey)
        }.getOrDefault(0)
        runBlocking { SettingsStore.save(ctx, ns) }
        val arr = JSONArray()
        cleared.forEach { arr.put(it) }
        return JSONObject().apply {
            put("ok", true); put("key", key); put("cleared", arr); put("deleted", deleted)
        }.toString()
    }

    // ── /api/doctor ───────────────────────────────────────────────────
    private fun doctor(): String {
        val s = settings()
        val arr = JSONArray()
        val wbAccCount = try {
            WbAccountStore.all(ctx).count { it.accessToken.isNotBlank() }
        } catch (_: Exception) { 0 }
        val wbIssues = JSONArray()
        if (s.wbToken.isBlank() && wbAccCount == 0) wbIssues.put("缺少 accessToken")
        arr.put(JSONObject().apply {
            put("key", "workbuddy"); put("mode", "wb")
            put("ready", s.wbToken.isNotBlank() || wbAccCount > 0); put("issues", wbIssues)
        })
        val trIssues = JSONArray()
        if (s.traeToken.isBlank() && s.traeSession.isBlank()) trIssues.put("缺少凭据：token 或 session")
        // 设备号缺失不算问题（V2.86）：LocalCheckin.trae() 内部会用 DeviceId.derive() 派生并落盘。
        // 以前这里既报 issue 又参与 ready，导致「界面显示未配置、定时任务根本不签」。
        if (s.traeDeviceId.isBlank()) trIssues.put("设备号留空，签到时自动派生")
        arr.put(JSONObject().apply {
            put("key", "traesolo"); put("mode", "trae")
            put("ready", s.traeToken.isNotBlank() || s.traeSession.isNotBlank())
            put("issues", trIssues)
        })
        val qdIssues = JSONArray()
        if (s.qoderToken.isBlank() && s.qoderRefresh.isBlank())
            qdIssues.put("缺少凭据：dt- 的 accessToken 或 drt- 的 refreshToken")
        else if (s.qoderToken.isBlank()) qdIssues.put("只用 refreshToken（会自动续期）")
        else if (s.qoderMachineId.isBlank()) qdIssues.put("未填设备指纹（选填，不影响签到）")
        arr.put(JSONObject().apply {
            put("key", "qoder"); put("mode", "qoder")
            // 设备指纹不是鉴权项（实测不带 Cosy-MachineId 也 200）；refreshToken 单独可用
            put("ready", s.qoderToken.isNotBlank() || s.qoderRefresh.isNotBlank())
            put("issues", qdIssues)
        })
        val mmIssues = JSONArray()
        if (s.mmToken.isBlank()) mmIssues.put("缺少 accessToken（mmoat_ 开头）")
        else if (s.mmUserId.isBlank()) mmIssues.put("未填 user_id（选填，不影响签到）")
        arr.put(JSONObject().apply {
            put("key", "minimax"); put("mode", "minimax")
            // user_id / device_id 不参与鉴权（实测 user_id=0 也 200），只判 token
            put("ready", s.mmToken.isNotBlank())
            put("issues", mmIssues)
        })
        return JSONObject().put("providers", arr).toString()
    }

    // ── /api/trae_probe 只读体检（纯本地凭据自检，不发网络请求）──────────
    /**
     * 配置页 Trae 卡片「检查凭据」按钮的数据源。
     *
     * 字段契约来自 `backend/web/static/app.js` 的 `.f-trae-probe` 处理器，
     * 少一个字段前端就少显示一行，所以这里按前端逐字段补齐：
     * `session_ok` / `session_len`（session 能否自动续期）、
     * `token_ok` / `token_len` / `token_exp`（JWT 的 exp，秒级，-1 表示解不开）、
     * `device_id` / `device_from`（设备号来源）。
     *
     * 注意：本函数**不联网**，只读 DataStore 里的本地凭据。
     */
    private fun traeProbe(): String {
        val s = settings()
        val tok = s.traeToken
        val sess = s.traeSession
        val dev = s.traeDeviceId
        val exp = if (tok.isNotBlank()) LocalCheckin.jwtExpiresAt(tok) else -1L
        return JSONObject().apply {
            put("session_ok", sess.isNotBlank())
            put("session_len", sess.length)
            put("token_ok", tok.isNotBlank())
            put("token_len", tok.length)
            put("token_exp", exp)
            put("device_id", dev)
            put("device_from", if (dev.isNotBlank()) "设置" else "")
        }.toString()
    }

    // ── /api/probe/<key> 只读体检（纯本地凭据自检，不发网络请求）─────────
    private fun probe(key: String): String {
        val s = settings()
        return when (key) {
            "qoder" -> JSONObject().apply {
                val ok = s.qoderToken.isNotBlank() || s.qoderRefresh.isNotBlank()
                put("reachable", ok)
                put("auth_ok", ok)
                put("region", s.qoderRegion.ifBlank { "cn" })
                put("uid", s.qoderUid)
                put("machine_id", s.qoderMachineId)
                put("refresh_ok", s.qoderRefresh.isNotBlank())
                put("issues", JSONArray().apply {
                    if (!ok) put("缺少凭据：dt- 的 accessToken 或 drt- 的 refreshToken")
                    else if (s.qoderToken.isBlank()) put("只用 refreshToken（会自动续期）")
                    else if (s.qoderMachineId.isBlank()) put("未填设备指纹（选填，不影响签到）")
                })
            }
            "minimax" -> JSONObject().apply {
                put("reachable", s.mmToken.isNotBlank())
                put("auth_ok", s.mmToken.isNotBlank())
                put("user_id", s.mmUserId)
                put("device_id", s.mmDeviceId)
                put("issues", JSONArray().apply {
                    if (s.mmToken.isBlank()) put("缺少 accessToken（mmoat_ 开头）")
                    if (s.mmUserId.isBlank()) put("未填 user_id（选填）")
                })
            }
            "huawei" -> JSONObject().apply {
                val accs = hwList(s)
                val main = accs.firstOrNull { it.id == HistoryStore.DEFAULT_ACCOUNT } ?: accs.firstOrNull()
                val ck = main?.cookie ?: s.hwCookie
                // P2（BUGS-v2.46）：这里刻意**不**真打 hwProbe —— 探测接口会被前端
                // 的「测试」按钮与部分轮询触发，每次都打上游会把华为会话耗得更快。
                // 用 hwUsable 读「会话名 + 上次验证结论」，与首页就绪口径一致。
                val ok = hwUsable(ck)
                put("reachable", ok)
                put("auth_ok", ok)
                put("has_cftk", LocalCheckin.hwHasCftk(ck))
                // V2.79：静默重登是否可用 —— auth 域凭据在就能在会话失效后自动换新，
                // 不用用户重输手机号。空则只能手动重登。
                put("auto_renew", s.hwAuthCookie.isNotBlank() || accs.any { it.authCookie.isNotBlank() })
                put("accounts", accs.size)
                // 积分接口与签到同域（/chat/snap-manager/v1/statistics/plugin），
                // 用同一份 cookie —— 会话可用即积分可读。
                put("portal_ok", ok)
                put("issues", JSONArray().apply {
                    // 与 status() 的 hwIssues 同分支（BUGS-v2.48 P3-3）：`ok=false` 有两种原因 ——
                    // 根本没有 Cookie，或有 Cookie 但被 hwProbe 证实是匿名会话。
                    // 早先这里一律说「缺少会话 Cookie」，用户拿着「我明明填了」去看诊断会被带偏。
                    if (accs.isEmpty()) put("缺少华为会话 Cookie：点「登录华为码道」在站内登录一次即可自动采集")
                    else if (!ok) put("这份 Cookie 是未登录状态（华为对未登录访客也发匿名会话）：请重新登录一次")
                    else if (!LocalCheckin.hwHasCftk(ck))
                        put("Cookie 里没有 CSRF 令牌（cftk），领取会被拒 —— 请重新登录一次完整采集")
                    // V2.79：没有 auth 域凭据时明确说清后果，否则用户只会看到「偶尔要重新登录」
                    // 而不知道为什么。这不是错误，是能力缺失，所以排在最后一条。
                    if (s.hwAuthCookie.isBlank() && accs.none { it.authCookie.isNotBlank() })
                        put("没有 auth.huaweicloud.com 的 Cookie：会话过期后无法自动续期，需要重新登录一次")
                })
            }
            "doubao" -> JSONObject().apply {
                // 与华为同理：这里刻意不真打上游（探测按钮会被轮询触发，每次都打会加速耗会话）。
                // 只做本地判据：Cookie 里有没有 sessionid 这一组。
                // V2.75 起豆包搁置，ENABLED=false 时前端不会请求这个 key；这里仍保留实现，
                // 将来改回 true 就能直接用，不需要重写。
                val ck = s.dbCookie
                val ok = ck.isNotBlank() && DoubaoWebCheckin.hasSession(ck)
                put("reachable", ok)
                put("auth_ok", ok)
                put("cookie_len", ck.length)
                put("scene", s.dbScene)
                put("enabled", DoubaoWebCheckin.ENABLED)
                put("issues", JSONArray().apply {
                    if (!DoubaoWebCheckin.ENABLED) put("豆包已搁置（V2.75 起隐藏），不会参与签到")
                    else if (ck.isBlank()) put("缺少豆包 Cookie：点「登录豆包」在站内登录一次即可自动采集")
                    else if (!ok) put("Cookie 里没找到 sessionid / sid_tt / uid_tt / sid_guard：请重新登录一次完整采集")
                    if (s.dbScene.isBlank()) put("还没探测过活动 scene（首次签到时自动试）")
                })
            }
            "zhipu" -> JSONObject().apply {
                // 纯本地判据（不发网络请求，与其它 provider 的 probe 口径一致）。
                // 判据是 ZhipuCheckin.hasSession：refresh token 存在 **且** JWT 里不是游客。
                // 只看「有没有 chatglm_token」会把游客认成已登录（见 ZhipuCheckin 类注释）。
                val ck = s.zpCookie
                val ok = ZhipuCheckin.hasSession(ck)
                put("reachable", ok)
                put("auth_ok", ok)
                put("cookie_len", ck.length)
                put("device_id", ZhipuCheckin.deviceIdOf(ck, s.zpDeviceId))
                put("uid", ZhipuCheckin.uidOf(ck))
                put("issues", JSONArray().apply {
                    if (ck.isBlank()) put("缺少 Cookie：点「登录智谱清言」在站内登录一次即可自动采集")
                    else if (!ok) put("这份 Cookie 是游客态（JWT 里 is_guest=true）：游客也有一套完整 token，请重新登录一次")
                })
            }
            "qianfan" -> JSONObject().apply {
                val ck = s.qfCookie
                val ok = DumateCheckin.hasSession(ck)
                put("reachable", ok)
                put("auth_ok", ok)
                put("cookie_len", ck.length)
                put("csrf_len", DumateCheckin.csrfOf(ck).length)
                put("issues", JSONArray().apply {
                    if (ck.isBlank()) put("缺少 Cookie：点「登录百度智能云」在站内登录一次即可自动采集")
                    else if (!ok) put("Cookie 里没有 BDUSS / bce-user-info：这是游客态，请重新登录一次")
                })
            }
            else -> JSONObject().put("ok", false).put("error", "未知服务")
        }.toString()
    }


    // ── 签到执行 ──────────────────────────────────────────────────────

    /**
     * 今天是不是「所有已就绪的服务都已签完」。
     *
     * 给「一键全部签到」做短路（用户 m09631）：全签完之后再点只提示
     * 「你今天已经完成全部签到啦！」，不再重复打上游接口。
     *
     * 判据与 [status] 里各 provider 的 ready 完全一致（未配置的服务不参与，
     * 否则一个没填凭据的服务会让「今天已完成」永远不成立）。
     */
    private fun allDoneToday(s: AppSettings, day: String): Boolean {
        // P1 N1（BUGS-v2.3）：以前用 store.recordsOf(day) 判「某家签完了吗」，而它是
        // Map<provider, Rec> —— 每个 provider 只留一行（取 rank 最差的那条）。
        // 多账号是「一个账号一行」（UNIQUE(provider, day, account_key)），于是
        // 「3 个账号只签成 1 个」在 recordsOf 眼里和「3 个全签成」完全一样 →
        // allDoneToday() 返回 true → 一键签到直接回 skipped，剩下 2 个账号永远签不上，
        // 而且没有任何入口能触发（功能静默失效，比报错更糟）。
        //
        // 现在改成逐账号比对：取出该 provider 的全部行，断言
        // 「行数 >= 应签账号数」且「全部 success/already」。
        fun allOkOf(key: String, expect: Int): Boolean {
            if (expect <= 0) return true
            val rows = try { store.recordsOfProvider(day, key) } catch (e: Exception) { emptyList() }
            if (rows.size < expect) return false          // 有账号今天压根没跑过
            return rows.all { it.status == "success" || it.status == "already" }
        }

        // 应签账号数必须与真正会发请求的数量一致（见 checkinAll 的 jobs 构造）。
        val wbAccs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
        val wbMain = if (s.wbToken.isNotBlank()) 1 else 0
        val wbMulti = wbAccs.count { it.accessToken.isNotBlank() && !isSameAsMainAccount(it, s) }
        val wbExpect = wbMain + wbMulti
        val traeExpect = traeList(s).size
        val qdExpect = qoderList(s).size
        val mmExpect = mmList(s).size
        // P2（BUGS-v2.46）：应签数与 isReady / checkinAll 用同一个 hwUsable 口径，
        // 三处口径不一致就会出现「isReady 说没配好、allOkOf 又要求 1 条记录」的死结。
        val hwExpect = hwList(s).count { hwUsable(it.cookie) }

        var active = 0
        if (isReady("workbuddy", s)) { active++; if (!allOkOf("workbuddy", wbExpect)) return false }
        if (isReady("traesolo", s)) { active++; if (!allOkOf("traesolo", traeExpect)) return false }
        if (isReady("qoder", s)) { active++; if (!allOkOf("qoder", qdExpect)) return false }
        if (isReady("minimax", s)) { active++; if (!allOkOf("minimax", mmExpect)) return false }
        if (isReady("huawei", s)) { active++; if (!allOkOf("huawei", hwExpect)) return false }
        // 豆包 V2.75 起搁置（DoubaoWebCheckin.ENABLED=false）：不参与「今天全签完」判定，
        // 否则一张永远签不成功的卡会让「今天已完成」永远不成立。
        if (DoubaoWebCheckin.ENABLED && isReady("doubao", s)) { active++; if (!allOkOf("doubao", 1)) return false }
        if (isReady("zhipu", s)) { active++; if (!allOkOf("zhipu", 1)) return false }
        if (isReady("qianfan", s)) { active++; if (!allOkOf("qianfan", 1)) return false }
        return active > 0
    }

    /**
     * 某家「是否已配置到能签」的唯一判据。
     *
     * P3 N8（BUGS-v2.3）：以前 status()、allDoneToday()、checkinAll() 各写一套，
     * 三处一旦漂移就会出现「首页说未就绪、一键签到却去打了接口」这类互相矛盾的表现。
     * 现在统一走这里，status() 的 providerRow(ready = …) 也用它。
     */
    private fun isReady(key: String, s: AppSettings): Boolean = when (key) {
        "workbuddy" -> s.wbToken.isNotBlank() ||
            (try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() })
                .any { it.accessToken.isNotBlank() }
        "traesolo" -> traeList(s).any { it.token.isNotBlank() || it.session.isNotBlank() }
        "qoder" -> qoderList(s).any { it.token.isNotBlank() || it.refresh.isNotBlank() }
        "minimax" -> mmList(s).any { it.token.isNotBlank() }
        "huawei" -> hwList(s).any { hwUsable(it.cookie) }
        // V2.75 起豆包搁置：ENABLED=false 时一律不算就绪，任何调用方都跳过它。
        "doubao" -> DoubaoWebCheckin.ENABLED &&
            s.dbCookie.isNotBlank() && DoubaoWebCheckin.hasSession(s.dbCookie)
        "zhipu" -> s.zpCookie.isNotBlank() && ZhipuCheckin.hasSession(s.zpCookie)
        "qianfan" -> s.qfCookie.isNotBlank() && DumateCheckin.hasSession(s.qfCookie)
        else -> false
    }

    private fun checkinAll(): String {
        val s = settings()
        val day = today()
        // 今天已经全签完：直接回提示，不重复打上游（用户 m09631）。
        // 前端 app.js 收到 skipped 只弹提示、不动卡片。
        if (allDoneToday(s, day)) {
            val msg = "你今天已经完成全部签到啦！"
            return JSONObject().apply {
                put("trigger", "local")
                put("skipped", true)
                put("done", true)
                put("message", msg)
                put("results", JSONObject())
                put("summary", msg)
            }.toString()
        }
        val results = JSONObject()
        val wbList = mutableListOf<CheckinResult>()

        // 多账号列表：排除主账号（主账号单独签一次，列表里再有同一个人就是重复打接口）
        val accs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
        val multiAccs = accs.filter { it.accessToken.isNotBlank() && !isSameAsMainAccount(it, s) }

        // ── 先把所有网络请求一次性发出去（并行），再回主线程落库 ──
        //
        // 原来是 WorkBuddy → Trae → Qoder → MiniMax 串行，单家 1~3 秒，
        // 四家叠加就是「点了没反应」。现在四家同时发，总耗时 ≈ 最慢那家。
        val jobs = mutableListOf<() -> Any?>()

        // 当天已经签完的账号（用户 m06895）：6 点手动签过、8 点定时任务再跑时不该重打上游。
        //
        // **必须在这里查完**：下面每个账号的网络请求都跑在线程池里，而本项目硬规则是
        // 「线程池里绝不碰 SQLite」（见 AGENTS.md §6 通用段）。所以先在调用线程上把
        // 「provider|accountKey → 原记录」查成一张普通 Map，job 里只做 map 查表。
        //
        // 命中时返回的是**原记录**（积分/连续天数/文案全部照抄），落库后与库里一致，
        // 不会把首页的「已签到 773」冲成「已签到」。
        val doneToday = runCatching { store.doneMap(day) }.getOrDefault(emptyMap())
        fun doneOf(provider: String, accountKey: String): CheckinResult? =
            doneToday[provider + "|" + accountKey.ifBlank { HistoryStore.DEFAULT_ACCOUNT }]

        // 1) WorkBuddy 主账号
        if (s.wbToken.isNotBlank()) {
            val wbKey0 = wbAccountKey(s)
            val wbDone = doneOf("workbuddy", wbKey0)
            if (wbDone != null) {
                jobs.add { wbDone }
            } else {
                jobs.add {
                    CheckinGate.run("workbuddy", wbKey0) {
                        runBlocking { WbAuth.runCheckin(ctx, s) }
                    }
                }
            }
        } else {
            jobs.add { null }
        }

        // 2) WorkBuddy 多账号（每个账号一个任务）
        //
        // 池里只做 HTTP（WbApi.checkin 内部会顺带刷新 token），
        // 写 WbAccountStore 一律回主线程串行做 —— 多线程同时 read-modify-write
        // 那个 JSON 列表会互相覆盖，丢账号。
        multiAccs.forEach { acc ->
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                val done = doneOf("workbuddy", acc.id)
                if (done != null) {
                    Pair(null, done)
                } else try {
                    // 闸门：同一账号并发时只打一次上游（定时任务 vs 手动点击）
                    var fresh: WbAccount? = null
                    val r = CheckinGate.run("workbuddy", acc.id) {
                        val a = WbApi.checkin(acc)
                        fresh = a
                        CheckinResult(
                            "workbuddy",
                            if (a.lastStatus.isBlank()) "fail" else a.lastStatus,
                            "${a.displayName()}：${a.lastMessage}",
                            "",
                            a.totalPoints,
                            a.streakDays
                        )
                    }
                    Pair(fresh, r)
                } catch (e: Exception) {
                    Pair(null, CheckinResult("workbuddy", "fail", "${acc.displayName()}：${e.message ?: "签到异常"}", ""))
                }
            }
        }

        // 3) Trae —— 主账号 + 多账号列表，**每个账号一个任务并行跑**
        //
        // 换到的新 token 不能就地写回（池里不碰存储），先塞进 renewed 表，
        // 回主线程再落盘：主账号写 DataStore，其余写 TraeAccountStore。
        //
        // 用户 m18704：一键签到「用时很长」，手动点单卡却很快。
        // 旧实现把同一 provider 的所有账号收进**一个** job 里串行，账号之间
        // `Thread.sleep(12~25 秒)` 硬等（照抄同类项目 GAP_MIN/GAP_MAX=12,25 的经验值，
        // 那是为「全部串行」定的）。3 个账号光等待就 24~50 秒。
        //
        // 关键事实：**Trae 的 status 查询不会触发 9074，只有 claim 会**
        // （9074 = ErrWorkRiskControlBanned，是领取接口的风控）。所以这里改成：
        // 所有账号并行查 status / 走各自的续期链路，只有真正要发 claim 的那一下
        // 过一道全局节流（`LocalCheckin.throttleTraeClaim()`，间隔 3 秒）。
        // 3 个账号的总耗时从 24~50 秒降到 ≈ 一个账号的耗时 + 6 秒。
        val traeAccs = traeList(s)
        val traeRenewed = arrayOfNulls<String>(1)
        val traeRenewedAcc = java.util.concurrent.ConcurrentHashMap<String, String>()
        val traeRenewedDev = java.util.concurrent.ConcurrentHashMap<String, String>()
        // Trae 那批 job 在 jobs 里的起始下标（-1 = 没加）。
        // 超时**确实要放宽**：见下方 traeTimeoutSec 的注释 —— 每账号一个 job 之后
        // 仍然存在 claim 排队（节流间隔 3 秒），账号多时最后几个会等 N×3 秒。
        // 早先这里写过「并行后不再需要放宽超时」，与实现直接矛盾，会把后来人带偏。
        val traeJobStart = if (traeAccs.isNotEmpty()) jobs.size else -1
        traeAccs.forEach { acc ->
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                val done = doneOf("traesolo", acc.id)
                if (done != null) {
                    done
                } else CheckinGate.run("traesolo", acc.id) {
                    runBlocking {
                        LocalCheckin.trae(
                            acc.token, acc.deviceId, acc.global, acc.session,
                            onRenewed = { fresh ->
                                if (acc.id == HistoryStore.DEFAULT_ACCOUNT) traeRenewed[0] = fresh
                                else traeRenewedAcc[acc.id] = fresh
                            },
                            // 9074 换设备号成功时也要落盘，否则下一轮还是旧号，白白多打一次上游
                            onRenewedDevice = { fresh -> traeRenewedDev[acc.id] = fresh },
                        )
                    }
                }
            }
        }

        // 4) Qoder CN —— token 或 refreshToken 有一个就签；设备指纹选填（实测不带头也 200）
        val qoderAccs = qoderList(s)
        // BUGS-v2.63-287 P3-1：续期换出来的新 (token, refresh_token) 先收在这里，
        // job 里不能直接写 DataStore（硬规则「线程池绝不碰 SQLite / SharedPreferences」），
        // 落库段在调用线程统一回写。与 hwRenewed / zpRenewed 同一套做法。
        val qdRenewed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        qoderAccs.forEach { acc ->
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                doneOf("qoder", acc.id) ?: CheckinGate.run("qoder", acc.id) {
                    runBlocking {
                        LocalCheckin.qoder(
                            acc.region.ifBlank { "cn" }, acc.token,
                            acc.uid, acc.machineId, "",
                            acc.refresh,
                            onRenewed = { t, rf -> qdRenewed[acc.id] = Pair(t, rf) }
                        )
                    }
                }
            }
        }

        // 5) MiniMax Code
        val mmAccs = mmList(s)
        mmAccs.forEach { acc ->
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                doneOf("minimax", acc.id)
                    ?: CheckinGate.run("minimax", acc.id) {
                        runBlocking { LocalCheckin.minimax(acc.token, acc.userId, acc.deviceId, "") }
                    }
            }
        }

        // 6) 华为码道 —— 多账号（用户 m24258），每账号一份 Cookie，一个 job 一个。
        // 过滤用 hwUsable 而非 hwHasSession（BUGS-v2.46 P2）：被证实是匿名会话的 Cookie
        // 不该被派 job —— 派了必然 401，失败结果还会把当天那条 success 记录覆盖掉。
        //
        // V2.79：静默重登。auth 域的长期凭据传给 HwCheckin.run，登录态失效时它会用
        // 不可见 WebView 走一次 302 拿到新 SESSION_ID，并通过 onRenewed 回传。
        // 新 Cookie 必须**立刻落盘**（与 Trae 换 token 同一理由）：不写回的话下一轮
        // 还是拿旧 Cookie 去打、还要再花 18 秒重登一次。
        val hwAccs = hwList(s).filter { hwUsable(it.cookie) }
        val hwJobStart = if (hwAccs.isNotEmpty()) jobs.size else -1
        val hwRenewed = java.util.concurrent.ConcurrentHashMap<String, HwRenew.Renewed>()
        hwAccs.forEach { acc ->
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）。华为本来有 HwTodayMark 当日标记，
                // 但那层会在换 Cookie / 清标记后失效，用历史记录兜一道更稳。
                doneOf("huawei", acc.id) ?: CheckinGate.run("huawei", acc.id) {
                    runBlocking {
                        // 主账号用设置里那份 authCookie，列表账号用自己那份（V2.79：
                        // 每个华为号在 auth 域的凭据不同，共用会把 A 号的拿去给 B 号续期）。
                        val auth = if (acc.id == HistoryStore.DEFAULT_ACCOUNT) s.hwAuthCookie else acc.authCookie
                        HwCheckin.run(ctx, acc.cookie, s.hwPortalCookie, auth) { fresh ->
                            hwRenewed[acc.id] = fresh
                        }
                    }
                }
            }
        }

        // 6b) 豆包（V2.66 重新接入；**V2.75 起搁置**，ENABLED=false 时整块不派 job）。
        // 只在 isReady 为真时派 job：没有 sessionid 的 Cookie 打上游必然 710012001，
        // 派了只会白等一轮超时，还会把当天已有的 success 记录覆盖成 fail。
        // 搁置原因见 DoubaoWebCheckin.ENABLED 的注释。
        val dbReady = DoubaoWebCheckin.ENABLED && isReady("doubao", s)
        // BUGS-v2.63 P3-8：原来叫 dbJobStart（和 traeJobStart 一样暗示「job 起始下标」），
        // 但它从不参与下标运算 —— 消费段是靠 idx++ 顺序读，这里只当「有没有派过」的开关用。
        // 改成 Boolean 名字，避免后来人以为它像 Trae 那样有独立超时策略。
        val dbDispatched = dbReady
        if (dbReady) {
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                doneOf("doubao", HistoryStore.DEFAULT_ACCOUNT)
                    ?: CheckinGate.run("doubao", HistoryStore.DEFAULT_ACCOUNT) {
                        runBlocking { DoubaoWebCheckin.run(ctx, s.dbCookie) }
                    }
            }
        }

        // 6c) 智谱清言（V2.76 接入）：纯 HTTP，一次写请求。
        // 只在 isReady 为真时派 job —— 游客态 Cookie 打上游只会拿到 401 40103，
        // 派了白等一轮，还会把当天已有的 success 记录覆盖成 fail。
        val zpReady2 = isReady("zhipu", s)
        val zpDispatched = zpReady2
        // BUGS-v2.63-286 P3：智谱 refresh 若真的轮换出新 refresh_token，job 里不能直接
        // 写 DataStore（硬规则：线程池绝不碰 SQLite / SharedPreferences），先收在这里，
        // 落库段在调用线程统一回写。与 hwRenewed 同一套做法。
        val zpRenewed = java.util.concurrent.ConcurrentHashMap<String, String>()
        if (zpReady2) {
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                doneOf("zhipu", HistoryStore.DEFAULT_ACCOUNT)
                    ?: CheckinGate.run("zhipu", HistoryStore.DEFAULT_ACCOUNT) {
                        runBlocking {
                            ZhipuCheckin.run(s.zpCookie, s.zpDeviceId) { rt -> zpRenewed["zhipu"] = rt }
                        }
                    }
            }
        }

        // 6d) 百度智能云 / 百度搭子（V2.76 接入）：Cookie + csrftoken 纯 HTTP。
        val qfReady2 = isReady("qianfan", s)
        val qfDispatched = qfReady2
        if (qfReady2) {
            jobs.add {
                // 当天已签直接返回原记录（用户 m06895）
                doneOf("qianfan", HistoryStore.DEFAULT_ACCOUNT)
                    ?: CheckinGate.run("qianfan", HistoryStore.DEFAULT_ACCOUNT) {
                        runBlocking { DumateCheckin.run(s.qfCookie) }
                    }
            }
        }

        // P2-8：用 parallelDetail 而不是 parallel —— 后者把「超时」和「任务抛异常」
        // 都收成 null，调用方只能统一兜底成「缺少凭据」，上游 hang 住 60 秒时
        // 用户看到的是「缺少凭据：填 mmoat_ 开头的 token」，真实原因被完全掩盖。
        //
        // Trae 现在每账号一个 job 并行，各自 60 秒够用；唯一可能超时的是
        // claim 排队（账号多时最后几个要等 N×3 秒），给 Trae 的 job 放宽到
        // 60 + 账号数×5 秒即可（节流间隔 3 秒，留点余量）。
        val traeTimeoutSec = TASK_TIMEOUT_SEC + traeAccs.size * 5L
        val (out, timeouts) = parallelDetail(jobs) { i ->
            if (traeJobStart >= 0 && i >= traeJobStart && i < traeJobStart + traeAccs.size)
                traeTimeoutSec else TASK_TIMEOUT_SEC
        }

        /** 某个下标的任务是不是超时了（用来区分「超时」与「没配凭据」两种 null）。 */
        fun timedOut(i: Int): Boolean = timeouts.contains(i)

        /** 统一的兜底文案：超时说实话，没超时才说缺凭据。 */
        fun fallback(i: Int, emptyMsg: String): String =
            if (timedOut(i)) "上游超时（${TASK_TIMEOUT_SEC} 秒无响应），请稍后重试；" +
                "若反复超时，检查网络或代理" else emptyMsg

        var idx = 0
        val wbMainIdx = idx
        val wbMain = out.getOrNull(idx++) as? CheckinResult
        if (wbMain != null) {
            store.upsert(day, wbMain, wbAccountKey(s))
            wbList.add(wbMain)
        } else if (s.wbToken.isNotBlank()) {
            // 有 token 却拿不到结果 = 超时或异常，不能静默丢掉（否则前端显示「未配置」）
            wbList.add(CheckinResult("workbuddy", "fail", fallback(wbMainIdx, "签到异常，请重试")))
        }
        multiAccs.forEach { acc ->
            val i = idx++
            @Suppress("UNCHECKED_CAST")
            val pr = out.getOrNull(i) as? Pair<WbAccount?, CheckinResult>
            if (pr != null) {
                pr.first?.let { a -> runCatching { WbAccountStore.update(ctx, a) } }
                store.upsert(day, pr.second, acc.id)
                wbList.add(pr.second)
            } else {
                val r = CheckinResult("workbuddy", "fail",
                    "${acc.displayName()}：" + fallback(i, "签到异常，请重试"))
                store.upsert(day, r, acc.id)
                wbList.add(r)
            }
        }

        // 汇总成一行给前端：全成功=success，有失败=fail，部分成功=already
        val wbAgg: CheckinResult = when {
            wbList.isEmpty() -> CheckinResult("workbuddy", "fail", "未配置 WorkBuddy 账号")
            wbList.size == 1 -> wbList[0]
            else -> {
                val okN = wbList.count { it.status == "success" || it.status == "already" }
                // 部分成功必须报 fail：原先两个分支都返回 already，用户看不出有账号失败
                val st = if (okN == wbList.size) "already" else "fail"
                val msg = wbList.joinToString("；") { it.message }
                // 多账号：积分取各账号之和（读不到的跳过），streak 取最大值
                val pts = wbList.map { it.totalPoints }.filter { it >= 0 }
                val sum = if (pts.isEmpty()) -1 else pts.sum()
                val stk = wbList.map { it.streakDays }.filter { it >= 0 }.maxOrNull() ?: -1
                CheckinResult("workbuddy", st, msg, "", sum, stk)
            }
        }
        results.put("workbuddy", toJson(wbAgg, day))

        // BUGS-v2.63-288 P1：主账号的 token 与 deviceId 必须合成一次写。
        // 以前是两段各自 `SettingsStore.save(ctx, s.copy(...))`，都基于本轮
        // 开头读到的旧快照 `s` —— 后执行的那次把前一次刚写的字段覆盖回旧值。
        // `LocalCheckin.trae()` 里设备号回调（:835）排在 token 回调（:851）
        // 之前，所以这里原来是「先存 deviceId、再存 token」→ deviceId 被冲掉；
        // 一旦顺序反过来就换成 token 被冲掉。两种都表现为「本轮成功、下次
        // 还是旧凭据」。这里改成先重新读当前配置再一次性合并写。
        if (traeRenewed[0] != null || traeRenewedDev[HistoryStore.DEFAULT_ACCOUNT] != null) {
            runCatching {
                runBlocking {
                    val cur = SettingsStore.flow(ctx).first()
                    SettingsStore.save(ctx, cur.copy(
                        traeToken = traeRenewed[0] ?: cur.traeToken,
                        traeDeviceId = traeRenewedDev[HistoryStore.DEFAULT_ACCOUNT] ?: cur.traeDeviceId))
                }
            }
        }
        // 多账号换到的 token 逐个写回列表（主线程串行，避免 JSON 列表互相覆盖）
        if (traeRenewedAcc.isNotEmpty()) {
            runCatching {
                traeAccs.filter { it.id != HistoryStore.DEFAULT_ACCOUNT }.forEach { acc ->
                    val fresh = traeRenewedAcc[acc.id] ?: return@forEach
                    TraeAccountStore.update(ctx, acc.copy(token = fresh))
                }
            }
        }
        // 9074 换到的设备号同样要落盘：列表账号写列表。
        // 不写回的话下一轮还是旧号，等于每次都要多打一次上游才失败。
        //
        // BUGS-v2.63-288 P1：列表账号也不能分两次 update —— `TraeAccountStore.update`
        // 是整条替换，后一次会拿同一份旧 `acc` 覆盖前一次刚写的字段。
        // 所以 token 与 deviceId 在这里合并成一次 update。
        if (traeRenewedDev.isNotEmpty()) {
            runCatching {
                traeAccs.filter { it.id != HistoryStore.DEFAULT_ACCOUNT }.forEach { acc ->
                    val freshDev = traeRenewedDev[acc.id] ?: return@forEach
                    TraeAccountStore.update(ctx, acc.copy(
                        token = traeRenewedAcc[acc.id] ?: acc.token,
                        deviceId = freshDev))
                }
            }
        }

        // ── Trae：逐账号落库 + 汇总一行 ──
        //
        // 每个账号现在都是**独立的 job**（见上面 jobs 构建处的注释：status 查询不会
        // 触发 9074，只有 claim 会，所以只对 claim 做全局节流），结果按账号顺序
        // 逐个从 out 里取。
        val traeListRes = mutableListOf<CheckinResult>()
        // 只有真的往 jobs 里加过 Trae 任务，才允许占用下标 ——
        // 否则 traeAccs 为空时这些 idx++ 会把 Qoder 的结果当成 Trae 的读走。
        if (traeJobStart >= 0) idx += traeAccs.size
        traeAccs.forEachIndexed { ai, acc ->
            val jobIdx = if (traeJobStart >= 0) traeJobStart + ai else -1
            val r = (if (jobIdx >= 0) out.getOrNull(jobIdx) as? CheckinResult else null)
                ?: CheckinResult(
                    "traesolo", "fail",
                    if (jobIdx >= 0 && timedOut(jobIdx))
                        "上游超时（${TASK_TIMEOUT_SEC} 秒无响应），请稍后重试；若反复超时，检查网络或代理"
                    else "缺少凭据：点「登录主账号」，或填 X-Cloudide-Session")
            store.upsert(day, r, acctKey(acc.id))
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                runCatching {
                    TraeAccountStore.update(ctx, acc.copy(
                        lastStatus = r.status, lastMessage = r.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = r.totalPoints, streakDays = r.streakDays,
                        usedPoints = r.usedPoints))
                }
            }
            traeListRes.add(r)
        }
        val tr = aggregate("traesolo", traeListRes, "缺少凭据：点「登录主账号」，或填 X-Cloudide-Session")
        results.put("traesolo", toJson(tr, day))

        // ── Qoder：逐账号落库 + 汇总一行 ──
        val qdListRes = mutableListOf<CheckinResult>()
        qoderAccs.forEach { acc ->
            val i = idx++
            val r = out.getOrNull(i) as? CheckinResult ?: CheckinResult(
                "qoder", "fail", fallback(i, "缺少凭据：填 dt- token 或 drt- refreshToken"))
            store.upsert(day, r, acctKey(acc.id))
            // BUGS-v2.63-287 P3-1：续期换出的新凭据在调用线程回写。
            qdRenewed[acc.id]?.let { (t, rf) ->
                if (acc.id == HistoryStore.DEFAULT_ACCOUNT) {
                    runCatching {
                        runBlocking {
                            val cur = SettingsStore.flow(ctx).first()
                            SettingsStore.save(ctx, cur.copy(
                                qoderToken = t.ifBlank { cur.qoderToken },
                                qoderRefresh = rf.ifBlank { cur.qoderRefresh }))
                        }
                    }
                } else {
                    runCatching { QoderAccountStore.update(ctx, acc.copy(token = t, refresh = rf)) }
                }
            }
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                runCatching {
                    QoderAccountStore.update(ctx, acc.copy(
                        lastStatus = r.status, lastMessage = r.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = r.totalPoints, streakDays = r.streakDays,
                        usedPoints = r.usedPoints))
                }
            }
            qdListRes.add(r)
        }
        if (qdListRes.isNotEmpty()) {
            results.put("qoder", toJson(aggregate("qoder", qdListRes, "缺少凭据：填 dt- token 或 drt- refreshToken"), day))
        }

        // ── MiniMax：逐账号落库 + 汇总一行 ──
        val mmListRes = mutableListOf<CheckinResult>()
        mmAccs.forEach { acc ->
            val i = idx++
            val r = out.getOrNull(i) as? CheckinResult ?: CheckinResult(
                "minimax", "fail", fallback(i, "缺少凭据：填 mmoat_ 开头的 token"))
            store.upsert(day, r, acctKey(acc.id))
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                runCatching {
                    MmAccountStore.update(ctx, acc.copy(
                        lastStatus = r.status, lastMessage = r.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = r.totalPoints, streakDays = r.streakDays,
                        usedPoints = r.usedPoints))
                }
            }
            mmListRes.add(r)
        }
        if (mmListRes.isNotEmpty()) {
            results.put("minimax", toJson(aggregate("minimax", mmListRes, "缺少凭据：填 mmoat_ 开头的 token"), day))
        }

        // ── 华为码道：逐账号落库 + 汇总一行（用户 m24258）──
        if (hwJobStart >= 0) {
            // V2.79：静默重登换到的新 Cookie 先落盘（主账号写 Settings，列表账号写 store）。
            // 放在落库之前：即使这一轮结果仍是 fail，新凭据也是有效资产。
            if (hwRenewed.isNotEmpty()) {
                hwRenewed[HistoryStore.DEFAULT_ACCOUNT]?.let { fresh ->
                    runCatching {
                        runBlocking {
                            val cur = SettingsStore.flow(ctx).first()
                            SettingsStore.save(ctx, cur.copy(
                                hwCookie = fresh.cookie,
                                hwAuthCookie = fresh.authCookie,
                                hwPortalCookie = fresh.portalCookie.ifBlank { cur.hwPortalCookie },
                            ))
                        }
                    }
                }
                hwAccs.filter { it.id != HistoryStore.DEFAULT_ACCOUNT }.forEach { acc ->
                    val fresh = hwRenewed[acc.id] ?: return@forEach
                    runCatching {
                        HwAccountStore.update(ctx, acc.copy(
                            cookie = fresh.cookie,
                            authCookie = fresh.authCookie.ifBlank { acc.authCookie },
                        ))
                    }
                }
            }
            val hwListRes = mutableListOf<CheckinResult>()
            hwAccs.forEach { acc ->
                val i = idx++
                val r = out.getOrNull(i) as? CheckinResult ?: CheckinResult(
                    "huawei", "fail", fallback(i, "缺少华为会话 Cookie：点「登录华为码道」在站内登录一次"))
                store.upsert(day, r, acctKey(acc.id))
                if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                    runCatching {
                        HwAccountStore.update(ctx, acc.copy(
                            lastStatus = r.status, lastMessage = r.message,
                            lastCheckAt = System.currentTimeMillis(),
                            totalPoints = r.totalPoints, streakDays = r.streakDays,
                            usedPoints = r.usedPoints))
                    }
                }
                hwListRes.add(r)
            }
            results.put("huawei", toJson(
                aggregate("huawei", hwListRes, "缺少华为会话 Cookie：点「登录华为码道」在站内登录一次"), day))
        }

        // ── 豆包：单账号落库（V2.66）──
        if (dbDispatched) {
            val i = idx++
            val r = out.getOrNull(i) as? CheckinResult ?: CheckinResult(
                "doubao", "fail", fallback(i, "缺少豆包 Cookie：点「登录豆包」在站内登录一次"))
            store.upsert(day, r, HistoryStore.DEFAULT_ACCOUNT)
            results.put("doubao", toJson(r, day))
        }

        // ── 智谱清言 / 百度千帆：单账号落库（V2.76）──
        if (zpDispatched) {
            val i = idx++
            val r = out.getOrNull(i) as? CheckinResult ?: CheckinResult(
                "zhipu", "fail", fallback(i, "缺少 Cookie：点「登录智谱清言」在站内登录一次"))
            store.upsert(day, r, HistoryStore.DEFAULT_ACCOUNT)
            // BUGS-v2.63-286 P3：refresh 轮换出来的新 refresh_token 在这里回写
            zpRenewed["zhipu"]?.let { rt ->
                runCatching {
                    runBlocking {
                        val cur = SettingsStore.flow(ctx).first()
                        SettingsStore.save(ctx, cur.copy(zpCookie = ZhipuCheckin.withRefreshToken(cur.zpCookie, rt)))
                    }
                }
            }
            results.put("zhipu", toJson(r, day))
        }
        if (qfDispatched) {
            val i = idx++
            val r = out.getOrNull(i) as? CheckinResult ?: CheckinResult(
                "qianfan", "fail", fallback(i, "缺少 Cookie：点「登录百度智能云」在站内登录一次"))
            store.upsert(day, r, HistoryStore.DEFAULT_ACCOUNT)
            results.put("qianfan", toJson(r, day))
        }

        // P3-5：原来 `extraN = results.length() - 2` 是「总家数减 2」，而
        // `results` 里 workbuddy 与 traesolo 都是无条件写入的，于是：
        //   · Trae 完全没配（只有 workbuddy）时 extraN 仍是 0，但文案会说「共 2 家」；
        //   · Qoder 没配（不写 key）时反而少算一家。
        // 改成按「真正产生了结果的家数」数，并把账号数单独说出来。
        val famKeys = listOf(
            "workbuddy", "traesolo", "qoder", "minimax", "huawei",
            "doubao", "zhipu", "qianfan",
        ).filter { results.has(it) }
        val summary = when {
            wbList.size > 1 -> "本地签到完成（WorkBuddy ${wbList.size} 个账号 + 其他 ${famKeys.size - 1} 家）"
            famKeys.size > 1 -> "本地签到完成（共 ${famKeys.size} 家）"
            else -> "本地签到完成"
        }
        return JSONObject().apply {
            put("trigger", "local")
            put("results", results)
            put("summary", summary)
        }.toString()
    }

    /**
     * 多账号汇总成一行给前端：全成功=already，有失败=fail。
     * 部分成功必须报 fail —— 都报 already 的话用户看不出有账号失败。
     */
    /**
     * 多账号结果汇总。
     *
     * 用户 m11566（截图）：设置页 Trae 的测试签到显示「失败 · fail」，
     * 正文第一句却是「今日已签到 · 当前积分 3659；设备号被风控…」——
     * 因为以前是 `list.joinToString("；") { it.message }`，把「成功的账号」和
     * 「失败的账号」平铺拼在一起，整体状态又按「有失败就 fail」，
     * 于是标题说失败、正文第一句说成功，自相矛盾，用户以为程序判断错了。
     *
     * 现在分两种写法：
     * - 全部成功：消息直接拼（都是好消息，拼起来不冲突）；
     * - 有失败：前面加一句「N 个账号 M 个成功 K 个失败」，正文只列失败的那些
     *   （成功的已经在首页/记录页各自一行，这里再列一遍只会淹没真正的错误）。
     */
    private fun aggregate(key: String, list: List<CheckinResult>, emptyMsg: String): CheckinResult {
        if (list.isEmpty()) return CheckinResult(key, "fail", emptyMsg)
        if (list.size == 1) return list[0]
        val okList = list.filter { it.status == "success" || it.status == "already" }
        val badList = list.filter { it.status != "success" && it.status != "already" }
        val allOk = badList.isEmpty()
        val st = if (allOk) "already" else "fail"
        val msg = if (allOk) {
            list.joinToString("；") { it.message }
        } else {
            val head = "${list.size} 个账号：${okList.size} 个成功、${badList.size} 个失败"
            if (badList.isEmpty()) head else head + " —— " + badList.joinToString("；") { it.message }
        }
        val pts = list.map { it.totalPoints }.filter { it >= 0 }
        val sum = if (pts.isEmpty()) -1 else pts.sum()
        val usd = list.map { it.usedPoints }.filter { it >= 0 }
        val usedSum = if (usd.isEmpty()) -1 else usd.sum()
        val stk = list.map { it.streakDays }.filter { it >= 0 }.maxOrNull() ?: -1
        return CheckinResult(key, st, msg, "", sum, stk, usedSum)
    }

    private fun checkinOne(key: String, log: Boolean = true): String {
        val s = settings()
        val day = today()
        // V2.86（BUGS-v2.63 P2-2）：单卡签到也要「当天已签就跳过」，与 checkinAll 同口径。
        //
        // 以前只有 checkinAll 做了短路，checkinOne 完全没有 —— 用户当天签成功后点一下单卡的
        // 「签到」，WorkBuddy / Trae / Qoder / MiniMax 会实打实再打一轮上游。Trae 的 9074
        // （ErrWorkRiskControlBanned）已证明重复/密集请求会累积风险，这不是纯理论问题。
        //
        // 只对真实签到（log=true）生效：log=false 是「测试签到」按钮，用户就是要真打一次看
        // 上游返回什么，复用旧记录会骗人（checkinAll 里那句 reuse=log 是同一套理由）。
        val doneToday = if (log) runCatching { store.doneMap(day) }.getOrDefault(emptyMap()) else emptyMap()
        fun doneOf(provider: String, accountKey: String): CheckinResult? =
            doneToday[provider + "|" + accountKey.ifBlank { HistoryStore.DEFAULT_ACCOUNT }]

        // WorkBuddy 多账号：每个账号各写一行（用 account_key 区分），
        // 不能汇总成一行——那会互相覆盖，只剩最后一个账号的记录。
        if (key == "workbuddy" && s.wbToken.isBlank()) {
            val accs = try { WbAccountStore.all(ctx) } catch (e: Exception) { emptyList() }
            // P3 N7（BUGS-v2.3）：与 checkinAll 口径一致，排除主账号。
            // 当前分支前提 s.wbToken 为空，isSameAsMainAccount 必然 false，所以不会真重复；
            // 但将来若允许「主账号 token 与列表账号并存」，少了这层过滤就会打两次接口。
            val list = accs.filter { it.accessToken.isNotBlank() && !isSameAsMainAccount(it, s) }.map { acc ->
                val done = doneOf("workbuddy", acc.id)
                if (done != null) return@map done
                val one = try {
                    // 闸门：log=false 是「测试签到」，必须 reuse=false —— 复用旧结果会骗人，
                    // 也不能把没落库的结果写进复用表（否则紧接着的真实签到会被去重成漏签）
                    var fresh: WbAccount? = null
                    val r = CheckinGate.run("workbuddy", acc.id, reuse = log) {
                        val a = WbApi.checkin(acc)
                        fresh = a
                        CheckinResult(
                            "workbuddy",
                            if (a.lastStatus.isBlank()) "fail" else a.lastStatus,
                            "${a.displayName()}：${a.lastMessage}",
                            "",
                            a.totalPoints,
                            a.streakDays
                        )
                    }
                    fresh?.let { a -> WbAccountStore.update(ctx, a) }
                    r
                } catch (e: Exception) {
                    CheckinResult("workbuddy", "fail", "${acc.displayName()}：${e.message ?: "签到异常"}", "")
                }
                if (log) store.upsert(day, one, acc.id)
                one
            }
            val agg = when {
                list.isEmpty() -> CheckinResult("workbuddy", "fail", "未配置 WorkBuddy 账号")
                list.size == 1 -> list[0]
                else -> {
                    val okN = list.count { it.status == "success" || it.status == "already" }
                    val pts = list.map { it.totalPoints }.filter { it >= 0 }
                    val stk = list.map { it.streakDays }.filter { it >= 0 }.maxOrNull() ?: -1
                    CheckinResult(
                        "workbuddy",
                        // 部分成功必须报 fail：原先两个分支都返回 already，用户看不出有账号失败
                        if (okN == list.size) "already" else "fail",
                        list.joinToString("；") { it.message },
                        "",
                        if (pts.isEmpty()) -1 else pts.sum(),
                        stk
                    )
                }
            }
            return toJson(agg, day).toString()
        }

        // Trae / Qoder / MiniMax：同样逐账号各写一行（account_key 区分），再汇总返回
        if (key == "traesolo" || key == "qoder" || key == "minimax") {
            val ones = mutableListOf<CheckinResult>()
            when (key) {
                "traesolo" -> traeList(s).forEach { acc ->
                    doneOf("traesolo", acc.id)?.let { ones.add(it); return@forEach }
                    // 用户 m18704：这里原来每个账号之间 sleep 12~25 秒（为躲 9074）。
                    // 9074 只由 claim 触发、status 查询不会，节流已经收进
                    // `LocalCheckin.throttleTraeClaim()`（只管 claim 那一下，间隔 3 秒），
                    // 所以这里不再整账号串行等待。
                    var renewed: String? = null
                    var renewedDev: String? = null
                    // 单个账号抛异常不能让整个循环中断（否则后面账号一个都不签，
                    // 且异常冒泡到 handle() 的 catch，前端只看到 {"error":...}）
                    val r0 = try {
                        CheckinGate.run("traesolo", acc.id, reuse = log) {
                            runBlocking {
                                LocalCheckin.trae(
                                    acc.token, acc.deviceId, acc.global, acc.session,
                                    onRenewed = { fresh -> renewed = fresh },
                                    // 9074 时换设备号重试，换到的号必须落盘（见 LocalCheckin.trae 注释）
                                    onRenewedDevice = { fresh -> renewedDev = fresh },
                                )
                            }
                        }
                    } catch (e: Exception) {
                        CheckinResult("traesolo", "fail",
                            "签到异常：${e.message ?: e.javaClass.simpleName}", "")
                    }
                    // BUGS-v2.63-288 P1：token 与 deviceId 合成一次写。
                    // 以前是两段各自 `SettingsStore.save(ctx, s.copy(...))` /
                    // 两次 `TraeAccountStore.update(acc.copy(...))`，都基于本轮
                    // 开头读到的旧快照 —— 后执行的那次把前一次刚写的字段覆盖回旧值。
                    // `TraeAccountStore.update` 是整条替换，同样会互相覆盖。
                    if (renewed != null || renewedDev != null) {
                        if (acc.id == HistoryStore.DEFAULT_ACCOUNT) {
                            runCatching {
                                runBlocking {
                                    val cur = SettingsStore.flow(ctx).first()
                                    SettingsStore.save(ctx, cur.copy(
                                        traeToken = renewed ?: cur.traeToken,
                                        traeDeviceId = renewedDev ?: cur.traeDeviceId))
                                }
                            }
                        } else {
                            runCatching {
                                TraeAccountStore.update(ctx, acc.copy(
                                    token = renewed ?: acc.token,
                                    deviceId = renewedDev ?: acc.deviceId))
                            }
                        }
                    }
                    if (log) store.upsert(day, r0, acctKey(acc.id))
                    if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                        runCatching {
                            TraeAccountStore.update(ctx, acc.copy(
                                lastStatus = r0.status, lastMessage = r0.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r0.totalPoints, streakDays = r0.streakDays,
                                usedPoints = r0.usedPoints))
                        }
                    }
                    ones.add(r0)
                }
                "qoder" -> qoderList(s).forEach { acc ->
                    doneOf("qoder", acc.id)?.let { ones.add(it); return@forEach }
                    // BUGS-v2.63-287 P3-1：续期换出的新凭据回写
                    var qdTok: String? = null
                    var qdRef: String? = null
                    val r0 = try {
                        CheckinGate.run("qoder", acc.id, reuse = log) {
                            runBlocking {
                                LocalCheckin.qoder(acc.region.ifBlank { "cn" }, acc.token,
                                    acc.uid, acc.machineId, "", acc.refresh,
                                    onRenewed = { t, rf -> qdTok = t; qdRef = rf })
                            }
                        }
                    } catch (e: Exception) {
                        CheckinResult("qoder", "fail",
                            "签到异常：${e.message ?: e.javaClass.simpleName}", "")
                    }
                    if (qdTok != null || qdRef != null) {
                        runCatching {
                            runBlocking {
                                if (acc.id == HistoryStore.DEFAULT_ACCOUNT) {
                                    val cur = SettingsStore.flow(ctx).first()
                                    SettingsStore.save(ctx, cur.copy(
                                        qoderToken = qdTok ?: cur.qoderToken,
                                        qoderRefresh = qdRef ?: cur.qoderRefresh))
                                } else {
                                    QoderAccountStore.update(ctx, acc.copy(
                                        token = qdTok ?: acc.token,
                                        refresh = qdRef ?: acc.refresh))
                                }
                            }
                        }
                    }
                    if (log) store.upsert(day, r0, acctKey(acc.id))
                    if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                        runCatching {
                            QoderAccountStore.update(ctx, acc.copy(
                                lastStatus = r0.status, lastMessage = r0.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r0.totalPoints, streakDays = r0.streakDays,
                                usedPoints = r0.usedPoints))
                        }
                    }
                    ones.add(r0)
                }
                else -> mmList(s).forEach { acc ->
                    doneOf("minimax", acc.id)?.let { ones.add(it); return@forEach }
                    val r0 = try {
                        CheckinGate.run("minimax", acc.id, reuse = log) {
                            runBlocking {
                                LocalCheckin.minimax(acc.token, acc.userId, acc.deviceId, "")
                            }
                        }
                    } catch (e: Exception) {
                        CheckinResult("minimax", "fail",
                            "签到异常：${e.message ?: e.javaClass.simpleName}", "")
                    }
                    if (log) store.upsert(day, r0, acctKey(acc.id))
                    if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                        runCatching {
                            MmAccountStore.update(ctx, acc.copy(
                                lastStatus = r0.status, lastMessage = r0.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r0.totalPoints, streakDays = r0.streakDays,
                                usedPoints = r0.usedPoints))
                        }
                    }
                    ones.add(r0)
                }
            }
            val emptyMsg = when (key) {
                "traesolo" -> "缺少凭据：点「登录主账号」，或填 X-Cloudide-Session"
                "qoder" -> "缺少凭据：填 dt- token 或 drt- refreshToken"
                else -> "缺少凭据：填 mmoat_ 开头的 token"
            }
            return toJson(aggregate(key, ones, emptyMsg), day).toString()
        }

        // 华为码道：多账号逐签（用户 m24258）。
        // 与其他三家一致：单家签到请求签「这家所有已就绪账号」，再汇总成一行返回。
        if (key == "huawei") {
            val accs = hwList(s).filter { hwUsable(it.cookie) }
            if (accs.isEmpty()) {
                return toJson(CheckinResult(
                    "huawei", "fail",
                    "缺少华为会话 Cookie：点「登录华为码道」在站内登录一次", ""), day).toString()
            }
            val ones = mutableListOf<CheckinResult>()
            accs.forEach { a ->
                doneOf("huawei", a.id)?.let { ones.add(it); return@forEach }
                // V2.79：静默重登换到的新 Cookie 就地落盘（单家签到这条路径不经过
                // checkinAll 的落盘段，漏了就等于每次都要重登一次）。
                var renewed: HwRenew.Renewed? = null
                val r0 = try {
                    CheckinGate.run("huawei", a.id, reuse = log) {
                        runBlocking {
                            val auth = if (a.id == HistoryStore.DEFAULT_ACCOUNT) s.hwAuthCookie else a.authCookie
                            HwCheckin.run(ctx, a.cookie, s.hwPortalCookie, auth) { fresh ->
                                renewed = fresh
                            }
                        }
                    }
                } catch (e: Exception) {
                    CheckinResult("huawei", "fail",
                        "签到异常：${e.message ?: e.javaClass.simpleName}", "")
                }
                renewed?.let { fresh ->
                    runCatching {
                        if (a.id == HistoryStore.DEFAULT_ACCOUNT) {
                            runBlocking {
                                val cur = SettingsStore.flow(ctx).first()
                                SettingsStore.save(ctx, cur.copy(
                                    hwCookie = fresh.cookie,
                                    hwAuthCookie = fresh.authCookie,
                                    hwPortalCookie = fresh.portalCookie.ifBlank { cur.hwPortalCookie },
                                ))
                            }
                        } else {
                            HwAccountStore.update(ctx, a.copy(
                                cookie = fresh.cookie,
                                authCookie = fresh.authCookie.ifBlank { a.authCookie },
                            ))
                        }
                    }
                }
                if (log) store.upsert(day, r0, acctKey(a.id))
                if (a.id != HistoryStore.DEFAULT_ACCOUNT) {
                    runCatching {
                        HwAccountStore.update(ctx, a.copy(
                            lastStatus = r0.status, lastMessage = r0.message,
                            lastCheckAt = System.currentTimeMillis(),
                            totalPoints = r0.totalPoints, streakDays = r0.streakDays,
                            usedPoints = r0.usedPoints))
                    }
                }
                ones.add(r0)
            }
            return toJson(
                aggregate("huawei", ones, "缺少华为会话 Cookie：点「登录华为码道」在站内登录一次"), day).toString()
        }

        // 豆包（V2.66）：单账号，走 WebView 页面内 fetch。
        // V2.75 起搁置（DoubaoWebCheckin.ENABLED=false）：直接返回「已搁置」，
        // 不发任何请求，也不落库（否则历史里会多一条永远 fail 的记录）。
        if (key == "doubao") {
            if (!DoubaoWebCheckin.ENABLED) {
                return toJson(CheckinResult(
                    "doubao", "fail", "豆包已搁置（V2.75 起隐藏），不会参与签到", ""), day).toString()
            }
            if (!isReady("doubao", s)) {
                return toJson(CheckinResult(
                    "doubao", "fail",
                    "缺少豆包 Cookie：点「登录豆包」在站内登录一次", ""), day).toString()
            }
            val r0 = doneOf("doubao", HistoryStore.DEFAULT_ACCOUNT) ?: try {
                CheckinGate.run("doubao", HistoryStore.DEFAULT_ACCOUNT, reuse = log) {
                    runBlocking { DoubaoWebCheckin.run(ctx, s.dbCookie) }
                }
            } catch (e: Exception) {
                CheckinResult("doubao", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            if (log) store.upsert(day, r0, HistoryStore.DEFAULT_ACCOUNT)
            return toJson(r0, day).toString()
        }

        // 智谱清言（V2.76）：单账号纯 HTTP。
        if (key == "zhipu") {
            if (!isReady("zhipu", s)) {
                return toJson(CheckinResult(
                    "zhipu", "fail",
                    "缺少 Cookie：点「登录智谱清言」在站内登录一次", ""), day).toString()
            }
            val r0 = doneOf("zhipu", HistoryStore.DEFAULT_ACCOUNT) ?: try {
                // BUGS-v2.63-286 P3：refresh 轮换时回写 zpCookie（平时不触发）。
                var zpNewRt: String? = null
                val rr = CheckinGate.run("zhipu", HistoryStore.DEFAULT_ACCOUNT, reuse = log) {
                    runBlocking { ZhipuCheckin.run(s.zpCookie, s.zpDeviceId) { rt -> zpNewRt = rt } }
                }
                zpNewRt?.let { rt ->
                    runCatching {
                        runBlocking {
                            val cur = SettingsStore.flow(ctx).first()
                            SettingsStore.save(ctx, cur.copy(zpCookie = ZhipuCheckin.withRefreshToken(cur.zpCookie, rt)))
                        }
                    }
                }
                rr
            } catch (e: Exception) {
                CheckinResult("zhipu", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            if (log) store.upsert(day, r0, HistoryStore.DEFAULT_ACCOUNT)
            return toJson(r0, day).toString()
        }

        // 百度千帆 / 百度搭子（V2.76）：单账号纯 HTTP（Cookie + csrftoken）。
        if (key == "qianfan") {
            if (!isReady("qianfan", s)) {
                return toJson(CheckinResult(
                    "qianfan", "fail",
                    "缺少 Cookie：点「登录百度智能云」在站内登录一次", ""), day).toString()
            }
            val r0 = doneOf("qianfan", HistoryStore.DEFAULT_ACCOUNT) ?: try {
                CheckinGate.run("qianfan", HistoryStore.DEFAULT_ACCOUNT, reuse = log) {
                    runBlocking { DumateCheckin.run(s.qfCookie) }
                }
            } catch (e: Exception) {
                CheckinResult("qianfan", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            if (log) store.upsert(day, r0, HistoryStore.DEFAULT_ACCOUNT)
            return toJson(r0, day).toString()
        }

        val mainKey = if (key == "workbuddy") wbAccountKey(s) else HistoryStore.DEFAULT_ACCOUNT
        val r = doneOf(key, mainKey) ?: CheckinGate.run(key, mainKey, reuse = log) {
            runBlocking {
                when (key) {
                    "workbuddy" -> WbAuth.runCheckin(ctx, s)
                    else -> CheckinResult(key, "fail", "未知服务")
                }
            }
        }
        if (log) {
            store.upsert(day, r, mainKey)
        }
        return toJson(r, day).toString()
    }

    /**
     * 单条签到结果转 JSON。
     *
     * day 必须由调用方传入「这次签到实际写进库的那一天」，不能内部写 today()：
     * 跨零点（或补签历史日期）时返回的 day 会和库里那条记录对不上。
     * http_status 本地拿不到（上游状态码没有落库），回 null 而不是假的 200。
     * time 与 day 统一用北京时区，避免设备时区不是 UTC+8 时两者不同步。
     */
    private fun toJson(r: CheckinResult, day: String = today()): JSONObject = JSONObject().apply {
        put("provider", r.provider); put("status", r.status)
        put("message", r.message); put("points", r.points)
        put("total_points", r.totalPoints); put("streak_days", r.streakDays)
        put("used_points", r.usedPoints)
        put("day", day); put("http_status", JSONObject.NULL)
        put("time", LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        put("elapsed", 0); put("dry_run", false)
    }


    // ── 推送通知 ──────────────────────────────────────────────────────
    /**
     * 测试推送。body 里带 kind/webhook_url/secret 时先用这次的临时配置发，
     * 让用户不必先保存也能验证地址对不对。
     */
    private fun testNotify(body: String): String {
        val cur = Notify.load(ctx)
        val tmp = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
        val url = tmp.optString("webhook_url", "").takeIf { it.isNotBlank() && !it.contains("***") }
        val cfg = Notify.Cfg(
            enabled = true,
            kind = Notify.normalize(tmp.optString("kind", cur.kind)),
            url = url ?: cur.url,
            secret = tmp.optString("secret", "").takeIf { it.isNotBlank() && !it.contains("***") } ?: cur.secret,
            // P1R1（BUGS-v2.2）：同 saveConfig —— 不透传 secretLost 会让「测试推送」
            // 这个纯读操作把密钥抹掉。
            secretLost = cur.secretLost,
        )
        if (cfg.url.isBlank()) return """{"ok":false,"error":"先填 Webhook 地址再测试"}"""
        val title = "签到中心 · 测试通知"
        val bodyText = buildString {
            append("这是一条测试消息，收到说明渠道配置正确。\n")
            append("时间：")
            append(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            append("\n设备：Android")
        }
        val r = runBlocking { Notify.push(ctx, cfg, title, bodyText) }
        appendNotifyLog(r.kind, title, r.ok, r.detail)
        return JSONObject().apply {
            put("ok", r.ok)
            put("kind", r.kind)
            put("detail", r.detail)
            if (!r.ok) put("error", r.detail)
        }.toString()
    }

    private fun notifyHistory(): String {
        val arr = JSONArray()
        ctx.getSharedPreferences("signhub_notify_log", Context.MODE_PRIVATE)
            .getString("log", "")?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                try {
                    val all = JSONArray(raw)
                    for (i in 0 until minOf(all.length(), 50)) arr.put(all.opt(i))
                } catch (_: Exception) {}
            }
        return JSONObject().put("logs", arr).toString()
    }

    private fun appendNotifyLog(kind: String, title: String, ok: Boolean, detail: String) {
        val p = ctx.getSharedPreferences("signhub_notify_log", Context.MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("log", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        val entry = JSONObject().apply {
            put("kind", kind)
            put("title", title)
            put("ok", ok)
            put("detail", detail.take(200))
            put("created_at", LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        }
        val next = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), 49)) next.put(arr.opt(i))
        p.edit().putString("log", next.toString()).apply()
    }

    // ── WorkBuddy 成长中心 ────────────────────────────────────────────
    private fun growth(refresh: Boolean): String {
        val acc = wbAccount()
            ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        if (!refresh) {
            WbAssets.loadCache(ctx, "growth_${acc.uid}")?.let { return it.toString() }
        }
        val o = runBlocking { WbGrowth.overview(acc) }
        if (o.optBoolean("ok", false)) WbAssets.saveCache(ctx, "growth_${acc.uid}", o, 120_000L)
        return o.toString()
    }

    private fun growthRunTasks(): String {
        val acc = wbAccount() ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        val rows = runBlocking { WbGrowth.runTasks(acc) }
        val ok = (0 until rows.length()).count { rows.optJSONObject(it)?.optBoolean("ok") == true }
        WbAssets.saveCache(ctx, "growth_${acc.uid}", JSONObject().put("stale", true), 0L)
        return JSONObject().apply {
            put("ok", ok > 0)
            put("done", ok)
            put("total", rows.length())
            put("results", rows)
        }.toString()
    }

    private fun growthClaimAll(): String {
        val acc = wbAccount() ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        val rows = runBlocking { WbGrowth.claimAll(acc) }
        val ok = (0 until rows.length()).count { rows.optJSONObject(it)?.optBoolean("ok") == true }
        return JSONObject().apply {
            put("ok", ok > 0 || rows.length() == 0)
            put("claimed", ok)
            put("total", rows.length())
            put("results", rows)
        }.toString()
    }

    private fun growthDaily(refresh: Boolean): String {
        val acc = wbAccount()
            ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        if (!refresh) {
            WbAssets.loadCache(ctx, "daily_${acc.uid}")?.let { return it.toString() }
        }
        val o = runBlocking { WbGrowth.dailyCard(acc) }
        if (o.optBoolean("ok", false)) WbAssets.saveCache(ctx, "daily_${acc.uid}", o, 120_000L)
        return o.toString()
    }

    private fun growthRunDaily(): String {
        val acc = wbAccount() ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        val rows = runBlocking { WbGrowth.runDaily(acc) }
        val ok = (0 until rows.length()).count { rows.optJSONObject(it)?.optBoolean("ok") == true }
        return JSONObject().apply {
            put("ok", ok > 0)
            put("done", ok)
            put("total", rows.length())
            put("results", rows)
        }.toString()
    }

    // ── 积分资产 / 过期预警 ───────────────────────────────────────────
    private fun wbAssets(refresh: Boolean): String {
        val acc = wbAccount()
            ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        val o = runBlocking { WbAssets.card(acc, useCache = !refresh, ctx = ctx) }
        return o.toString()
    }

    /**
     * 自动签到的运行状态：上次跑的时间/结果 + 下次排期 + 三个服务的就绪情况。
     *
     * 存在的意义：用户无法区分「没跑」和「跑了失败」。这两者的处理方式完全相反
     * （前者是 WorkManager/系统的锅，后者是凭据/风控的问题），必须先能看见。
     */
    private fun autoStatus(): String {
        val o = JSONObject()
        val c = cfg()
        val s = settings()
        o.put("enabled", c.schedulerEnabled)
        o.put("time", c.schedulerTime)
        o.put("jitter_min", c.schedulerJitter)

        val last = AutoCheckinScheduler.lastRun(ctx)
        if (last == null) {
            o.put("ran", false)
            o.put("last_at", JSONObject.NULL)
            o.put("last_title", "")
            o.put("last_body", "")
        } else {
            o.put("ran", true)
            o.put("last_at", last["at"] as Long)
            o.put("last_title", last["title"] as String)
            o.put("last_body", last["body"] as String)
        }

        // 下次排期（V2.51）：主调度是 AlarmManager 精确闹钟，时刻持久化在 LocalConfig.nextPick。
        //
        // 为什么不再读 WorkManager：定时签到已经从 WorkManager 迁到 AlarmManager。
        // WorkManager 现在只负责「立即签到」，拿它的 workInfo 报「下次自动签到」会一直
        // 显示 NONE / 上一次的残留状态，等于给用户一个假的诊断面板。
        val nextPick = AutoCheckinScheduler.nextPickAt(ctx)
        o.put("next_pick", nextPick)
        o.put("next_pick_at", if (nextPick > 0) {
            java.time.Instant.ofEpochMilli(nextPick)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        } else "")
        o.put("queued", nextPick > System.currentTimeMillis())
        o.put("queued_state", if (nextPick > 0) "ALARM" else "NONE")

        // 精确定时授权状态：Android 14+ 全新安装默认不给，没有它闹钟会被推迟数小时。
        // 这是「到点没签」最常见的原因，必须让用户看得见。
        o.put("exact_ok", AutoCheckinScheduler.canExact(ctx))
        o.put("sdk", Build.VERSION.SDK_INT)

        // 就绪情况：这三项任一为空，那次自动签到的结果必然是「未执行」
        o.put("wb_ready", s.wbToken.isNotBlank() || runCatching {
            WbAccountStore.all(ctx).any { it.accessToken.isNotBlank() }
        }.getOrDefault(false))
        o.put("trae_ready", s.traeToken.isNotBlank() || s.traeSession.isNotBlank())
        o.put("trae_has_session", s.traeSession.isNotBlank())
        o.put("trae_has_token", s.traeToken.isNotBlank())
        o.put("trae_device_id", s.traeDeviceId)

        // 五家的就绪明细（用户 m21921）。
        //
        // 原来这里只报 WorkBuddy 与 Trae 两家 —— 那是只有两家时的实现，
        // 后来加了 Qoder / MiniMax / 华为码道却忘了同步，结果「自动签到没跑成」
        // 时面板上根本看不到是哪个新服务缺凭据，只能一家家点「测试签到」去猜。
        //
        // 这里给一份通用结构，前端直接遍历渲染，以后再加 provider 不用改前端。
        val provs = org.json.JSONArray()
        runCatching {
            val wbOk = o.optBoolean("wb_ready", false)
            provs.put(JSONObject().put("key", "workbuddy").put("label", "WorkBuddy")
                .put("ready", wbOk)
                .put("detail", if (wbOk) {
                    val n = runCatching { WbAccountStore.all(ctx).count { it.accessToken.isNotBlank() } }.getOrDefault(0)
                    if (n > 0) "主账号 + ${n} 个多账号" else "主账号"
                } else "未登录（会跳过）"))

            val trOk = o.optBoolean("trae_ready", false)
            provs.put(JSONObject().put("key", "traesolo").put("label", "Trae Solo")
                .put("ready", trOk)
                .put("detail", if (!trOk) {
                    "未就绪（device_id=${s.traeDeviceId.ifBlank { "空" }}）"
                } else {
                    val n = runCatching { TraeAccountStore.all(ctx).count { it.token.isNotBlank() || it.session.isNotBlank() } }.getOrDefault(0)
                    val tail = if (s.traeSession.isNotBlank()) " · 有 session 可续期"
                    else if (s.traeToken.isNotBlank()) " · 仅 token（8 小时后失效）" else ""
                    (if (n > 0) "主账号 + $n 个多账号" else "主账号") + tail
                }))

            // Qoder：有 token 或 refreshToken 就算就绪（refreshToken 能自动续期一年）
            val qdOk = isReady("qoder", s)
            val qdN = runCatching { QoderAccountStore.all(ctx).count { it.token.isNotBlank() || it.refresh.isNotBlank() } }.getOrDefault(0)
            provs.put(JSONObject().put("key", "qoder").put("label", "Qoder CN")
                .put("ready", qdOk)
                .put("detail", if (!qdOk) "未配置（会跳过）"
                    else (if (qdN > 0) "主账号 + $qdN 个多账号" else "主账号") +
                        (if (s.qoderRefresh.isNotBlank()) " · refreshToken 自动续期" else " · 仅 token")))

            val mmOk = isReady("minimax", s)
            val mmN = runCatching { MmAccountStore.all(ctx).count { it.token.isNotBlank() } }.getOrDefault(0)
            provs.put(JSONObject().put("key", "minimax").put("label", "MiniMax Code")
                .put("ready", mmOk)
                .put("detail", if (!mmOk) "未配置（会跳过）"
                    else if (mmN > 0) "主账号 + $mmN 个多账号" else "主账号"))

            val hwAccsDiag = hwList(s).filter { hwUsable(it.cookie) }
            val hwOk = hwAccsDiag.isNotEmpty()
            provs.put(JSONObject().put("key", "huawei").put("label", "华为码道")
                .put("ready", hwOk)
                .put("detail", if (!hwOk) "未登录（会跳过）"
                    else if (hwAccsDiag.size > 1) "${hwAccsDiag.size} 个账号 · 会话就绪 · 余额可读"
                    else "会话就绪 · 余额可读"))

            // 豆包（V2.66）：单账号，判据与 isReady 一致。
            // V2.75 起搁置（DoubaoWebCheckin.ENABLED=false）：整条不列，界面上不出现。
            if (DoubaoWebCheckin.ENABLED) {
                val dbOk = isReady("doubao", s)
                provs.put(JSONObject().put("key", "doubao").put("label", "豆包")
                    .put("ready", dbOk)
                    .put("detail", if (!dbOk) "未登录（会跳过）"
                        else "Cookie 就绪 · 免签名直调"))
            }

            // 智谱清言 / 百度千帆（V2.76）：单账号，判据与 isReady 一致。
            val zpOk = isReady("zhipu", s)
            provs.put(JSONObject().put("key", "zhipu").put("label", "智谱清言")
                .put("ready", zpOk)
                .put("detail", if (!zpOk) "未登录（会跳过）"
                    else "Cookie 就绪 · 签到前自动续期 access token"))

            val qfOk = isReady("qianfan", s)
            provs.put(JSONObject().put("key", "qianfan").put("label", "百度千帆")
                .put("ready", qfOk)
                .put("detail", if (!qfOk) "未登录（会跳过）"
                    else "Cookie 就绪 · csrftoken 已取"))
        }
        o.put("providers", provs)
        return o.toString()
    }

    /**
     * 强制重查两个平台的「当前持有积分」并写缓存，返回最新 /api/status。
     *
     * 前端「刷新余额」调这里。原先只刷 WorkBuddy 余额卡，Trae 那行永远是
     * 「积分稍后刷新」——因为它要单独查 ide_user_ent_usage，而且 token 过了 8 小时
     * 必须先拿 session 换新，否则必然 401。
     *
     * 关键：查到的新读数必须**同时**写 SharedPreferences 缓存与当天 SQLite 记录。
     * 首页卡片与 Token 健康的 `total_points` 优先取当天记录（providerRow 第 230 行），
     * 只写缓存的话页面纹丝不动 —— 这正是「点了刷新只有资产卡变」的原因。
     */
    private fun refreshTotals(): String {
        val s = settings()
        val totals = JSONObject()
        val day = today()

        // WorkBuddy：逐账号查（主账号 + 多账号列表），每个账号的读数只写自己那一行。
        //
        // 用户 m18105：成长中心把账号切到「账号 2」后，账号 1 的积分变成了账号 2 的。
        // 原因是这里用 wbAccount()（= 当前选中的账号）去查，却把结果写进
        // wbAccountKey(s)（= 主账号）那一行 —— 选中谁，主账号的数字就被谁覆盖。
        val wbKey = wbAccountKey(s)
        val wbAccs: List<WbAccount> = buildList {
            if (s.wbToken.isNotBlank()) {
                add(WbAccount(
                    id = HistoryStore.DEFAULT_ACCOUNT,
                    uid = s.wbUid,
                    nickname = "主账号",
                    enterpriseId = s.wbEnterpriseId,
                    accessToken = s.wbToken,
                    refreshToken = s.wbRefreshToken,
                    domain = s.wbDomain,
                    variant = if (s.wbGlobal) "ai" else "cn",
                ))
            }
            runCatching { WbAccountStore.all(ctx) }.getOrDefault(emptyList())
                .filter { it.accessToken.isNotBlank() && !isSameAsMainAccount(it, s) }
                .forEach { add(it) }
        }

        // ── 三路网络请求并行发起，再回主线程串行落库 ──
        //
        // 原来 WorkBuddy 余额 → WorkBuddy 签到状态 → Trae 余额 一路串行，
        // 每步 300~500ms，加起来 1.2~1.5 秒；三路互不依赖，并行后 ≈ 最慢那步。
        val traeRenewed = arrayOfNulls<String>(1)
        val traeRenewedAcc = java.util.concurrent.ConcurrentHashMap<String, String>()
        // BUGS-v2.63-287 P3-2：Qoder 余额路径也在续期，同样会把新 refresh_token 丢掉。
        // 与签到路径同一套做法：先收进 Map，回主线程串行落库。
        val qdRenewed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        val tasks = mutableListOf<() -> Any?>()

        // WorkBuddy：每个账号一个任务（读自己的余额），外加一个「当前选中账号」的签到状态。
        wbAccs.forEach { acc ->
            tasks.add { runCatching { runBlocking { WbApi.fetchTotal(acc) } }.getOrDefault(-1) }
        }
        tasks.add {
            wbAccs.firstOrNull()?.let {
                runCatching { WbApi.fetchStatus(it) }.getOrNull()
            }
        }
        // Trae：逐账号查（每个账号一次 usage 请求，同时拿剩余 + 已用）
        val traeAccs = traeList(s)
        traeAccs.forEach { acc ->
            tasks.add {
                if (acc.deviceId.isBlank()) Pair(-1, -1)
                else runCatching {
                    runBlocking {
                        val sums = LocalCheckin.traeUsageSums(acc.token, acc.deviceId, acc.global)
                        if (sums.first < 0 && acc.session.isNotBlank()) {
                            // token 过期：先换新再查一次，换到的 token 回主线程落盘
                            val fresh = LocalCheckin.traeExchangeToken(acc.session, acc.global)
                            if (fresh != null) {
                                if (acc.id == HistoryStore.DEFAULT_ACCOUNT) traeRenewed[0] = fresh
                                else traeRenewedAcc[acc.id] = fresh
                                LocalCheckin.traeUsageSums(fresh, acc.deviceId, acc.global)
                            } else sums
                        } else sums
                    }
                }.getOrDefault(Pair(-1, -1))
            }
        }

        // Qoder：逐账号查
        val qoderAccs = qoderList(s)
        qoderAccs.forEach { acc ->
            tasks.add {
                runCatching {
                    runBlocking {
                        LocalCheckin.qoderHeldAndUsed(
                            acc.region.ifBlank { "cn" }, acc.token, acc.uid, acc.machineId, "",
                            acc.refresh,
                            onRenewed = { t, rf -> qdRenewed[acc.id] = Pair(t, rf) })
                    }
                }.getOrDefault(Pair(-1, -1))
            }
        }

        // MiniMax：逐账号查
        val mmAccs = mmList(s)
        mmAccs.forEach { acc ->
            tasks.add {
                runCatching {
                    runBlocking {
                        LocalCheckin.minimaxHeldAndUsed(acc.token, acc.userId, acc.deviceId, "")
                    }
                }.getOrDefault(Pair(-1, -1))
            }
        }

        // 华为码道：逐账号查可用积分（走签到同域的 /chat/snap-manager/v1/statistics/plugin）
        // 用户 m24258：改成多账号，每账号一个任务。
        val hwAccs = hwList(s)
        hwAccs.forEach { acc ->
            tasks.add {
                runCatching {
                    runBlocking { LocalCheckin.huaweiHeld(acc.cookie, s.hwPortalCookie) }
                }.getOrDefault(-1)
            }
        }

        // 智谱清言 / 百度千帆（V2.76）：单账号，各一个任务。
        // 两家都没有独立的「只读余额」端点，直接复用 probe（智谱会顺带 refresh 一次，
        // 百度只打 loginBonusInfo 只读接口），拿不到就 -1，界面显示「—」。
        val zpReadyT = isReady("zhipu", s)
        val qfReadyT = isReady("qianfan", s)
        if (zpReadyT) {
            tasks.add {
                runCatching {
                    runBlocking { ZhipuCheckin.probe(s.zpCookie, s.zpDeviceId) }
                }.getOrNull()?.optInt("total", -1) ?: -1
            }
        }
        if (qfReadyT) {
            tasks.add {
                runCatching {
                    runBlocking { DumateCheckin.probe(s.qfCookie) }
                }.getOrNull()?.optInt("total", -1) ?: -1
            }
        }

        val out = parallel(tasks)
        // WorkBuddy 先占 wbAccs.size 个槽位（每个账号自己的余额），再一个是签到状态文档
        val wbVals = wbAccs.indices.map { (out.getOrNull(it) as? Int) ?: -1 }
        val stDoc = out.getOrNull(wbAccs.size) as? JSONObject
        var cursor = wbAccs.size + 1
        fun nextPair(): Pair<Int, Int> {
            val p = out.getOrNull(cursor++) as? Pair<*, *>
            return Pair((p?.first as? Int) ?: -1, (p?.second as? Int) ?: -1)
        }
        fun nextInt(): Int = (out.getOrNull(cursor++) as? Int) ?: -1
        val traePairs = traeAccs.map { nextPair() }
        val qoderPairs = qoderAccs.map { nextPair() }
        val mmPairs = mmAccs.map { nextPair() }
        val hwVals = hwAccs.map { nextInt() }
        val zpVal = if (zpReadyT) nextInt() else -1
        val qfVal = if (qfReadyT) nextInt() else -1

        // WorkBuddy：逐账号落库。
        //
        // 用户 m18105：切换成长中心账号后，账号 1 的积分被账号 2 的覆盖了。
        // 修法就是把「查询对象」和「落库对象」绑在同一个账号上 —— 原来查的是
        // 当前选中账号、写的是主账号那一行，两个身份不一致。
        var wbSum = 0
        var wbAny = false
        wbAccs.forEachIndexed { i, acc ->
            val v = wbVals.getOrElse(i) { -1 }
            if (v < 0) return@forEachIndexed
            val key = if (acc.id == HistoryStore.DEFAULT_ACCOUNT) wbKey else acctKey(acc.id)
            store.updateTotals(day, "workbuddy", total = v, accountKey = key)
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                runCatching { WbAccountStore.update(ctx, acc.copy(totalPoints = v)) }
            }
            wbSum += v
            wbAny = true
        }
        if (wbAny) {
            // 缓存口径：优先存「主账号自己」的读数（用户 m13804 要求卡片只展示主账号），
            // 主账号查不到时才退回求和，免得卡片显示成「两个号相加」。
            val mainVal = wbVals.firstOrNull() ?: -1
            cacheTotal("workbuddy", if (mainVal >= 0) mainVal else wbSum)
            totals.put("workbuddy", wbSum)
        }
        // 签到状态只属于「主账号」这一行（WB 的 fetchStatus 是按凭据查的，没有多账号维度）
        if (stDoc != null && stDoc.optBoolean("ok", false)) {
            val stk = stDoc.optInt("streak_days", -1)
            if (stk >= 0) {
                store.updateTotals(day, "workbuddy", streak = stk, accountKey = wbKey)
                totals.put("streak", stk)
            }
        }

        // Trae / Qoder / MiniMax：逐账号落库（主账号用 DEFAULT_ACCOUNT，其余用各自 id），
        // 汇总值同时写进 totals 给前端资产卡用。
        var traeSum = 0; var traeUsedSum = 0; var traeAny = false
        traeAccs.forEachIndexed { i, acc ->
            val (total, used) = traePairs.getOrElse(i) { Pair(-1, -1) }
            if (total >= 0) {
                store.updateTotals(day, "traesolo", total = total,
                    accountKey = acctKey(acc.id), used = used)
                if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                    runCatching {
                        TraeAccountStore.update(ctx, acc.copy(totalPoints = total, usedPoints = used))
                    }
                }
                traeSum += total
                if (used >= 0) traeUsedSum += used
                traeAny = true
            }
        }
        if (traeAny) {
            cacheTotal("traesolo", traeSum)
            totals.put("traesolo", traeSum)
            if (traeUsedSum > 0) {
                cacheUsed("traesolo", traeUsedSum)
                totals.put("traesolo_used", traeUsedSum)
            }
        }

        var qdSum = 0; var qdUsedSum = 0; var qdAny = false
        qoderAccs.forEachIndexed { i, acc ->
            // BUGS-v2.63-287 P3-2：余额路径续期换出的新凭据在调用线程回写
            qdRenewed[acc.id]?.let { (t, rf) ->
                runCatching {
                    if (acc.id == HistoryStore.DEFAULT_ACCOUNT) {
                        runBlocking {
                            val cur = SettingsStore.flow(ctx).first()
                            SettingsStore.save(ctx, cur.copy(
                                qoderToken = t.ifBlank { cur.qoderToken },
                                qoderRefresh = rf.ifBlank { cur.qoderRefresh }))
                        }
                    } else {
                        QoderAccountStore.update(ctx, acc.copy(token = t, refresh = rf))
                    }
                }
            }
            val (total, used) = qoderPairs.getOrElse(i) { Pair(-1, -1) }
            if (total >= 0) {
                store.updateTotals(day, "qoder", total = total,
                    accountKey = acctKey(acc.id), used = used)
                if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                    runCatching {
                        QoderAccountStore.update(ctx, acc.copy(totalPoints = total, usedPoints = used))
                    }
                }
                qdSum += total
                if (used >= 0) qdUsedSum += used
                qdAny = true
            }
        }
        if (qdAny) {
            cacheTotal("qoder", qdSum)
            totals.put("qoder", qdSum)
            if (qdUsedSum > 0) {
                cacheUsed("qoder", qdUsedSum)
                totals.put("qoder_used", qdUsedSum)
            }
        }

        var mmSum = 0; var mmUsedSum = 0; var mmAny = false
        mmAccs.forEachIndexed { i, acc ->
            val (total, used) = mmPairs.getOrElse(i) { Pair(-1, -1) }
            if (total >= 0) {
                store.updateTotals(day, "minimax", total = total,
                    accountKey = acctKey(acc.id), used = used)
                if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                    runCatching {
                        MmAccountStore.update(ctx, acc.copy(totalPoints = total, usedPoints = used))
                    }
                }
                mmSum += total
                if (used >= 0) mmUsedSum += used
                mmAny = true
            }
        }
        if (mmAny) {
            cacheTotal("minimax", mmSum)
            totals.put("minimax", mmSum)
            if (mmUsedSum > 0) {
                cacheUsed("minimax", mmUsedSum)
                totals.put("minimax_used", mmUsedSum)
            }
        }

        // 华为码道：逐账号落库（用户 m24258）
        var hwSum = 0; var hwAny = false
        hwAccs.forEachIndexed { i, acc ->
            val v = hwVals.getOrElse(i) { -1 }
            if (v < 0) return@forEachIndexed
            store.updateTotals(day, "huawei", total = v, accountKey = acctKey(acc.id), used = -1)
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                runCatching { HwAccountStore.update(ctx, acc.copy(totalPoints = v)) }
            }
            hwSum += v
            hwAny = true
        }
        if (hwAny) {
            cacheTotal("huawei", hwSum)
            totals.put("huawei", hwSum)
        }

        // 智谱清言 / 百度千帆（V2.76）：单账号，读数只写主账号那一行。
        if (zpVal >= 0) {
            store.updateTotals(day, "zhipu", total = zpVal, accountKey = HistoryStore.DEFAULT_ACCOUNT, used = -1)
            cacheTotal("zhipu", zpVal)
            totals.put("zhipu", zpVal)
        }
        if (qfVal >= 0) {
            store.updateTotals(day, "qianfan", total = qfVal, accountKey = HistoryStore.DEFAULT_ACCOUNT, used = -1)
            cacheTotal("qianfan", qfVal)
            totals.put("qianfan", qfVal)
        }
        // WorkBuddy 的累计已用：资产卡正面用 wb_assets 的 remaining，
        // 翻面用同一次查询里的 used_total，落库后首页/翻面都能读到。
        //
        // 与上面同一条原则（用户 m18105）：读数属于哪个账号，就写哪个账号那一行。
        // wbAssets() 内部走 wbAccount()（= 成长中心当前选中的账号），
        // 所以这里必须按「选中的那个账号」算 key，不能一律写主账号。
        val wbSel = wbAccount()
        val wbUsedKey = if (wbSel == null || wbSel.id == HistoryStore.DEFAULT_ACCOUNT) wbKey
        else acctKey(wbSel.id)
        val wbAssetsUsed = runCatching {
            val a = JSONObject(wbAssets(false))
            if (a.optBoolean("ok", false)) a.optDouble("used_total", -1.0).toInt() else -1
        }.getOrDefault(-1)
        if (wbAssetsUsed >= 0) {
            cacheUsed("workbuddy", wbAssetsUsed)
            store.updateTotals(day, "workbuddy", used = wbAssetsUsed, accountKey = wbUsedKey)
            totals.put("workbuddy_used", wbAssetsUsed)
        }
        traeRenewed[0]?.let { fresh ->
            runCatching {
                runBlocking { SettingsStore.save(ctx, s.copy(traeToken = fresh)) }
            }
        }
        if (traeRenewedAcc.isNotEmpty()) {
            runCatching {
                traeAccs.filter { it.id != HistoryStore.DEFAULT_ACCOUNT }.forEach { acc ->
                    val fresh = traeRenewedAcc[acc.id] ?: return@forEach
                    TraeAccountStore.update(ctx, acc.copy(token = fresh))
                }
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("totals", totals)
            put("status", JSONObject(status()))
        }.toString()
    }

    /**
     * 官网连登热力图。统计页优先用它，本地 SQLite 只作兜底 ——
     * 因为本地库只记本机签过的日子，重装/换机/首次使用全是空的。
     */
    private fun heat(): String {
        val acc = wbAccount()
            ?: return """{"ok":false,"error":"未配置 WorkBuddy 账号"}"""
        // 缓存 10 分钟：热力图一年几百个格子，没必要每次切 tab 都打上游
        WbAssets.loadCache(ctx, "heat_${acc.uid}")?.let { return it.toString() }
        val o = runBlocking { WbGrowth.heatCells(acc) }
        if (o.optBoolean("ok", false)) WbAssets.saveCache(ctx, "heat_${acc.uid}", o, 600_000L)
        return o.toString()
    }
}

/** 定时配置（本地模式用 SharedPreferences 就够）。 */
data class LocalConfig(
    val schedulerEnabled: Boolean = true,
    val schedulerTime: String = "08:30",
    /** 随机浮动上限（分钟，0~240）：实际执行 = 目标时间 + random(0..jitter)。0 表示不加浮动。 */
    val schedulerJitter: Int = 30,
    /**
     * 已排定的下次执行时刻（毫秒，0 = 未排）。
     *
     * V2.51 引入：换成 AlarmManager 精确闹钟后，「已排时刻」不能再靠 WorkManager 的
     * 数据库查（闹钟是系统级记录）。持久化它是为了两件事：
     *   ① 开机 / 打开 App 时如果这个时刻还在未来，就原样重排，不重新抽
     *      —— 否则用户上午打开一次 App，今天 08:30 那次就被顶成明天
     *   ② 设置页可以显示「下次自动签到：MM-dd HH:mm」而不必去问系统
     */
    val nextPick: Long = 0L,
    /**
     * 网络类失败后的补签时刻（毫秒，0 = 没有补签）。
     * 同一天只补一次，避免上游持续故障时每 10 分钟打一遍。
     */
    val retryAt: Long = 0L,
) {
    companion object {
        private const val PREF = "local_config"
        fun load(ctx: Context): LocalConfig {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return LocalConfig(
                schedulerEnabled = p.getBoolean("enabled", true),
                schedulerTime = p.getString("time", "08:30") ?: "08:30",
                schedulerJitter = p.getInt("jitter", 30).coerceIn(0, 240),
                nextPick = p.getLong("next_pick", 0L),
                retryAt = p.getLong("retry_at", 0L),
            )
        }

        fun save(ctx: Context, c: LocalConfig) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", c.schedulerEnabled)
                .putString("time", c.schedulerTime)
                .putInt("jitter", c.schedulerJitter.coerceIn(0, 240))
                .putLong("next_pick", c.nextPick)
                .putLong("retry_at", c.retryAt)
                .apply()
        }
    }
}
