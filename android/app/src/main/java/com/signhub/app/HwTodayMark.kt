package com.signhub.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 华为码道的「今日已签」本地标记（手机端）。
 *
 * ## 为什么需要它（BUGS-v2.41 P1）
 * 电脑端 `backend/signbot/huawei.py` 一直有 `huawei_today_done.json`：今日已签成功过
 * 就直接返回 `already`，不再打华为接口。手机端**完全没有这套标记**，于是：
 *
 * 1. 手机上签完华为码道；
 * 2. 过一会儿（华为会话静置 30~60 分钟就失效）再点一次华为卡片；
 * 3. `huawei()` 真打上游 → 拿到 401 → 返回 `fail`「华为登录态已失效」；
 * 4. `HistoryStore.upsert()` 用 `CONFLICT_REPLACE` 把当天那条 success 记录**整行替换**成 fail。
 *
 * 连带后果：`allDoneToday()` 的 `allOkOf("huawei", 1)` 也变 false →「一键全部签到」
 * 不再短路 → 用户反复触发整轮签到。
 *
 * ## 设计
 * - 存 SharedPreferences，键按 **账号身份**（`HwAccount.identityOf`：优先 `agencyID`、
 *   拿不到回退整条 Cookie）分桶 —— 换账号不继承标记；**同一账号重登（会话 Cookie 变了、
 *   `agencyID` 没变）仍然命中同一个桶**。BUGS-v2.46 P1：早先直接用整条 Cookie 的哈希，
 *   重登一次桶名就变，「今日已签」标记读不到，当天会再去真打一次上游。
 * - 日期口径用 **Asia/Shanghai**，与 `db.today_str(8)` 和 `HistoryStore.now()` 一致；
 *   用设备本地时区会在跨零点附近错开一天（设备不在 UTC+8 时）。
 * - 只记「成功」：`fail` 绝不写标记，否则用户当天再也签不了。
 * - 标记里**连积分一起存**（BUGS-v2.46 P3）：命中标记时若还去读一次余额，就等于
 *   又 ping 了两条上游接口，而注释承诺的正是「不再 ping 华为」—— 会话静置 30~60 分钟
 *   就失效，多 ping 一次就多一次「已签到被误报成登录态过期」的机会。
 */
object HwTodayMark {

    private const val PREF = "signhub_hw_done"

    /**
     * 分桶键 = 账号身份（`HwAccount.identityOf`）经 `HwAccount.keyOf` 派生的 12 位十六进制。
     *
     * **必须与 `HwAccount.id`、`HwVerified.key` 用同一个口径**：早先这里用整条 Cookie 的哈希，
     * 而 `HwAccount` 后来改成优先 `agencyID`，两边不一致就会出现「账号认得出、
     * 标记读不到」（BUGS-v2.46 P1 的第三个后果）。BUGS-v2.48 P3-2 又把哈希本身
     * 收敛到 `HwAccount.keyOf`，免得这里 md5、那里 hashCode 各写一套。
     */
    private fun bucket(cookie: String): String {
        val k = HwAccount.keyOf(cookie)
        return if (k.isEmpty()) "" else "hw_" + k
    }

    /** 今日（Asia/Shanghai）日期串。 */
    fun today(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(Date())
    }

    /**
     * 今天是否已经签成功过。是则返回当时记录的消息（给界面显示），否则空串。
     *
     * 存储格式 `日期\u0001消息`（旧）或 `日期\u0001消息\u0001积分`（新，见 [mark]）。
     */
    fun get(ctx: Context, cookie: String): String = read(ctx, cookie)?.first ?: ""

    /** 同上，但连当时记下的积分一起返回；没记积分时是 -1。 */
    fun getWithPoints(ctx: Context, cookie: String): Pair<String, Int>? = read(ctx, cookie)

    private fun read(ctx: Context, cookie: String): Pair<String, Int>? {
        val k = bucket(cookie)
        if (k.isEmpty()) return null
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = p.getString(k, "") ?: ""
        if (raw.isEmpty()) return null
        val parts = raw.split("\u0001")
        if (parts.size < 2) return null
        if (parts[0] != today()) {
            // 跨日：顺手清掉，避免长期堆积
            runCatching { p.edit().remove(k).apply() }
            return null
        }
        val pts = parts.getOrNull(2)?.toIntOrNull() ?: -1
        return parts[1] to pts
    }

