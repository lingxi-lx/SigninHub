package com.signhub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 每日提醒的广播接收器。
 *
 * V2.51 起它多了一件事：**续排下一天的提醒**。
 * 因为 ReminderScheduler 已从 `setInexactRepeating`（自带周期）改成一次性精确闹钟，
 * 不续排的话提醒只会响一次。
 *
 * V2.63/288（BUGS-v2.63-287 P2）：续排**不能在 onReceive 主线程同步做**。
 * `onReceive` 只有 10 秒预算，而 `ReminderScheduler.scheduleNext()` 要读 DataStore
 * （冷启动时还要初始化 + 读文件 + 解析全部 key）。超时即 ANR 或广播被系统杀掉，
 * 于是「续排下一天」这一步根本不执行 —— 一次性闹钟没有自带周期，链条就此断掉，
 * 之后只能靠 `BootReceiver` 或用户开 App 兜底。失败还是静默的：`runCatching`
 * 把超时异常吞掉，用户只看到提醒响了一次，第二天不再响。
 * 现在照抄 [BootReceiver] 已经用对的写法：`goAsync()` + IO 协程 + `finally finish()`。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "signhub_reminder"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "签到提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // 点通知要能进 App：以前没有 contentIntent，点一下只是把通知划掉。
        val open = Intent(ctx, WebUiActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, REQ_OPEN, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("该去签到了")
            .setContentText("打开签到中心，一键完成 WorkBuddy / Trae Solo / Qoder / MiniMax / 华为码道 签到打卡。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // 通知 id 必须与 CheckinRunner.NOTIFY_ID 区分开：以前两边都用 2001，
        // 提醒发出后一旦自动签到跑完，签到结果会把提醒顶掉（反过来也一样）。
        nm.notify(NOTIFY_ID, notif)

        // 续排下一天（一次性闹钟没有自带周期）。放在通知之后：
        // 即使续排抛异常，用户至少已经收到提醒。
        //
        // V2.63/288（BUGS-v2.63-287 P2）：改成 goAsync + IO 协程。
        // 原来这里是同步 `runCatching { ReminderScheduler.scheduleNext(...) }`，
        // 而 scheduleNext 内部 `runBlocking { SettingsStore.flow(ctx).first() }`
        // 在主线程等 DataStore 首次文件读 —— 冷启动（进程被闹钟拉起）时最危险，
        // 超 10 秒就 ANR 或广播被杀，续排不执行、链条断掉且静默无日志。
        // goAsync 让系统等我们最多 10 秒（broadcast 超时上限仍是 60 秒），
        // 真正的 IO 在 Dispatchers.IO 上跑，主线程立刻返回。
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.scheduleNext(ctx.applicationContext)
            } catch (_: Exception) {
                // 续排失败不该让用户看到崩溃：下一天还有 BootReceiver / 开 App 兜底。
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 与 CheckinRunner.NOTIFY_ID（2001）、AutoCheckinService（2003）错开。 */
        const val NOTIFY_ID = 2002
        private const val REQ_OPEN = 1003
    }
}
