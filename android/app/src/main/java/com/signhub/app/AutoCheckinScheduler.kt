package com.signhub.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 自动签到的定时调度（V2.51：AlarmManager 精确闹钟为主，WorkManager 降级为辅）。
 *
 * ## 为什么从 WorkManager 换成 AlarmManager
 * 旧实现用 `OneTimeWorkRequest.setInitialDelay()`，注释里自己写着「受 Doze 影响但一定会在
 * 维护窗口执行」。问题是那个维护窗口**可能是几小时后**，甚至要等设备解锁 ——
 * 用户设了 08:30，实际可能 11:40 才跑，也可能当天根本没跑。
 * `setExactAndAllowWhileIdle()` 是唯一能在 Doze 中准点触发的 API。
 *
 * ## 链路
 * ```
 * AlarmManager.setExactAndAllowWhileIdle
 *   → AutoCheckinAlarmReceiver（约 10 秒存活，只做一件事：startForegroundService）
 *   → AutoCheckinService（前台服务，慢慢跑 CheckinRunner）
 *   → 跑完续排下一天（接力链，一次性闹钟）
 * ```
 * 广播接收器只有约 10 秒，装不下十几个网络请求，所以中间必须有一个前台服务；
 * 而「收到精确闹钟广播」恰好是 Android 12+ 允许后台启动前台服务的官方豁免项。
 *
 * ## 为什么不用 PeriodicWorkRequest / setRepeating
 * 周期固定 24 小时，一旦某次被 Doze 延后 2 小时，**之后每天都会固定在那个延后的时刻**。
 * 这里用「一次性 + 跑完自己排下一次」，每天都贴着目标时间。
 *
 * ## 随机浮动（jitter）
 * 固定到点打卡的行为特征太明显（每天同一分钟、同一顺序）。
 * 每次排期时在目标时间后叠加 0~jitterMin 分钟的随机偏移。
 *
 * ## 为什么还要留 WorkManager
 * 三个理由：
 *   ① 「立即签到」按钮走 WorkManager 更省事（不需要前台服务通知）
 *   ② `USE_EXACT_ALARM` 在个别 ROM 上仍可能被拒（`canScheduleExactAlarms()` 为 false），
 *      此时只能 `setAndAllowWhileIdle`，仍可能大幅延迟
 *   ③ 精确闹钟权限被用户关掉时，前端会提示，用户也可以选择只走 WorkManager
 */
object AutoCheckinScheduler {

    private const val TAG = "AutoCheckinScheduler"

    const val WORK_NAME = "signhub_auto_checkin_next"
    /** 「立即签到」用的独立 unique 名，与日常排期互不干扰。 */
    const val WORK_NAME_NOW = "signhub_auto_checkin_now"
    const val DEFAULT_TIME = "08:30"

    /** 闹钟的 PendingIntent 请求码（与 ReminderScheduler 的 1001 错开）。 */
    private const val REQ_ALARM = 2001
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 调度（或更新）下一次自动签到。enabled=false 则取消。
     *
     * 用户手动改时间 / 改浮动 / 开关时调这个 —— 属于「用户明确要求重排」，
     * 所以丢弃已排时刻、重新抽。
     */
    fun schedule(ctx: Context, enabled: Boolean, hhmm: String = DEFAULT_TIME, jitterMin: Int = -1) {
        if (!enabled) {
            cancel(ctx)
            return
        }
        val c = LocalConfig.load(ctx)
        val j = if (jitterMin >= 0) jitterMin else c.schedulerJitter
        val (h, m) = parse(hhmm)
        val at = pickAt(h, m, j)
        LocalConfig.save(ctx, c.copy(nextPick = at, retryAt = 0L))
        arm(ctx, at)
    }

    /**
     * 确保有排期，但**不覆盖已经排好的那次**。
     *
     * 为什么需要这个：以前 App 每次 onCreate 都调 schedule()，而 schedule 内部是
     * REPLACE 策略。于是只要上午打开一次 App，那条「今天 08:30 还没执行」的任务
     * 就会被替换成「明天 08:30」——今天这一次直接消失。表现就是「开了 App 反而
     * 不签到了」。
     */
    fun ensureScheduled(ctx: Context, hhmm: String = DEFAULT_TIME, jitterMin: Int = -1) {
        val c = LocalConfig.load(ctx)
        if (!c.schedulerEnabled) return
        val j = if (jitterMin >= 0) jitterMin else c.schedulerJitter
        val (h, m) = parse(if (hhmm.isBlank()) c.schedulerTime else hhmm)
        ensureArmedAt(ctx, h, m, j, allowToday = true)
    }

