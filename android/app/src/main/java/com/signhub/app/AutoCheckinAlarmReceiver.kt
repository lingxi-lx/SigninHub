package com.signhub.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 精确闹钟触发的广播接收器（V2.51）。
 *
 * 它只做一件事：把签到交给前台服务。
 *
 * 为什么不在这里直接签到：
 *   BroadcastReceiver 的 onReceive 只有约 10 秒存活时间，超过就 ANR / 被系统强杀。
 *   我们一次要跑五家平台、十几个账号，Trae 多账号还要错峰 12~25 秒/个 ——
 *   在接收器里跑必然超时。
 *
 * 为什么可以在这里 startForegroundService：
 *   Android 12+ 禁止后台进程启动前台服务，但「收到精确闹钟广播」是官方豁免项
 *   （与「收到高优先级 FCM」「设备刚开机」同级）。
 */
class AutoCheckinAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        Log.i(TAG, "精确闹钟触发，拉起签到前台服务")
        AutoCheckinService.start(ctx.applicationContext)
    }

    companion object {
        private const val TAG = "CheckinAlarm"
        const val ACTION_FIRE = "com.signhub.app.action.CHECKIN_ALARM"
    }
}
