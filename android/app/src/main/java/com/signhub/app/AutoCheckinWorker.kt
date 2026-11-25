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

        // BUGS-v2.63-290 P3-5：retry 分支以前直接 return，不排下一次。
        // WorkManager 默认重试 10 次、指数退避约 8~9 小时，重试耗尽后任务终止，
        // 而这条降级链路上没有别的闹钟 —— 自动签到会永久停摆，
        // 用户只会看到「某天起不再自动签」。所以先续排，再决定要不要 retry。
        try {
            AutoCheckinScheduler.scheduleNext(applicationContext)
        } catch (_: Exception) {
        }
        // 与 CheckinRunner 的 attempt < 2 判据对齐：给网络抖动一次机会，之后不再 retry，
        // 免得同一轮被 WorkManager 反复拉起又反复失败（每次都会往通知栏写一条）。
        if (needRetry && runAttemptCount < 2) return Result.retry()
        return Result.success()
    }
}
