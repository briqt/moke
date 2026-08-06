package com.briqt.moke.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.briqt.moke.MainActivity
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.terminal.sftp.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 文件传输的前台服务。
 *
 * 与 [MokeSessionService] 分开，因为两者的"什么时候可以停"完全不同：会话服务在会话归零时停，
 * 传输服务在队列跑空时停。混在一个服务里会让停止条件变得难以推理，也会让通知说不清现在在干嘛。
 * 类型是 `dataSync`（Android 14+ 还需 FOREGROUND_SERVICE_DATA_SYNC 权限）——传文件不是"特殊用途"。
 */
class MokeTransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val transfers = (application as MokeApplication).transfers
        transfers.tasks.onEach { list ->
            val active = list.filter { it.active }
            if (active.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            } else if (started) {
                notificationManager().notify(NOTIF_ID, build(active))
            }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = (application as MokeApplication).transfers.tasks.value.filter { it.active }
        startForeground(NOTIF_ID, build(active))
        started = true
        if (active.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
        // 传输无法凭空恢复（凭据与 SAF 授权都要 app 进程在），被杀后由用户在应用内「继续」。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(active: List<TransferTask>): android.app.Notification {
        val running = active.firstOrNull { it.state == com.briqt.moke.terminal.sftp.TransferState.RUNNING }
            ?: active.first()
        val text = if (active.size > 1) {
            getString(R.string.notif_transfer_multi, running.name, active.size)
        } else {
            running.name
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_transfer_title))
            .setContentText(text)
            .setSubText(RemotePath.formatSize(running.done) + (if (running.total > 0) " / " + RemotePath.formatSize(running.total) else ""))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val fraction = running.fraction
        if (fraction != null) b.setProgress(100, (fraction * 100).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_transfer_channel_desc)
                setShowBadge(false)
            }
            notificationManager().createNotificationChannel(ch)
        }
    }

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    companion object {
        private const val CHANNEL_ID = "moke_transfers"
        private const val NOTIF_ID = 1002
    }
}
