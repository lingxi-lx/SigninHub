package com.signhub.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 后台自动签到的 **WorkManager 入口**（只服务「立即签到」与降级兜底）。
 *
 * V2.51 起真正的签到逻辑搬进了 [CheckinRunner]，本类只剩一层壳：
 *   - 定时签到改由 AlarmManager 精确闹钟 → [AutoCheckinService] 前台服务执行
 *     （WorkManager 的 setInitialDelay 受 Doze 影响，息屏后可能被推迟到几小时后）
 *   - 本类继续负责「立即签到」（runNow）以及精确闹钟不可用时的降级路径
 *
 * 这样两套入口共用同一份执行体，不会出现「手动签得对、自动签得错」的分叉。
 */
class AutoCheckinWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        /** 保留旧常量位置，外部（ReminderReceiver 注释、LocalApi）仍引用。 */
        const val CHANNEL_ID = CheckinRunner.CHANNEL_ID
        const val CHANNEL_NAME = CheckinRunner.CHANNEL_NAME
        const val NOTIFY_ID = CheckinRunner.NOTIFY_ID
        private const val TAG = "AutoCheckinWorker"
    }

    override suspend fun doWork(): Result {
        val needRetry = try {
            CheckinRunner.run(ctx, attempt = runAttemptCount)
        } catch (e: Exception) {
            Log.e(TAG, "自动签到执行异常", e)
            // 异常也要排下一次，否则明天彻底不跑了
            try { AutoCheckinScheduler.scheduleNext(applicationContext) } catch (_: Exception) {}
            return Result.success()
        }

        if (needRetry) return Result.retry()

        // 排下一次（单次任务模式的必要一步，否则明天不会跑）
        try {
            AutoCheckinScheduler.scheduleNext(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