    /**
     * 统一重排入口（幂等，可安全重复调用）。
     *
     * @param allowToday true = 开机 / 打开 App / 改配置场景：已排时刻还在未来就沿用，
     *                   过期了才重抽（重抽仍按「今天该时刻已过 → 明天」算）
     *                   false = 签到执行完后的续排：必须排到严格未来的那一次
     */
    fun ensureArmed(ctx: Context, allowToday: Boolean) {
        val c = LocalConfig.load(ctx)
        if (!c.schedulerEnabled) return
        val (h, m) = parse(c.schedulerTime)
        ensureArmedAt(ctx, h, m, c.schedulerJitter, allowToday)
    }

    /**
     * 网络类失败后的「补一次」：把下一次闹钟提前到 [minutes] 分钟后。
     * 同一天只补一次 —— 用 `retryAt` 做标记，避免上游持续故障时每 10 分钟打一遍。
     */
    fun scheduleRetryIn(ctx: Context, minutes: Int) {
        val c = LocalConfig.load(ctx)
        val now = System.currentTimeMillis()
        if (c.retryAt > 0 && todayKey(c.retryAt) == todayKey(now)) return
        val at = now + minutes.coerceIn(1, 60) * 60_000L
        // BUGS-v2.63-286 P2：`nextPick` 与 `retryAt` 必须同步写成同一个时刻。
        // 以前只写 retryAt，nextPick 还停在「明天 08:30」，于是紧接着的续排
        // （ensureArmedAt 走 allowToday=false 分支）拿 nextPick 覆盖掉补签闹钟
        // —— 两个字段各说一套，谁后跑谁赢。写成同一个值以后，无论哪条路径
        // 后执行，arm() 的目标都是 at。
        LocalConfig.save(ctx, c.copy(retryAt = at, nextPick = at))
        arm(ctx, at)
    }

