package com.signhub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 每日提醒的广播接收器。
 *
 * V2.51 起它多了一件事：**续排下一天的提醒**。
 * 因为 ReminderScheduler 已从 `setInexactRepeating`（自带周期）改成一次性精确闹钟，
 * 不续排的话提醒只会响一次。
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
        runCatching { ReminderScheduler.scheduleNext(ctx.applicationContext) }
    }

    companion object {
        /** 与 CheckinRunner.NOTIFY_ID（2001）、AutoCheckinService（2003）错开。 */
        const val NOTIFY_ID = 2002
        private const val REQ_OPEN = 1003
    }
}
