package com.briqt.moke.terminal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.briqt.moke.MainActivity
import com.briqt.moke.R
import com.briqt.moke.localized

/**
 * 终端提醒：远端程序响铃（BEL）或发出通知序列（OSC 9 / OSC 777）时，若用户此刻不在这个会话里，
 * 发一条系统通知。只实现通用终端协议，不针对任何特定程序（交互原则 P6）；设置开关默认关闭。
 *
 * 状态放在进程作用域：回调来自终端（任意会话、可能没有界面），不能依赖 ViewModel 活着。
 */
object TerminalAlerts {

    /** 设置「响铃与通知提醒」的镜像（`MokeApplication` 维护）。默认关闭。 */
    @Volatile var enabled: Boolean = false

    /** 应用界面是否在前台（`MainActivity` 的 onStart / onStop 维护）。 */
    @Volatile var appVisible: Boolean = false

    /** 当前正在看的会话（终端页显示时设置，离开时清空）。 */
    @Volatile var visibleSessionId: String? = null

    private const val CHANNEL_ID = "moke_alerts"

    /** 同一会话两次提醒的最小间隔：程序连响几下只算一次，不刷屏。 */
    private const val MIN_INTERVAL_MS = 5_000L

    private val lastPostedAt = HashMap<String, Long>()

    /**
     * 远端程序请求提醒。[title] / [body] 来自 OSC 777 / OSC 9；响铃时二者皆为 null。
     * 用户正看着这个会话时不打扰——提醒本来就是为"你不在场"准备的。
     */
    fun post(context: Context, sessionId: String, sessionTitle: String, title: String?, body: String?) {
        if (!enabled) return
        if (appVisible && visibleSessionId == sessionId) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lastPostedAt) {
            val last = lastPostedAt[sessionId]
            if (last != null && now - last < MIN_INTERVAL_MS) return
            lastPostedAt[sessionId] = now
        }
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val headline = title?.takeIf { it.isNotBlank() }
        val message = body?.takeIf { it.isNotBlank() }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SESSIONS)
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline ?: sessionTitle)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (message != null) setContentText(message)
                if (headline != null) setSubText(sessionTitle)
            }
            .setContentIntent(
                // requestCode 按会话区分：同一 Activity 的多条 PendingIntent 只差 extra 时会被合并成一条。
                PendingIntent.getActivity(
                    context, notificationId(sessionId), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        runCatching { nm.notify(notificationId(sessionId), n) }
    }

    /** 进入会话 / 会话关闭时收起它的提醒。 */
    fun cancel(context: Context, sessionId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(sessionId)) }
        synchronized(lastPostedAt) { lastPostedAt.remove(sessionId) }
    }

    /**
     * 进程启动时调用：会话不跨进程存活，上一个进程留下的提醒都指向已不存在的会话，一并收起。
     */
    fun cancelStale(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }.forEach { nm.cancel(it.id) }
        }
    }

    /** 每个会话一条通知：新的提醒覆盖旧的。避开前台服务通知所用的 id。 */
    private fun notificationId(sessionId: String): Int = 0x4D4B0000 or (sessionId.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.localized(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