    /**
     * 记一次「今天签成功了」。message 与当时的积分都存下来给界面复用。
     *
     * @param amount 当时读到的账号累计积分；读不到传 -1（下次命中标记时会再读一次）。
     */
    fun mark(ctx: Context, cookie: String, message: String, amount: Int = -1) {
        val k = bucket(cookie)
        if (k.isEmpty()) return
        val v = today() + "\u0001" + message.replace("\u0001", " ") + "\u0001" + amount
        runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(k, v).apply()
        }
    }

    /** 清掉某份 Cookie 的标记（重置凭据 / 换号时用）。 */
    fun clear(ctx: Context, cookie: String) {
        val k = bucket(cookie)
        if (k.isEmpty()) return
        runCatching { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(k).apply() }
    }

    /** 清空所有标记（重置全部凭据时用）。 */
    fun clearAll(ctx: Context) {
        runCatching { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}

/**
 * 华为「这份 Cookie 到底登录了没有」的持久化结论（BUGS-v2.46 P2）。
 *
 * ## 为什么不能只看 Cookie
 * 华为对**未登录访客也会下发匿名 `J_SESSION_ID`**，所以 `hwHasSession()`
 * （判字符串包含）成立 ≠ 已登录。真正能判的只有 `LocalCheckin.hwProbe()`，
 * 但它要打一次上游 —— 而 `isReady()` 会被 `/api/status` 每 25 秒轮询触发，
 * 在状态接口里同步打上游会把响应从毫秒拖到秒级（电脑端 `_proxy_check_cached`
 * 的 60 秒 TTL 就是同一个坑）。
 *
 * ## 做法
 * 采集/验证那一刻的结论**存下来**，`isReady()` 只读本地记录：
 * - `hwProbe` 返回 `true`  → 记 `true`
 * - `hwProbe` 返回 `false` → 记 `false`（这份 Cookie 是匿名会话，别把它算成可用账号）
 * - `hwProbe` 返回 `null`（网络异常）→ **不改记录**。判断不了不等于没登录，
 *   把它记成 `false` 会让网络抖一下就把用户的华为账号判死。
 *
 * 没有记录（`null`）时按**可用**处理 —— 老版本配置的账号本来就没这条记录，
 * 升级后不能突然全部变成「未就绪」（那会让 `allDoneToday` 把华为从 active 里摘掉，
 * 用户看到的是「签到按钮变灰」，属于比原 bug 更糟的回归）。
 *
 * ## `false` 必须有失效通路（BUGS-v2.48 P1）
 * 键只由 `identityOf` 决定，同一账号重新登录键完全相同。而 `set(null)` 什么都不写，
 * 于是这条链会把人锁死：某次签到拿到 401 → 记 `false`；用户按提示重新登录、华为确实
 * 登录成功了；但那一刻网络抖动 → `hwProbe` 返回 `null` → 不写 → **旧的 `false` 原封不动**。
 * 结果首页一直显示「未登录（会跳过）」，而用户明明已经重新登录，唯一自愈口是
 * 「重置凭据」（会连另外四家的凭据一起清掉）。华为会话本来就 30~60 分钟过期，
 * 401 是高频事件，所以这个组合并不罕见。
 *
 * 两条通路同时补上：
 * 1. **新 Cookie 即视为新证据** —— `persistHw` 落库前先 [clear]，把结论清回「未知」；
 * 2. **`false` 带时间戳** —— 存 `"0@<epochMillis>"`，超过 [FALSE_TTL_MS] 就当作没有结论
 *    （顺带清掉残留），这样即使某次没走到 `persistHw`，结论也会自己松绑。
 *
 * 两条都不能省：①解决「重新登录成功」这个主路径；②解决「结论是历史残留、
 * 用户根本没重新登录过，只是换了网络环境」这类边角。
 */
object HwVerified {

    private const val PREF = "signhub_hw_verified"

    /**
     * `false`（未登录）结论的有效期：2 小时。
     *
     * 取值理由：华为会话 Cookie 静置 30~60 分钟就失效，所以「未登录」这个判断本身
     * 只有半小时左右的新鲜度；2 小时足够覆盖一次正常签到流程，又不至于让一条
     * 陈旧结论把人锁到第二天。**只对 `false` 生效** —— `true` 是「这份 Cookie
     * 确实能签成」的强证据，没有过期理由（Cookie 失效后签到会自己把它改回 false）。
     */
    private const val FALSE_TTL_MS = 2L * 60L * 60L * 1000L

    /** 与 `HwTodayMark` 共用同一个键派生函数（BUGS-v2.48 P3-2），换账号不继承结论。 */
    private fun key(cookie: String): String {
        val k = HwAccount.keyOf(cookie)
        return if (k.isEmpty()) "" else "v_" + k
    }

    /**
     * 读结论：`true` 已确认登录 / `false` 确认是未登录 / `null` 没结论（按可用处理）。
     *
     * 存储格式 `"1@<ms>"` / `"0@<ms>"`；兼容旧的裸 `"1"` / `"0"`（按「刚写入」处理，
     * 免得升级那一刻把所有人的 `false` 一次性作废）。
     */
    fun get(ctx: Context, cookie: String): Boolean? {
        val k = key(cookie)
        if (k.isEmpty()) return null
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = p.getString(k, "") ?: ""
        if (raw.isEmpty()) return null
        val at = raw.indexOf('@')
        val flag = if (at < 0) raw else raw.substring(0, at)
        val ts = if (at < 0) 0L else raw.substring(at + 1).toLongOrNull() ?: 0L
        return when (flag) {
            "1" -> true
            "0" -> {
                // 没有时间戳（旧格式）→ 视为刚写入，不立刻作废；但下次 set 会补上时间戳。
                val expired = ts > 0L && System.currentTimeMillis() - ts > FALSE_TTL_MS
                if (expired) {
                    runCatching { p.edit().remove(k).apply() }
                    null
                } else {
                    false
                }
            }
            else -> null
        }
    }

    /** 记结论。`null` 表示「判断不了」，此时**什么都不写**（见类文档）。 */
    fun set(ctx: Context, cookie: String, verdict: Boolean?) {
        if (verdict == null) return
        val k = key(cookie)
        if (k.isEmpty()) return
        val v = (if (verdict) "1" else "0") + "@" + System.currentTimeMillis()
        runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(k, v).apply()
        }
    }

    /**
     * 清掉某个账号的结论，回到「未知」。
     *
     * 两个用处（BUGS-v2.48 P1）：`persistHw` 保存新 Cookie 前先清（新 Cookie = 新证据）、
     * 删除账号时清（否则同一天用同一份 Cookie 加回来还带着旧结论）。
     */
    fun clear(ctx: Context, cookie: String) {
        val k = key(cookie)
        if (k.isEmpty()) return
        runCatching { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(k).apply() }
    }

    /**
     * 这份 Cookie 是否「能用」= 有会话名 **且** 没被证实是未登录。
     *
     * 三个入口（`LocalApi.isReady` / `LocalApi.checkinAll` / `AutoCheckinWorker`）必须
     * 用同一个判据，否则会出现「就绪说没配好、应签数又要求 1 条记录」的死结。
     */
    fun usable(ctx: Context, cookie: String): Boolean =
        LocalCheckin.hwHasSession(cookie) && get(ctx, cookie) != false

    fun clearAll(ctx: Context) {
        runCatching { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}

/**
 * 华为签到的统一入口：**先查今日标记再打上游**，成功才写标记。
 *
 * 三个调用点（`LocalApi.checkinAll`、`LocalApi.checkinOne`、`AutoCheckinWorker`）
 * 都走这里，避免「某条路径忘了判标记」这种跨端不一致（BUGS-v2.41 P1 就是这么来的：
 * 电脑端有标记、手机端没有）。
 */
object HwCheckin {

    /**
     * 签一次华为码道。
     *
     * P3-1（BUGS-v2.46）：早先有个 `reuse: CheckinResult? = null` 形参，5 个调用点
     * 全都没传 —— 死参数。删掉它，免得后来人以为存在「预置结果」这条通路。
     * 「刚签过别再打」由 `CheckinGate` 的复用表负责，「今天签过」由 [HwTodayMark] 负责。
     *
     * ## 静默重登（V2.79）
     *
     * 用户 m05846 要求「Cookie 不过期、自动续签」。华为没有 Trae 那种换发端点
     * （实测见 [HwRenew] 的类文档），但 auth 域的长期凭据还在时，用一次不可见
     * WebView 打开 `chat/home` 就能让它自己 302 往返、下发新 `SESSION_ID`。
     *
     * 触发条件**只限「明确判定为登录态问题」**：
     *   · `authFailed == true`（`LocalCheckin.huawei` 的 401 / 空响应 / 非 JSON 三条路径
     *     都会置它，不靠匹配中文文案 —— BUGS-v2.48 P2-2 的教训）；
     *   · 或者 cookie 里压根没有会话名（`hwHasSession` 为假），此时本来就要重登。
     *
     * 不在这里判断「cookie 快过期」：华为的 SESSION_ID 没有 `Expires`（Session 级），
     * 无从得知还剩多久，只能等它真的失效。网络错误（`authFailed == false` 且
     * status 是 fail）**不触发重登** —— 判断不了不等于没登录。
     *
     * 重登成功后用新 Cookie 再签一次，并通过 [onRenewed] 让调用方落盘。
     * 救不回来（auth 域也过期）就原样返回第一次的失败结果，绝不掩盖。
     */
    suspend fun run(
        ctx: Context,
        cookie: String,
        portalCookie: String = "",
        authCookie: String = "",
        onRenewed: ((HwRenew.Renewed) -> Unit)? = null,
    ): CheckinResult {
        val first = once(ctx, cookie, portalCookie)
        // 已经签成了、或者只是网络错误 → 不重登
        if (first.status == "success" || first.status == "already") return first
        val needRenew = first.authFailed || !LocalCheckin.hwHasSession(cookie)
        if (!needRenew) return first
        val fresh = HwRenew.renew(ctx, cookie, authCookie, portalCookie) ?: return first
        // 先落盘再重签：即使这一轮签到仍失败，新 Cookie 也是有效资产，
        // 下次不必再花 18 秒走一遍 302。
        runCatching { onRenewed?.invoke(fresh) }
        // 新会话刚建立，旧的就绪结论必须清掉（按 identityOf 分桶，agencyID 未变 → 同一个桶），
        // 否则 `HwVerified.usable()` 会拿旧 Cookie 的 false 压住新 Cookie，白跳过一轮。
        //
        // **`HwTodayMark` 不清，这是有意的**（BUGS-v2.63 P3-6 纠正了这里原先写错的注释）：
        // 它只在 `success` / `already` 时写（见本函数下方），标记存在就代表「今天真的签成功过」，
        // 与 Cookie 换没换无关。`once(fresh.cookie)` 命中它返回 already 正是正确语义
        // —— 同一账号同一天只能签一次。反过来，如果照旧注释去清它，就等于把
        // 「同账号同日只签一次」的保护拆掉，同一天会重复打上游。
        HwVerified.clear(ctx, fresh.cookie)
        val portal = fresh.portalCookie.ifBlank { portalCookie }
        val second = once(ctx, fresh.cookie, portal)
        // 新 Cookie 是「确实登录了」的强证据，无论签到结果如何都记 true：
        // 否则下一轮 /api/status 还会把它当未就绪，白跳过。
        HwVerified.set(ctx, fresh.cookie, true)
        return if (second.status == "success" || second.status == "already") {
            second.copy(message = second.message + "（已自动续期登录态）")
        } else {
            // 续期成功但签到仍失败：把真实原因带出去，别用第一次那条「登录已失效」误导用户。
            second.copy(message = second.message + "（登录态已自动续期，但签到仍失败）")
        }
    }

    /**
     * 单次签到尝试（不含量重登）。原 [run] 的主体，抽出来供重登前后各调一次。
     */
    private suspend fun once(
        ctx: Context,
        cookie: String,
        portalCookie: String,
    ): CheckinResult {
        if (!LocalCheckin.hwHasSession(cookie)) {
            return CheckinResult(
                "huawei", "fail",
                "缺少华为会话 Cookie：点「登录华为码道」在站内登录一次即可自动采集" +
                    "（Cookie 含 devclouddevuibjJ_SESSION_ID）",
                "",
            )
        }
        // 今日已签成功过 → 直接返回 already，**不再 ping 华为**。
        // 华为会话静置 30~60 分钟就失效，重复 ping 只会把「已签到」误报成「登录态过期」，
        // 而且失败结果会用 CONFLICT_REPLACE 把当天那条 success 记录整行覆盖掉。
        // 积分用标记里存的那份（BUGS-v2.46 P3-2：以前这里还去读一次余额，等于自己打自己脸）。
        val marked = HwTodayMark.getWithPoints(ctx, cookie)
        if (marked != null && marked.first.isNotEmpty()) {
            val pts = if (marked.second >= 0) marked.second else runCatching {
                LocalCheckin.huaweiHeld(cookie, portalCookie)
            }.getOrDefault(-1)
            return CheckinResult("huawei", "already", marked.first, "", pts)
        }
        val r = LocalCheckin.huawei(cookie, portalCookie)
        if (r.status == "success" || r.status == "already") {
            HwTodayMark.mark(ctx, cookie, r.message, r.totalPoints)
            // 能签成功就是「这份 Cookie 确实登录了」的最强证据，顺手把就绪结论固化，
            // 免得下次 /api/status 又因为「只看会话名」把它当成就绪（BUGS-v2.46 P2 的另一半）。
            HwVerified.set(ctx, cookie, true)
        } else if (r.authFailed) {
            // 明确的登录态错误 → 记成「未就绪」。
            //
            // BUGS-v2.48 P2-2：早先靠 `r.message.contains("登录态已失效") || contains("未登录")`，
            // 但 hwApi 的空响应（「多半是登录态不完整」）与非 JSON（「头组不完整或登录态失效」）
            // 两条路径都不含那两个子串 —— 于是结论记不下来，下一轮照旧派 job、照旧 401。
            // 改成读 CheckinResult.authFailed：结构化判据，不依赖文案措辞。
            // **网络错误（code == 0）不算**，判断不了不等于没登录。
            HwVerified.set(ctx, cookie, false)
        }
        return r
    }
}

