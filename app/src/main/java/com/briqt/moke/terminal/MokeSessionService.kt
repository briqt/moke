package com.briqt.moke.terminal

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.briqt.moke.MainActivity
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 前台服务：只要还有需要后台保活的活动会话（`keepAliveEligible && alive`）就常驻，借前台优先级与唤醒锁
 * 让退后台/关屏时进程、CPU 定时器与 Wi-Fi 收发不被冻结。活动会话归零时按最新 `startId` 安全停服并释放锁。
 */
class MokeSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fgsState = ForegroundServiceState()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        initLocks()
        val sessions = (application as MokeApplication).sessions
        sessions.sessions
            .flatMapLatest { list ->
                val eligible = list.filter { it.keepAliveEligible }
                if (eligible.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        eligible.map { ts ->
                            combine(ts.alive, ts.displayTitle) { isAlive, title ->
                                if (isAlive) LiveSessionInfo(ts.id, title) else null
                            }
                        }
                    ) { items -> items.filterNotNull() }
                }
            }
            .distinctUntilChanged()
            .onEach { live ->
                if (live.isEmpty()) {
                    releaseLocks()
                }
                when (fgsState.onWorkChanged(live.isNotEmpty(), ::stopSelfResult)) {
                    FgsUpdateAction.NOTIFY -> {
                        acquireLocks()
                        notificationManager().notify(NOTIF_ID, buildNotification(live))
                    }
                    FgsUpdateAction.STOP_FOREGROUND -> stopForegroundCompat()
                    FgsUpdateAction.NONE -> Unit
                }
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val live = currentLiveSessions()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(live), fgsType)
        val stopped = fgsState.onStartCommand(startId, live.isNotEmpty(), ::stopSelfResult)
        if (live.isEmpty()) {
            releaseLocks()
            if (stopped) {
                stopForegroundCompat()
            }
        } else if (fgsState.started) {
            acquireLocks()
        }
        // 无会话可续时不自动重启（连接无法凭空恢复）。
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        fgsState.onStop()
        scope.cancel()
        releaseLocks()
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onDestroy() {
        fgsState.onStop()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun currentLiveSessions(): List<LiveSessionInfo> =
        (application as MokeApplication).sessions.sessions.value
            .filter { it.keepAliveEligible && it.alive.value }
            .map { LiveSessionInfo(it.id, it.displayTitle.value) }

    private fun initLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply { setReferenceCounted(false) }
        }
        runCatching {
            // API 30–33 uses WIFI_MODE_FULL_HIGH_PERF (active while backgrounded/screen-off);
            // API 34 deprecated WIFI_MODE_FULL_HIGH_PERF and maps it internally to WIFI_MODE_FULL_LOW_LATENCY.
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(wifiMode, WIFILOCK_TAG)
                ?.apply { setReferenceCounted(false) }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            wakeLock?.let { if (!it.isHeld) it.acquire() }
        }
        runCatching {
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun buildNotification(live: List<LiveSessionInfo>) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Moke")
            .setContentText(
                when {
                    live.isEmpty() -> localized(R.string.notif_channel_desc)
                    live.size == 1 -> localized(R.string.notif_session_single, live[0].title)
                    else -> BackgroundKeepalive.formatMultiSessionNotification(
                        count = live.size,
                        titles = live.map { it.title },
                    ) { count, summary ->
                        localized(R.string.notif_sessions_summary, count, summary)
                    }
                }
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_SESSIONS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .apply {
                            BackgroundKeepalive.singleLiveSessionId(live)?.let { sessionId ->
                                putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
                            }
                        },
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
        private const val TAG = "MokeSessionService"
        private const val CHANNEL_ID = "moke_sessions"
        private const val NOTIF_ID = 1001
        private const val WAKELOCK_TAG = "moke:session-keepalive"
        private const val WIFILOCK_TAG = "moke:session-wifi"

        fun ensureRunning(context: Context): Boolean = try {
            val appContext = context.applicationContext ?: context
            val hasLive = (appContext as? MokeApplication)
                ?.sessions
                ?.sessions
                ?.value
                ?.any { it.keepAliveEligible && it.alive.value } == true
            if (!hasLive) {
                false
            } else {
                ContextCompat.startForegroundService(appContext, Intent(appContext, MokeSessionService::class.java))
                true
            }
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Failed to start foreground service", e) }
            false
        }
    }
}
