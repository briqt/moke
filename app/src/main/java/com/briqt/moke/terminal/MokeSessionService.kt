package com.briqt.moke.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.briqt.moke.MainActivity
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 前台服务：只要还有活动会话就常驻，借前台优先级让 app 退后台/关屏时进程（及 mosh 子进程）不被回收，
 * 从而会话保持存活。会话对象本身由 [MokeApplication.sessions]（Application 作用域）持有，本服务负责：
 *  - 常驻通知（展示活动会话数）；
 *  - 观察会话列表，归零即自行停止。
 */
class MokeSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val sessions = (application as MokeApplication).sessions
        // 会话数变化即刷新通知；归零即停服。
        sessions.sessions.onEach { list ->
            if (list.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            } else if (started) {
                notificationManager().notify(NOTIF_ID, buildNotification(list.size))
            }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = (application as MokeApplication).sessions.sessions.value.size
        startForeground(NOTIF_ID, buildNotification(count.coerceAtLeast(1)))
        started = true
        if (count == 0) {
            stopForegroundCompat()
            stopSelf()
        }
        // 无会话可续时不自动重启（连接无法凭空恢复）。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Moke")
            .setContentText(localized(R.string.notif_sessions_active, count))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                // 点通知要回到会话，而不只是把 app 拉到前台（见 MainActivity.ACTION_OPEN_SESSIONS）；
                // SINGLE_TOP|CLEAR_TOP 复用已有界面，不另起一个。
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_SESSIONS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun ensureChannel() {
        val ch = NotificationChannel(CHANNEL_ID, localized(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = localized(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(ch)
    }

    private fun notificationManager() =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "moke_sessions"
        private const val NOTIF_ID = 1001
    }
}
