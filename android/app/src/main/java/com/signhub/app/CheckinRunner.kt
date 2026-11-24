package com.signhub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first

/**
 * 自动签到的**执行体**，与「谁触发」解耦。
 *
 * 为什么要拆出来（V2.51）：
 *   以前这套逻辑整个长在 AutoCheckinWorker.doWork() 里，只能被 WorkManager 触发。
 *   而 WorkManager 的 setInitialDelay 受 Doze 影响 —— 息屏后可能被推迟到几小时后的
 *   维护窗口，甚至等设备解锁才跑。要准点就必须换成 AlarmManager 的精确闹钟，
 *   但精确闹钟的广播接收器只有约 10 秒，装不下十几个网络请求，必须由**前台服务**承接。
 *   于是同一套逻辑需要两个入口（Worker 给「立即签到」、Service 给「定时签到」），
 *   抽成这个 object 是最省事也最不容易分叉的做法。
 *
 * 返回值的语义：true = 本次有网络类失败，建议稍后重试。
 */
object CheckinRunner {

    const val CHANNEL_ID = "signhub_auto"
    const val CHANNEL_NAME = "自动签到"

    /** 与 ReminderReceiver.NOTIFY_ID（2002）错开，两条通知互不覆盖。 */
    const val NOTIFY_ID = 2001

    private const val TAG = "CheckinRunner"

