package com.signhub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定时签到的**前台服务**（V2.51）。
 *
 * 为什么必须有它：
 *   精确闹钟的广播接收器只有约 10 秒存活时间，而我们一次要跑五家平台、十几个账号，
 *   网络请求累计远超 10 秒。Android 12+ 明确禁止后台进程随便 startForegroundService，
 *   但**「收到精确闹钟广播」是官方豁免项之一** —— 所以链路是：
 *       精确闹钟 → AlarmReceiver（10 秒内）→ startForegroundService → 本服务慢慢跑
 *
 * 为什么不能只靠 WorkManager：
 *   WorkManager 的 setInitialDelay 在 Doze 下会被推迟到维护窗口（可能几小时后，
 *   甚至要等设备解锁）。息屏状态下的「准点」只有 setExactAndAllowWhileIdle 能给。
 *
 * 代价（必须如实告知用户）：
 *   每次执行会有一条「定时签到进行中」的常驻通知。这是 Android 8+ 前台服务的硬性
 *   要求，去不掉 —— 已用 IMPORTANCE_LOW + setSilent 做到不响不震。
 *
 * 它**不解决**国产 ROM 的进程杀手（MIUI/EMUI/ColorOS 的自启动与省电策略），
 * 那需要用户在设置页里手动把本应用加进白名单，见 SettingsGuide。
 */
