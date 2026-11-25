package com.briqt.moke.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

enum class FgsUpdateAction {
    NONE,
    NOTIFY,
    STOP_FOREGROUND,
}

enum class AlertToggleAction {
    ENABLE,
    DISABLE,
    REQUEST_PERMISSION,
    OPEN_APP_NOTIFICATION_SETTINGS,
    OPEN_ALERT_CHANNEL_SETTINGS,
}

/**
 * Tracks foreground-service `startId` lifecycle on the main thread so an empty-queue emission
 * never calls unconditional `stopSelf()` while a newer `startForegroundService` startId is
 * already in flight in ActivityManagerService.
 */
class ForegroundServiceState {
    var started: Boolean = false
        private set
    var lastStartId: Int = 0
        private set

    /**
     * Called immediately after `ServiceCompat.startForeground(...)` in `onStartCommand`.
     * Returns `true` only when `stopSelfResult(startId)` stopped the service (no newer startId pending),
     * indicating the caller should remove its foreground notification.
     */
    fun onStartCommand(
        startId: Int,
        hasActiveWork: Boolean,
        stopWithStartId: (Int) -> Boolean,
    ): Boolean {
        lastStartId = startId
        if (hasActiveWork) {
            started = true
            return false
        }
        val stopped = stopWithStartId(startId)
        started = !stopped
        return stopped
    }

    fun onWorkChanged(
        hasActiveWork: Boolean,
        stopWithStartId: (Int) -> Boolean,
    ): FgsUpdateAction {
        if (!started) return FgsUpdateAction.NONE
        if (hasActiveWork) return FgsUpdateAction.NOTIFY
        val stopped = stopWithStartId(lastStartId)
        return if (stopped) {
            started = false
            FgsUpdateAction.STOP_FOREGROUND
        } else {
            FgsUpdateAction.NONE
        }
    }

    fun onStop() {
        started = false
    }
}

object BackgroundKeepalive {

    const val RESUME_THAW_GRACE_MS = 2_500L
    const val SILENT_PERMISSION_DENY_THRESHOLD_MS = 350L
    private const val PERM_PREFS = "moke_perm_state"
    private const val KEY_NOTIF_RATIONALE_SEEN = "notif_rationale_seen"

    /** 设置「后台保持唤醒」的进程级镜像（默认开启）。 */
    @Volatile var wakeLockEnabled: Boolean = true

