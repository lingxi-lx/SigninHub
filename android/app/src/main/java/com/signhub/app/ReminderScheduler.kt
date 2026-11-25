package com.signhub.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 每日提醒（本地通知，**只提醒不签到**）。
 *
 * V2.51 的改动：从 `setInexactRepeating` 换成「一次性精确闹钟 + 触发后自己排下一天」。
 *
 * 为什么换：
 *   `setInexactRepeating` 从 Android 6 起在 Doze 下会被推迟到维护窗口，
 *   Android 12 之后更是被大幅批处理 —— 实测误差可达数小时。一个「提醒」如果
 *   设了 08:30 却 12:00 才响，等于没有。而且周期固定 24 小时，
 *   一旦某次被延后 2 小时，之后每天都会固定在那个延后的时刻。
 *
 * 为什么不用重复闹钟：
 *   一次性 + 触发后续排，每次都贴着目标时刻，不会累积漂移。
 *   代价是「如果某次触发失败（进程被强杀），链条就断了」—— 由 BootReceiver
 *   与 App 每次启动时的 ensureScheduled 兜底修复。
 */
object ReminderScheduler {
    private const val REQ = 1001
    private const val TAG = "ReminderScheduler"
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 排（或取消）每日提醒。 */
    fun schedule(ctx: Context, enabled: Boolean, hhmm: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx)
        if (!enabled) {
            runCatching { am.cancel(pi) }
            return
        }
        val (hour, min) = parse(hhmm)
        arm(ctx, am, pi, nextAt(hour, min))
    }

    /**
     * 提醒触发后由 [ReminderReceiver] 调用，续排下一天。
     * 单独一个方法是为了让「已关闭提醒」时不再续排 —— 否则用户关掉后第二天还会响。
     *
     * V2.63/288（BUGS-v2.63-287 P2）：改成 `suspend`，去掉内部的 `runBlocking`。
     * 原来这里是 `runBlocking { SettingsStore.flow(ctx).first() }`，而唯一的调用方
     * [ReminderReceiver.onReceive] 跑在主线程 —— 冷启动时 DataStore 要初始化、
     * 读文件、解析全部 key，全在主线程，10 秒预算一超就 ANR 或广播被杀，
     * 续排不执行、提醒链条断掉。调用方已改成 `goAsync()` + `Dispatchers.IO` 协程。
     */
    suspend fun scheduleNext(ctx: Context) {
        val s = runCatching { SettingsStore.flow(ctx).first() }.getOrNull()
        if (s == null || !s.reminderEnabled) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val (hour, min) = parse(s.reminderTime)
        arm(ctx, am, pendingIntent(ctx), nextAt(hour, min))
    }

    private fun arm(ctx: Context, am: AlarmManager, pi: PendingIntent, at: Long) {
        try {
            if (AutoCheckinScheduler.canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // 拿不到精确权限：仍比 setInexactRepeating 好（至少能在 Doze 中唤醒一次）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "精确闹钟被拒，降级", e)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        } catch (e: Exception) {
            Log.e(TAG, "注册提醒闹钟失败", e)
        }
    }

    /** 下一个 h:m（今天已过则明天），按 Asia/Shanghai 算。 */
    private fun nextAt(hour: Int, min: Int): Long {
        val now = LocalDateTime.now(ZONE)
        var target = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, min))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZONE).toInstant().toEpochMilli()
    }

    private fun parse(hhmm: String): Pair<Int, Int> {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        return h to m
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, REQ, intent, flags)
    }
}