class AutoCheckinService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "定时签到进度", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "定时签到执行过程中的实时进度"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (job != null) {
                    job?.cancel()   // job 的 finally 里完成释放锁 / 续排 / 收尾
                } else {
                    // BUGS-v2.63-286 P3：ensureArmed 是普通函数（非 suspend），
                    // 直接调即可，不要再起协程 —— 起协程会在 stopSelf() → scope.cancel()
                    // 时把还没开始执行的那次续排静默取消。
                    runCatching { AutoCheckinScheduler.ensureArmed(applicationContext, allowToday = false) }
                    finishRun()
                }
            }
            else -> {
                startAsForeground("准备签到…")
                when {
                    job?.isActive == true -> Unit
                    else -> {
                        job = scope.launch {
                            holdWakeLock()
                            // BUGS-v2.63-286 P2/P3：补签与续排以前各起一个 scope.launch，
                            // 谁先跑不确定，而 arm() 都落在同一个 PendingIntent 上 ——
                            // 补签后跑则明天的常规排期丢失，续排后跑则补签被顶成明天。
                            // 现在改成 finally 里一次顺序执行、二选一，并整体包在
                            // NonCancellable 里（finally 本身在 job 协程内，stopSelf()
                            // 之后才可能 scope.cancel()，包住即可保证跑完再退出）。
                            var needRetry = false
                            try {
                                needRetry = runAll()
                            } catch (e: CancellationException) {
                                updateProgress("签到已取消")
                            } catch (e: Exception) {
                                Log.e(TAG, "定时签到执行异常", e)
                                updateProgress("签到异常：" + (e.message ?: e.javaClass.simpleName))
                            } finally {
                                releaseWakeLock()
                                withContext(NonCancellable) {
                                    val app = applicationContext
                                    runCatching {
                                        if (needRetry) {
                                            // 前台服务里没法像 Worker 那样 return retry()，
                                            // 直接把下一次闹钟排到 10 分钟后（最多一次，靠本地标记防连环）
                                            AutoCheckinScheduler.scheduleRetryIn(app, 10)
                                        } else {
                                            AutoCheckinScheduler.ensureArmed(app, allowToday = false)
                                        }
                                    }
                                }
                                finishRun()
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        // Android 15+ 前台服务超时（dataSync 上限 6 小时，实际远达不到）
        Log.w(TAG, "前台服务超时，主动停止")
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 真正跑签到。返回 true 表示有网络类失败、值得重试一次。
     *
     * 执行前两道闸：
     *   ① 等网络就绪（最多 60 秒）—— 闹钟常在刚解锁/息屏时触发，Wi-Fi 可能还没连上
     *   ② 今日全签完就直接跳过 —— Doze 下同一闹钟可能被重复投递
     */
    private suspend fun runAll(): Boolean {
        if (!awaitNetwork()) {
            updateProgress("等待网络超时，仍尝试继续执行")
        }
        return CheckinRunner.run(this, attempt = 0) { text -> updateProgress(text) }
    }

    /** 等待网络 VALIDATED，2 秒轮询直到超时；等待期间可被取消。 */
    private suspend fun awaitNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val deadline = System.currentTimeMillis() + NETWORK_WAIT_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (isNetworkValidated(cm)) return true
            updateProgress("等待网络连接…")
            delay(2000)
        }
        return isNetworkValidated(cm)
    }

    /**
     * 网络可用性判断。任何异常（权限缺失、系统服务异常等）都视为「可用」——
     * 绝不能因为「查网络状态」这件事本身失败而阻断签到。
     */
    private fun isNetworkValidated(cm: ConnectivityManager): Boolean = runCatching {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun updateProgress(text: String) {
        try {
            nm.notify(NOTIFY_ID, buildNotification(text))
        } catch (_: SecurityException) {
            // 没通知权限：签到照跑，只是看不到进度
        }
    }

    private fun startAsForeground(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFY_ID, n)
            }
        } catch (e: Exception) {
            // Android 14 起前台服务类型缺失会抛异常；真抛了也不能崩，
            // 退化成「无通知后台跑」——可能被杀，但总比闪退强。
            Log.e(TAG, "startForeground 失败", e)
        }
    }

    // BUGS-v2.63-286 P3：原来的 scheduleNext() / scheduleRetryOnce() 已删除。
    // 它们是 `scope.launch { withContext(NonCancellable) { … } }`，两个 launch 在
    // Dispatchers.Default 上无顺序保证，而 arm() 都落在同一个 PendingIntent 上 ——
    // 谁后跑谁赢（补签被顶成明天，或明天的排期丢失）。而且 onDestroy 的
    // scope.cancel() 会把「还在排队没开始执行」的那个 launch 直接取消，
    // NonCancellable 只保护已经进入 withContext 的那一段 —— 旧注释声称
    // 「NonCancellable 保证续排必然完成」并不成立。
    // 现在统一在 onStartCommand 的 finally 里一次顺序执行、二选一。

    /** 收尾：撤销前台 + 停止服务（闹钟续排已在 finally 里同步完成）。 */
    private fun finishRun() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntentApp.openApp(this)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AutoCheckinService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("定时签到进行中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", stop)
            .build()
    }

    private fun holdWakeLock() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signhub:checkin").apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
        }.onFailure { wakeLock = null }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AutoCheckinService"
        const val ACTION_STOP = "com.signhub.app.action.STOP_CHECKIN"
        private const val CHANNEL_PROGRESS = "signhub_checkin_progress"
        /** 与 CheckinRunner.NOTIFY_ID（2001，结果通知）错开：进度条不能顶掉结果。 */
        private const val NOTIFY_ID = 2003
        private const val NETWORK_WAIT_SECONDS = 60
        /** 15 分钟：五家平台 × 多账号串行 + Trae 错峰 12~25 秒/账号，足够。 */
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L

        fun intent(context: Context): Intent = Intent(context, AutoCheckinService::class.java)

        /** 从广播接收器里调用（精确闹钟属于后台启动前台服务的系统豁免场景）。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, intent(context))
            } catch (e: Exception) {
                // Android 12+ 若不在豁免名单会抛 ForegroundServiceStartNotAllowedException，
                // 退化成 WorkManager（可能不准点，但至少会跑）
                Log.e(TAG, "startForegroundService 被拒，降级到 WorkManager", e)
                runCatching { AutoCheckinScheduler.runNow(context) }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(intent(context).setAction(ACTION_STOP))
            }
        }
    }
}
