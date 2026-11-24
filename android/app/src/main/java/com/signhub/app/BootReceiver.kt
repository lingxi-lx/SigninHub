package com.signhub.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机后恢复定时。
 *
 * V2.51 起责任变重了：以前 WorkManager 的任务能跨重启保留，这里只是兜底；
 * 现在定时签到走 **AlarmManager 一次性闹钟**，而闹钟在重启后会全部丢失
 * （除非是 `setRepeating`，那又会有周期漂移问题）。所以这里是**必走的一步**，
 * 否则用户重启一次手机，自动签到就永久停了。
 *
 * 两个「不要做」：
 *   ① 不在这里启动 [AutoCheckinService]：Android 15+ 明确禁止从 BOOT_COMPLETED
 *      启动 dataSync 类型的前台服务（会抛 ForegroundServiceStartNotAllowedException）。
 *      开机只负责「把闹钟重新排上」，到点再由闹钟拉起服务。
 *   ② 不重新抽随机时刻：`ensureArmed(allowToday = true)` 会读 LocalConfig.nextPick，
 *      还在未来就沿用原值 —— 否则每次重启都换一个时刻，等于浮动失效。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = SettingsStore.flow(ctx).first()
                // 老的提醒通道（只弹通知）
                if (s.reminderEnabled) {
                    ReminderScheduler.schedule(ctx, true, s.reminderTime)
                }
                // 自动签到（真的执行签到）：重排精确闹钟，允许今天补上
                AutoCheckinScheduler.ensureArmed(ctx, allowToday = true)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
