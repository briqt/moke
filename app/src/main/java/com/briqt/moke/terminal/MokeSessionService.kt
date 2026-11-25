package com.briqt.moke.terminal

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.briqt.moke.LocaleManager
import com.briqt.moke.MainActivity
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.data.SettingsStore
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
 * 前台服务：只要还有需要后台保活的活动会话（`keepAliveEligible && alive`）就常驻，借前台服务优先级
 * 让退后台/关屏时进程不被冻结或回收。唤醒锁（及 Android 11–13 的高性能 Wi-Fi 锁）受「后台保持唤醒」开关控制，
 * 且仅在应用退到后台后按需持有、回到前台即释放；Android 14+ 不持有后台无效的低延迟 Wi-Fi 锁。
 */
class MokeSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fgsState = ForegroundServiceState()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentHasLive = false
    private var currentWakeLockEnabled = BackgroundKeepalive.wakeLockEnabled
    private var lastChannelDeliverable = false
    private var lastLangTag = ""

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        initLocks()
        val sessions = (application as MokeApplication).sessions
        sessions.sessions
            .flatMapLatest { list ->
                val eligible = list.filter { it.keepAliveEligible }
                if (eligible.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(eligible.map { it.alive }) { aliveFlags ->
                        aliveFlags.any { it }
                    }
                }
            }
            .distinctUntilChanged()
            .onEach { hasLive ->
                currentHasLive = hasLive
                if (!hasLive) {
                    releaseLocks()
                }
                when (fgsState.onWorkChanged(hasLive, ::stopSelfResult)) {
                    FgsUpdateAction.NOTIFY -> syncLocks()
                    FgsUpdateAction.STOP_FOREGROUND -> {
                        releaseLocks()
                        clearForegroundRunning()
                        stopForegroundCompat()
                    }
                    FgsUpdateAction.NONE -> syncLocks()
                }
            }
            .launchIn(scope)

        SettingsStore(this).backgroundWakeLock
            .distinctUntilChanged()
            .onEach { enabled ->
                BackgroundKeepalive.wakeLockEnabled = enabled
                currentWakeLockEnabled = enabled
                syncLocks()
            }
            .launchIn(scope)

        TerminalAlerts.appVisibleFlow
            .onEach { syncLocks() }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInFlightUntilElapsedMs = 0L
        val hasLive = hasLiveSessions(application)
        currentHasLive = hasLive
        currentWakeLockEnabled = BackgroundKeepalive.wakeLockEnabled
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), fgsType)
        val stopped = fgsState.onStartCommand(startId, hasLive, ::stopSelfResult)
        if (!hasLive) {
            releaseLocks()
            if (stopped) {
                clearForegroundRunning()
                stopForegroundCompat()
            }
        } else if (fgsState.started) {
            lastChannelDeliverable = BackgroundKeepalive.isSessionNotificationChannelEnabled(this)
            lastLangTag = currentLangKey(this)
            markForegroundRunning()
            syncLocks()
        }
        // 无会话可续时不自动重启（连接无法凭空恢复）。
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncOngoingNotificationOnMain()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        startInFlightUntilElapsedMs = 0L
        clearForegroundRunning()
        fgsState.onStop()
        scope.cancel()
        releaseLocks()
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onDestroy() {
        clearForegroundRunning()
        fgsState.onStop()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun markForegroundRunning() {
        activeInstance = this
        isForegroundRunning = true
    }

    private fun clearForegroundRunning() {
        if (activeInstance === this || activeInstance == null) {
            activeInstance = null
            isForegroundRunning = false
            startInFlightUntilElapsedMs = 0L
        }
    }

    private fun initLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply { setReferenceCounted(false) }
        }
        if (BackgroundKeepalive.shouldCreateWifiLock(Build.VERSION.SDK_INT)) {
            runCatching {
                @Suppress("DEPRECATION")
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFILOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
            }
        }
    }

    private fun syncLocks() {
        val shouldHold = fgsState.started && BackgroundKeepalive.shouldHoldBackgroundLocks(
            hasLiveSessions = currentHasLive,
            wakeLockEnabled = currentWakeLockEnabled,
            appVisible = TerminalAlerts.appVisible,
        )
        if (shouldHold) {
            acquireLocks()
        } else {
            releaseLocks()
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

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized(R.string.app_name))
            .setContentText(localized(R.string.notif_channel_desc))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_SESSIONS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "MokeSessionService"
        internal const val CHANNEL_ID = "moke_sessions"
        private const val NOTIF_ID = 1001
        private const val WAKELOCK_TAG = "moke:session-keepalive"
        private const val WIFILOCK_TAG = "moke:session-wifi"
        private const val START_IN_FLIGHT_WINDOW_MS = 4_000L

        @Volatile private var isForegroundRunning = false
        @Volatile private var startInFlightUntilElapsedMs = 0L
        @Volatile private var activeInstance: MokeSessionService? = null

        internal fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.localized(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.localized(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        private fun currentLangKey(context: Context): String {
            val tag = LocaleManager.currentTag(context)
            return tag.ifEmpty { context.resources.configuration.locales[0]?.toLanguageTag().orEmpty() }
        }

        private fun hasLiveSessions(context: Context): Boolean =
            (context.applicationContext as? MokeApplication)
                ?.sessions
                ?.sessions
                ?.value
                ?.any { it.keepAliveEligible && it.alive.value } == true

        internal fun syncOngoingNotificationIfRunning() {
            val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post { syncOngoingNotificationOnMain() }
            } else {
                syncOngoingNotificationOnMain()
            }
        }

        private fun syncOngoingNotificationOnMain() {
            val service = activeInstance ?: return
            if (!isForegroundRunning || !service.fgsState.started || !hasLiveSessions(service)) return
            runCatching {
                val nowDeliverable = BackgroundKeepalive.isSessionNotificationChannelEnabled(service)
                val nowLangTag = currentLangKey(service)
                val nm = service.getSystemService(NotificationManager::class.java) ?: return
                if (!service.lastChannelDeliverable && nowDeliverable) {
                    ensureChannel(service)
                    nm.notify(NOTIF_ID, service.buildNotification())
                } else if (nowDeliverable && service.lastLangTag != nowLangTag) {
                    ensureChannel(service)
                    if (nm.activeNotifications.any { it.id == NOTIF_ID }) {
                        nm.notify(NOTIF_ID, service.buildNotification())
                    }
                }
                service.lastChannelDeliverable = nowDeliverable
                service.lastLangTag = nowLangTag
            }
        }

        fun ensureRunning(context: Context) {
            try {
                val appContext = context.applicationContext ?: context
                val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
                if (mainLooper != null && Looper.myLooper() != mainLooper) {
                    if (!hasLiveSessions(appContext)) return
                    if (!isForegroundRunning && SystemClock.elapsedRealtime() >= startInFlightUntilElapsedMs) {
                        Handler(mainLooper).post { ensureRunningOnMain(appContext) }
                    } else if (isForegroundRunning) {
                        Handler(mainLooper).post { syncOngoingNotificationOnMain() }
                    }
                } else {
                    ensureRunningOnMain(appContext)
                }
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "Failed to start foreground service", e) }
            }
        }

        private fun ensureRunningOnMain(appContext: Context) {
            try {
                if (!hasLiveSessions(appContext)) return
                if (isForegroundRunning) {
                    syncOngoingNotificationOnMain()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now < startInFlightUntilElapsedMs) return
                ContextCompat.startForegroundService(appContext, Intent(appContext, MokeSessionService::class.java))
                startInFlightUntilElapsedMs = now + START_IN_FLIGHT_WINDOW_MS
            } catch (e: Exception) {
                startInFlightUntilElapsedMs = 0L
                runCatching { Log.w(TAG, "Failed to start foreground service", e) }
            }
        }
    }
}