    fun shouldCreateWifiLock(sdkInt: Int): Boolean =
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun shouldHoldBackgroundLocks(
        hasLiveSessions: Boolean,
        wakeLockEnabled: Boolean,
        appVisible: Boolean,
    ): Boolean = hasLiveSessions && wakeLockEnabled && !appVisible

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }.getOrDefault(true)

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
        )
        candidates.firstOrNull { startSettingsIntent(context, it) }
    }

    fun openAppDetailsSettings(context: Context) {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        if (!startSettingsIntent(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))) {
            startSettingsIntent(context, Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun isPostNotificationsPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun areAppNotificationsAllowed(context: Context): Boolean = runCatching {
        isPostNotificationsPermissionGranted(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    fun isSessionNotificationChannelEnabled(context: Context): Boolean = runCatching {
        if (!areAppNotificationsAllowed(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = nm.getNotificationChannel(MokeSessionService.CHANNEL_ID) ?: run {
            MokeSessionService.ensureChannel(context)
            nm.getNotificationChannel(MokeSessionService.CHANNEL_ID)
        }
        (channel?.importance ?: NotificationManager.IMPORTANCE_LOW) != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    fun isAlertNotificationChannelEnabled(context: Context): Boolean = runCatching {
        if (!areAppNotificationsAllowed(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = nm.getNotificationChannel(TerminalAlerts.CHANNEL_ID) ?: run {
            TerminalAlerts.ensureChannel(context)
            nm.getNotificationChannel(TerminalAlerts.CHANNEL_ID)
        }
        (channel?.importance ?: NotificationManager.IMPORTANCE_HIGH) != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    fun wasNotifRationaleEverSeen(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PERM_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIF_RATIONALE_SEEN, false)
    }.getOrDefault(false)

    fun recordNotifRationaleIfSeen(context: Context, seenNow: Boolean) {
        if (!seenNow) return
        runCatching {
            context.getSharedPreferences(PERM_PREFS, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_NOTIF_RATIONALE_SEEN, true)
            }
        }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!startSettingsIntent(context, intent)) {
            openAppDetailsSettings(context)
        }
    }

    fun openSessionNotificationChannelSettings(context: Context) {
        runCatching { MokeSessionService.ensureChannel(context) }
        openChannelSettings(context, MokeSessionService.CHANNEL_ID)
    }

    fun openAlertNotificationChannelSettings(context: Context) {
        runCatching { TerminalAlerts.ensureChannel(context) }
        openChannelSettings(context, TerminalAlerts.CHANNEL_ID)
    }

    private fun openChannelSettings(context: Context, channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        if (!startSettingsIntent(context, intent)) {
            openAppNotificationSettings(context)
        }
    }

    tailrec fun findActivity(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    private fun startSettingsIntent(context: Context, intent: Intent): Boolean {
        if (findActivity(context) == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun effectiveTerminalAlertsChecked(
        prefEnabled: Boolean,
        appNotificationsAllowed: Boolean,
        alertChannelEnabled: Boolean,
    ): Boolean = prefEnabled && appNotificationsAllowed && alertChannelEnabled

    fun decideAlertToggle(
        wantChecked: Boolean,
        permissionGranted: Boolean,
        systemNotificationsEnabled: Boolean,
        alertChannelEnabled: Boolean,
    ): AlertToggleAction {
        if (!wantChecked) return AlertToggleAction.DISABLE
        if (!permissionGranted) return AlertToggleAction.REQUEST_PERMISSION
        if (!systemNotificationsEnabled) return AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS
        if (!alertChannelEnabled) return AlertToggleAction.OPEN_ALERT_CHANNEL_SETTINGS
        return AlertToggleAction.ENABLE
    }

    fun decideAlertPermissionResult(
        granted: Boolean,
        systemNotificationsEnabled: Boolean,
        alertChannelEnabled: Boolean,
        wasRationaleBefore: Boolean,
        isRationaleAfter: Boolean,
        rationaleEverSeen: Boolean,
        elapsedMs: Long,
    ): AlertToggleAction {
        if (granted) {
            if (!systemNotificationsEnabled) return AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS
            if (!alertChannelEnabled) return AlertToggleAction.OPEN_ALERT_CHANNEL_SETTINGS
            return AlertToggleAction.ENABLE
        }
        val permanentlyBlocked = !wasRationaleBefore && !isRationaleAfter &&
            (rationaleEverSeen || elapsedMs in 0L until SILENT_PERMISSION_DENY_THRESHOLD_MS)
        return if (permanentlyBlocked) {
            AlertToggleAction.OPEN_APP_NOTIFICATION_SETTINGS
        } else {
            AlertToggleAction.DISABLE
        }
    }

    fun resolveOpenSessionTarget(
        requestedSessionId: String?,
        allSessionIds: List<String>,
        liveSessionIds: List<String>,
    ): String? = requestedSessionId?.takeIf { it in allSessionIds }
        ?: liveSessionIds.singleOrNull()
        ?: allSessionIds.singleOrNull()

    fun shouldMarkEndedInBackground(
        dataReady: Boolean,
        closedByUser: Boolean,
        exitCode: Int,
        appVisible: Boolean,
        dataReadyAtElapsedMs: Long = -1L,
        lastBackgroundedAtElapsedMs: Long = -1L,
        lastResumedAtElapsedMs: Long = -1L,
        nowElapsedMs: Long = -1L,
    ): Boolean {
        if (!dataReady || closedByUser || exitCode == 0) return false
        if (!appVisible) return true
        if (lastBackgroundedAtElapsedMs < 0L || lastResumedAtElapsedMs < 0L || nowElapsedMs < 0L) {
            return false
        }
        val inResumeThawWindow = nowElapsedMs - lastResumedAtElapsedMs in 0L..RESUME_THAW_GRACE_MS
        val establishedBeforeResume = dataReadyAtElapsedMs in 0L..lastResumedAtElapsedMs
        return inResumeThawWindow && establishedBeforeResume
    }
}