    /**
     * 跑一遍全部账号。
     *
     * @param attempt 第几次尝试（Worker 传 runAttemptCount，Service 传 0）。
     *                只用来决定「网络异常要不要重试」的上限。
     * @param onProgress 进度回调：前台服务用它刷新常驻通知，Worker 传空实现。
     * @return true 表示存在网络类失败且还值得再试一次。
     */
    suspend fun run(
        ctx: Context,
        attempt: Int = 0,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        val s = SettingsStore.flow(ctx).first()
        val day = LocalApi.todayStatic()
        val store = HistoryStore(ctx)

        val results = mutableListOf<CheckinResult>()
        // 每条结果对应的账号键：多账号各自一行，否则 UNIQUE(provider, day) 会互相覆盖
        val keys = mutableListOf<String>()

        if (s.wbToken.isNotBlank()) {
            onProgress("WorkBuddy 签到中…")
            // 闸门：与「手动一键签到」撞车时同一账号会被打两遍上游
            results.add(CheckinGate.runSuspend("workbuddy", s.wbUid.ifBlank { HistoryStore.DEFAULT_ACCOUNT }) {
                WbAuth.runCheckin(ctx, s)
            })
            keys.add(s.wbUid.ifBlank { HistoryStore.DEFAULT_ACCOUNT })
        }
        // 多账号：遍历 WorkBuddy 多账号列表逐个签到。
        // 必须排除「主账号」本身，否则同一个人会被签两次：
        // 设置里登了主账号、多账号列表里又存了同一个账号时，两条记录都会打同一个接口，
        // 一次真签 + 一次「已签到」，白跑一趟还污染统计。
        try {
            val accounts = WbAccountStore.all(ctx).filter { acc ->
                acc.accessToken.isNotBlank() && !isSameAsMainAccount(acc, s)
            }
            accounts.forEach { acc ->
                onProgress("WorkBuddy 签到中…（${acc.displayName()}）")
                val r = CheckinGate.runSuspend("workbuddy", acc.id) {
                    val a = WbApi.checkin(acc)
                    WbAccountStore.update(ctx, a)
                    CheckinResult(
                        "workbuddy",
                        if (a.lastStatus.isBlank()) "fail" else a.lastStatus,
                        "${a.displayName()}：${a.lastMessage}",
                        "",
                        // 自动签到这条路径原先丢了这两个字段，落库后 total_points/streak_days 恒为 -1
                        a.totalPoints,
                        a.streakDays
                    )
                }
                results.add(r)
                keys.add(acc.id)
            }
        } catch (e: Exception) {
            // 以前这里是 `catch (_: Exception) {}`：整批 WorkBuddy 多账号一旦在
            // 读列表/解析 JSON 阶段炸掉，results 里一条都不加，界面却显示「自动签到完成」，
            // 用户以为签过了。现在至少落一条 fail 记录，让首页能看到「自动签到出错了」。
            Log.e(TAG, "WorkBuddy 多账号签到整批失败", e)
            results.add(CheckinResult("workbuddy", "fail",
                "多账号签到异常：${e.message ?: e.javaClass.simpleName}", ""))
            keys.add(HistoryStore.DEFAULT_ACCOUNT)
        }

        // Trae / Qoder / MiniMax：主账号 + 多账号列表逐个签（同 WorkBuddy 的做法）。
        // 账号键：主账号 DEFAULT_ACCOUNT，列表里的用各自 id —— 否则
        // UNIQUE(provider, day, account_key) 会让多个账号互相覆盖，只剩最后一条。
        val traeAccs = try { multiTrae(ctx, s) } catch (e: Exception) {
            Log.e(TAG, "读取 Trae 账号列表失败", e); emptyList()
        }
        // **多账号必须串行 + 错峰**（与 LocalApi.checkinAll 同一套策略）：
        // Trae 的 claim 接口按时间窗口排队，几个账号同时打会互相把对方挤进 9074。
        // 同类实测项目（L0NE-6/Trae-AutoCheckin）用 12~25 秒间隔，注释写
        // 「实测 25s 间隔可让相邻账号连续签到成功」。
        traeAccs.forEachIndexed { ti, acc ->
            if (ti > 0) {
                val gap = 12_000L + (Math.random() * 13_000L).toLong()
                try { kotlinx.coroutines.delay(gap) } catch (_: Exception) {}
            }
            onProgress("Trae Solo 签到中…（${acc.displayName()}）")
            var renewed: String? = null
            var renewedDev: String? = null
            val tr = try {
                CheckinGate.runSuspend("traesolo", acc.id) {
                    LocalCheckin.trae(
                        acc.token, acc.deviceId, acc.global, acc.session,
                        onRenewed = { fresh -> renewed = fresh },
                        onRenewedDevice = { fresh -> renewedDev = fresh },
                    )
                }
            } catch (e: Exception) {
                // 单个账号抛异常不能让整批中断：以前这里没有兜底，一个账号炸掉，
                // 后面所有账号都不签，且 doWork 直接抛出 → 通知里连「失败」都没有。
                Log.e(TAG, "Trae 账号签到异常", e)
                CheckinResult("traesolo", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            // 换到新 token 必须落盘，否则明天这轮又拿旧的 8 小时 JWT 撞 401
            renewed?.let { fresh ->
                try {
                    if (acc.id == HistoryStore.DEFAULT_ACCOUNT) SettingsStore.save(ctx, s.copy(traeToken = fresh))
                    else TraeAccountStore.update(ctx, acc.copy(token = fresh))
                } catch (_: Exception) {}
            }
            // 设备号被补齐/换新同样要落盘，否则下一轮又从旧值开始
            renewedDev?.let { fresh ->
                try {
                    if (acc.id == HistoryStore.DEFAULT_ACCOUNT) SettingsStore.save(ctx, s.copy(traeDeviceId = fresh))
                    else TraeAccountStore.update(ctx, acc.copy(deviceId = fresh))
                } catch (_: Exception) {}
            }
            results.add(tr)
            keys.add(acc.id)
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                try {
                    TraeAccountStore.update(ctx, acc.copy(
                        lastStatus = tr.status, lastMessage = tr.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = tr.totalPoints, streakDays = tr.streakDays,
                        usedPoints = tr.usedPoints))
                } catch (_: Exception) {}
            }
        }

        // Qoder CN / MiniMax Code：纯 HTTP，手机直连即可签
        // Qoder 只要有 token 或 refreshToken 就签（refreshToken 会自动续期；设备指纹选填）
        val qoderAccs = try { multiQoder(ctx, s) } catch (e: Exception) {
            Log.e(TAG, "读取 Qoder 账号列表失败", e); emptyList()
        }
        qoderAccs.forEach { acc ->
            onProgress("Qoder CN 签到中…（${acc.displayName()}）")
            val r = try {
                CheckinGate.runSuspend("qoder", acc.id) {
                    LocalCheckin.qoder(acc.region.ifBlank { "cn" }, acc.token,
                        acc.uid, acc.machineId, "", acc.refresh)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Qoder 账号签到异常", e)
                CheckinResult("qoder", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            results.add(r)
            keys.add(acc.id)
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                try {
                    QoderAccountStore.update(ctx, acc.copy(
                        lastStatus = r.status, lastMessage = r.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = r.totalPoints, streakDays = r.streakDays,
                        usedPoints = r.usedPoints))
                } catch (_: Exception) {}
            }
        }
        val mmAccs = try { multiMm(ctx, s) } catch (e: Exception) {
            Log.e(TAG, "读取 MiniMax 账号列表失败", e); emptyList()
        }
        mmAccs.forEach { acc ->
            onProgress("MiniMax Code 签到中…（${acc.displayName()}）")
            val r = try {
                CheckinGate.runSuspend("minimax", acc.id) {
                    LocalCheckin.minimax(acc.token, acc.userId, acc.deviceId, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MiniMax 账号签到异常", e)
                CheckinResult("minimax", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            results.add(r)
            keys.add(acc.id)
            if (acc.id != HistoryStore.DEFAULT_ACCOUNT) {
                try {
                    MmAccountStore.update(ctx, acc.copy(
                        lastStatus = r.status, lastMessage = r.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = r.totalPoints, streakDays = r.streakDays,
                        usedPoints = r.usedPoints))
                } catch (_: Exception) {}
            }
        }

        // 华为码道：多账号（用户 m24258）。主账号 + 多账号列表，逐个签。
        // 身份键优先 agencyID（BUGS-v2.46 P1）：整条 Cookie 会随重登变，
        // 用它当 id 会让同一个人当天出现两条历史记录、且「今日已签」标记读不到。
        // 去重也必须用 identityOf（BUGS-v2.48 P2-1）：主账号与列表里同一个号
        // （agencyID 相同、Cookie 不同）比整条 Cookie 是比不掉的。
        // Triple 的第三项是列表账号（主账号为 null）；Kotlin 不允许局部 data class。
        val hwOnes = mutableListOf<Triple<String, String, HwAccount?>>()
        val hwSeen = mutableSetOf<String>()
        if (s.hwCookie.isNotBlank()) {
            hwOnes.add(Triple(HistoryStore.DEFAULT_ACCOUNT, s.hwCookie, null))
            hwSeen.add(HwAccount.identityOf(s.hwCookie))
        }
        try {
            HwAccountStore.all(ctx).forEach { a ->
                if (a.cookie.isNotBlank() && hwSeen.add(HwAccount.identityOf(a.cookie))) {
                    hwOnes.add(Triple(AccountIds.stable(HwAccount.identityOf(a.cookie)), a.cookie, a))
                }
            }
        } catch (_: Exception) {}
        // hwUsable 而非 hwHasSession（BUGS-v2.46 P2）：被证实是匿名会话的 Cookie 不派 job，
        // 派了必然 401，失败结果还会用 CONFLICT_REPLACE 覆盖掉当天那条 success。
        hwOnes.filter { HwVerified.usable(ctx, it.second) }.forEach { one ->
            onProgress("华为码道签到中…")
            val r = try {
                CheckinGate.runSuspend("huawei", one.first) {
                    HwCheckin.run(ctx, one.second, s.hwPortalCookie)
                }
            } catch (e: Exception) {
                Log.e(TAG, "华为码道签到异常", e)
                CheckinResult("huawei", "fail",
                    "签到异常：${e.message ?: e.javaClass.simpleName}", "")
            }
            results.add(r)
            keys.add(one.first)
            one.third?.let { a ->
                try {
                    HwAccountStore.update(ctx, a.copy(
                        lastStatus = r.status, lastMessage = r.message,
                        lastCheckAt = System.currentTimeMillis(),
                        totalPoints = r.totalPoints, streakDays = r.streakDays,
                        usedPoints = r.usedPoints))
                } catch (_: Exception) {}
            }
        }

        if (results.isEmpty()) {
            val msg = "还没填凭据，打开 App 在配置里填一下"
            notify(ctx, "自动签到未执行", msg)
            AutoCheckinScheduler.recordRun(ctx, "自动签到未执行", msg)
            return false
        }

        results.forEachIndexed { i, r ->
            store.upsert(day, r, keys.getOrElse(i) { HistoryStore.DEFAULT_ACCOUNT })
        }

        val okCount = results.count { it.status == "success" || it.status == "already" }
        val allOk = okCount == results.size

        val lines = buildList {
            results.forEach { r ->
                val mark = when (r.status) {
                    "success" -> "✅"; "already" -> "🟡"; else -> "❌"
                }
                add("$mark ${label(r.provider)}：${r.message}")
            }
        }

        val title = when {
            allOk -> "自动签到完成"
            okCount > 0 -> "自动签到部分成功 $okCount/${results.size}"
            else -> "自动签到失败"
        }

        // 积分过期预警：顺手把「将过期积分」拼进通知，省得用户自己翻页面
        val extra = runCatching { expiringLine(ctx) }.getOrNull()
        val bodyAll = if (extra.isNullOrBlank()) lines.joinToString("\n")
        else lines.joinToString("\n") + "\n\n" + extra
        notify(ctx, title, bodyAll)
        // 推送到用户配置的 webhook（PushPlus / Server酱 / 群机器人 自动适配）
        runCatching { pushWebhook(ctx, title, bodyAll) }

        // 全部失败时考虑重试（网络问题居多）
        val anyNetworkError = results.any {
            it.status == "fail" && (it.message.contains("网络") || it.message.contains("超时"))
        }
        if (anyNetworkError && attempt < 2) {
            AutoCheckinScheduler.recordRun(ctx, title + "（网络异常，将重试第 ${attempt + 1} 次）", bodyAll)
            return true
        }

        // 记录本次运行，供设置页「上次自动签到」查看（否则用户无从判断到底跑没跑）
        AutoCheckinScheduler.recordRun(ctx, title, bodyAll)
        return false
    }

    fun label(key: String) = when (key) {
        "workbuddy" -> "WorkBuddy"
        "traesolo" -> "Trae Solo"
        "qoder" -> "Qoder CN"
        "minimax" -> "MiniMax Code"
        "huawei" -> "华为码道"
        else -> key
    }

    /**
     * 多账号列表里的这条，是不是就是设置里那个「主账号」？
     *
     * 判据按可靠性排序：uid 相同 → 同一个账号；否则 accessToken 完全相同 → 同一个账号；
     * 都没有 uid 时才退到「domain 相同且 id == default」。宁可漏判成两个账号（多签一次），
     * 也不能把两个真账号误判成一个（漏签）。
     */
    private fun isSameAsMainAccount(acc: WbAccount, s: AppSettings): Boolean {
        if (s.wbToken.isBlank()) return false
        // 区域不同就是不同账号（m19236）：workbuddy.ai 与 codebuddy.cn 两套体系，
        // uid 撞车很正常，判成同一人会把国际版账号直接丢掉。
        val mainVariant = if (s.wbGlobal) "ai" else "cn"
        if (acc.variant != mainVariant) return false
        if (s.wbUid.isNotBlank() && acc.uid.isNotBlank() && s.wbUid == acc.uid) return true
        if (acc.accessToken == s.wbToken) return true
        return false
    }

    // ── Trae / Qoder / MiniMax 的多账号列表（主账号 + 存储列表，去重） ──
    private fun multiTrae(ctx: Context, s: AppSettings): List<TraeAccount> {
        val out = mutableListOf<TraeAccount>()
        if ((s.traeToken.isNotBlank() || s.traeSession.isNotBlank()) && s.traeDeviceId.isNotBlank()) {
            out.add(TraeAccount(
                id = HistoryStore.DEFAULT_ACCOUNT,
                token = s.traeToken, session = s.traeSession,
                deviceId = s.traeDeviceId, global = s.traeGlobal))
        }
        try {
            TraeAccountStore.all(ctx)
                .filter { (it.token.isNotBlank() || it.session.isNotBlank()) && it.deviceId.isNotBlank() }
                .filter { acc -> out.none { sameTrae(it, acc) } }
                .forEach { out.add(it) }
        } catch (_: Exception) {}
        return out
    }

    private fun multiQoder(ctx: Context, s: AppSettings): List<QoderAccount> {
        val out = mutableListOf<QoderAccount>()
        if (s.qoderToken.isNotBlank() || s.qoderRefresh.isNotBlank()) {
            out.add(QoderAccount(
                id = HistoryStore.DEFAULT_ACCOUNT,
                token = s.qoderToken, refresh = s.qoderRefresh,
                uid = s.qoderUid, machineId = s.qoderMachineId,
                region = s.qoderRegion.ifBlank { "cn" }))
        }
        try {
            QoderAccountStore.all(ctx)
                .filter { it.token.isNotBlank() || it.refresh.isNotBlank() }
                .filter { acc -> out.none { sameQoder(it, acc) } }
                .forEach { out.add(it) }
        } catch (_: Exception) {}
        return out
    }

    private fun multiMm(ctx: Context, s: AppSettings): List<MmAccount> {
        val out = mutableListOf<MmAccount>()
        if (s.mmToken.isNotBlank()) {
            out.add(MmAccount(
                id = HistoryStore.DEFAULT_ACCOUNT,
                token = s.mmToken, userId = s.mmUserId, deviceId = s.mmDeviceId))
        }
        try {
            MmAccountStore.all(ctx)
                .filter { it.token.isNotBlank() }
                .filter { acc -> out.none { sameMm(it, acc) } }
                .forEach { out.add(it) }
        } catch (_: Exception) {}
        return out
    }

    private fun sameTrae(a: TraeAccount, b: TraeAccount): Boolean =
        (a.session.isNotBlank() && a.session == b.session) ||
            (a.token.isNotBlank() && a.token == b.token)

    private fun sameQoder(a: QoderAccount, b: QoderAccount): Boolean =
        (a.refresh.isNotBlank() && a.refresh == b.refresh) ||
            (a.token.isNotBlank() && a.token == b.token) ||
            (a.uid.isNotBlank() && a.uid == b.uid)

    private fun sameMm(a: MmAccount, b: MmAccount): Boolean =
        (a.token.isNotBlank() && a.token == b.token) ||
            (a.userId.isNotBlank() && a.userId == b.userId)

    /** 「将过期积分」一行文案；查不到返回 null（绝不写 0 误导）。 */
    private suspend fun expiringLine(ctx: Context): String? {
        val acc = try {
            WbAccountStore.all(ctx).firstOrNull { it.accessToken.isNotBlank() }
        } catch (e: Exception) { null } ?: return null
        val card = try { WbAssets.card(acc, useCache = false, ctx = ctx) } catch (e: Exception) { return null }
        val ex = card.optJSONObject("expiring") ?: return null
        val d7 = ex.optDouble("d7", 0.0)
        val next = ex.optJSONObject("next")
        return when {
            d7 > 0 && next != null -> "⏳ 7 天内将过期 ${fmt(d7)} 积分（最近 ${next.optString("date")} 到期 ${fmt(next.optDouble("amount", 0.0))}）"
            ex.optDouble("d30", 0.0) > 0 -> "⏳ 30 天内将过期 ${fmt(ex.optDouble("d30", 0.0))} 积分"
            else -> null
        }
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    /** 发送到用户配置的推送渠道（未启用则什么都不做）。 */
    private suspend fun pushWebhook(ctx: Context, title: String, body: String) {
        val cfg = Notify.load(ctx)
        if (!cfg.enabled || cfg.url.isBlank()) return
        val r = Notify.push(ctx, cfg, title, body)
        // 记一条推送日志，网页「通知」面板可查
        val p = ctx.getSharedPreferences("signhub_notify_log", Context.MODE_PRIVATE)
        val arr = try {
            org.json.JSONArray(p.getString("log", "[]") ?: "[]")
        } catch (e: Exception) { org.json.JSONArray() }
        val entry = org.json.JSONObject().apply {
            put("kind", r.kind)
            put("title", title)
            put("ok", r.ok)
            put("detail", r.detail.take(200))
            put("created_at", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        }
        val next = org.json.JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), 49)) next.put(arr.opt(i))
        p.edit().putString("log", next.toString()).apply()
    }

    private fun notify(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntentApp.openApp(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
            // 没通知权限就算了，签到本身已经完成
        }
    }
}

/** 点通知回到 App。 */
object PendingIntentApp {
    fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, WebUiActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(ctx, 1002, i, flags)
    }
}