    /** 立刻跑一次（调试 / 补签）。跑完会自动排下一次。 */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<AutoCheckinWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // L15（BUGS-v2.61）：原来 Result.retry() 走 WorkManager 默认退避
            // （30 秒线性），上游抖动时几秒内就连撞三四次。改成指数退避、
            // 30 秒起，第一次 30s → 60s → 120s …（上限由 WorkManager 控在 5 小时）。
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .addTag(WORK_NAME_NOW)
            .build()
        // 必须用 unique：以前是裸 enqueue()，用户在设置页连点两下「立即签到」
        // 就会有两个 Worker 同时跑，各自打一遍上游（第二次全变「已签到」），
        // 还会同时写库、同时发通知。KEEP 表示「已经排了就沿用」，不会打断正在跑的。
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NAME_NOW, ExistingWorkPolicy.KEEP, req
        )
    }

    /** 取消全部排期（闹钟 + WorkManager）。 */
    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmPendingIntent(ctx)) }
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) }
        val c = LocalConfig.load(ctx)
        LocalConfig.save(ctx, c.copy(nextPick = 0L, retryAt = 0L))
    }

    /** Worker / Service 跑完后调用：排下一天。 */
    fun scheduleNext(ctx: Context) {
        val c = LocalConfig.load(ctx)
        if (!c.schedulerEnabled) return
        val (h, m) = parse(c.schedulerTime)
        ensureArmedAt(ctx, h, m, c.schedulerJitter, allowToday = false)
    }

    /** 当前是否允许精确闹钟（Android 12/13 默认允许；Android 14+ 全新安装默认不允许）。 */
    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return runCatching {
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    /* ---------- 内部 ---------- */

    /**
     * 已排时刻还在未来就沿用（同一 PendingIntent 再注册等价于覆盖，等于没变），
     * 否则按窗口重新抽一个并持久化 —— 这样开机 / 打开 App 不会反复重抽，
     * 也不会把「今天还没跑」的那次顶成明天。
     */
    private fun ensureArmedAt(ctx: Context, h: Int, m: Int, jitterMin: Int, allowToday: Boolean) {
        val c = LocalConfig.load(ctx)
        val now = System.currentTimeMillis()
        // BUGS-v2.63-286 P2：续排（allowToday=false）必须先看有没有待执行的补签。
        // 补签（scheduleRetryIn）与续排（ensureArmed）在 AutoCheckinService 里是两个
        // 协程，谁先跑不确定；补签只把闹钟 arm 到 10 分钟后，如果续排后跑就会把它
        // 顶成明天。这里让续排认出「今天还欠一次补签」并直接沿用它 —— 两条路径
        // 谁先谁后都不丢。
        if (!allowToday && c.retryAt > now && todayKey(c.retryAt) == todayKey(now)) {
            arm(ctx, c.retryAt)
            return
        }
        // BUGS-v2.63 P3-4：以前这里写成 if (allowToday && …) / else if (!allowToday && …) 两支，
        // 两支的函数体一模一样（都 `at = c.nextPick`），allowToday 对最终闹钟时刻零影响，
        // 而注释却声称「false = 必须排到严格未来的那一次」。合并成一支，并让 allowToday
        // 真的起作用：false（签到执行完后的续排）时，即使 nextPick 在未来也重抽一次，
        // 否则「刚跑完这一轮」会把下一次排在几秒后（那个 nextPick 就是刚被消费掉的那个时刻）。
        val at: Long = if (allowToday && c.nextPick > now) {
            // 开机 / 打开 App / 改配置：已排时刻还在未来就沿用（同一 PendingIntent 再注册
            // 等价于覆盖，等于没变），避免反复重抽把「今天还没跑」的那次顶成明天。
            c.nextPick
        } else {
            // 续排（allowToday=false）：一律重抽到严格未来；或已排时刻已过期 → 也重抽。
            // retryAt 一并清掉：补签已经跑完（或本来就没有），不清会让下一次续排
            // 又被上面那条 if 拦回去。
            pickAt(h, m, jitterMin).also { LocalConfig.save(ctx, c.copy(nextPick = it, retryAt = 0L)) }
        }
        arm(ctx, at)
    }

    /**
     * 按「目标时刻 + 0~jitterMin 分钟随机偏移」算下一次执行时间戳。
     * 目标时刻取严格未来的那一个（今天已过 → 明天），时区固定 Asia/Shanghai。
     */
    private fun pickAt(h: Int, m: Int, jitterMin: Int): Long {
        val now = LocalDateTime.now(ZONE)
        var target = LocalDateTime.of(now.toLocalDate(), LocalTime.of(h, m))
        if (!target.isAfter(now)) target = target.plusDays(1)
        val base = target.atZone(ZONE).toInstant().toEpochMilli()
        val extra = if (jitterMin > 0) Random.nextLong(0, jitterMin.toLong() * 60_000L + 1) else 0L
        return base + extra
    }

    /**
     * 注册一次性闹钟。优先精确（Doze 中也能准点），拿不到权限就降级。
     *
     * `setExactAndAllowWhileIdle` 在未授权时会抛 SecurityException（Android 12+），
     * 所以必须 try/catch，绝不能让它把整个排期流程打断。
     */
    private fun arm(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPendingIntent(ctx)
        try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "精确闹钟被拒，降级为 setAndAllowWhileIdle", e)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        } catch (e: Exception) {
            Log.e(TAG, "注册闹钟失败", e)
        }
    }

    private fun alarmIntent(ctx: Context): Intent =
        Intent(ctx, AutoCheckinAlarmReceiver::class.java)
            .setAction(AutoCheckinAlarmReceiver.ACTION_FIRE)

    private fun alarmPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ_ALARM, alarmIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /* ---------- 运行记录（诊断用） ----------
     * 用户没法知道后台任务到底跑没跑：没跑是「系统没调度」，跑了失败是「凭据/风控」，
     * 两者处理方式完全不同。这里把每次运行的时间、标题、正文存下来，
     * 设置页可以直接看到「上次自动签到：MM-dd HH:mm · 结果」。 */
    private const val RUN_PREF = "signhub_auto_run"
    private const val K_AT = "at"
    private const val K_TITLE = "title"
    private const val K_BODY = "body"

    fun recordRun(ctx: Context, title: String, body: String) {
        try {
            val sp = ctx.getSharedPreferences(RUN_PREF, Context.MODE_PRIVATE)
            sp.edit()
                .putLong(K_AT, System.currentTimeMillis())
                .putString(K_TITLE, title)
                .putString(K_BODY, body)
                .apply()
        } catch (_: Exception) {
        }
    }

    /** 返回 {at, title, body}；没跑过返回 null。 */
    fun lastRun(ctx: Context): Map<String, Any>? {
        return try {
            val sp = ctx.getSharedPreferences(RUN_PREF, Context.MODE_PRIVATE)
            val at = sp.getLong(K_AT, 0L)
            if (at <= 0L) null
            else mapOf(
                "at" to at,
                "title" to (sp.getString(K_TITLE, "") ?: ""),
                "body" to (sp.getString(K_BODY, "") ?: ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parse(hhmm: String): Pair<Int, Int> {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        return h to m
    }

    /**
     * 距离下一个 h:m 还有多久（若今天已过则算明天）。
     *
     * 必须按 Asia/Shanghai 算，不能用 LocalDateTime.now()：
     * 全 App 的「今天」都走 Asia/Shanghai（LocalApi.today()、HistoryStore.now()、
     * MultiAccountsActivity.today()），而这里以前用设备本地时区 ——
     * 设备时区不是 UTC+8 时，排期时刻会与「今天」的判定错位，
     * 表现为「到点了没签，或者提前/延后好几个小时才签」。
     */
    fun delayUntil(h: Int, m: Int): Duration {
        val now = LocalDateTime.now(ZONE)
        var target = LocalDateTime.of(now.toLocalDate(), LocalTime.of(h, m))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }

    /** 下次排期时刻（毫秒）；0 = 没排。设置页「下次自动签到」用它。 */
    fun nextPickAt(ctx: Context): Long = LocalConfig.load(ctx).nextPick

    private fun todayKey(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate().toString()
}
