package com.briqt.moke.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

data class LiveSessionInfo(
    val id: String,
    val title: String,
)

enum class FgsUpdateAction {
    NONE,
    NOTIFY,
    STOP_FOREGROUND,
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
    const val DEFAULT_MAX_SUMMARY_TITLE_CODE_POINTS = 24

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

    private fun startSettingsIntent(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun singleLiveSessionId(liveSessions: List<LiveSessionInfo>): String? =
        liveSessions.singleOrNull()?.id

    fun resolveOpenSessionTarget(
        requestedSessionId: String?,
        allSessionIds: List<String>,
        liveSessionIds: List<String>,
    ): String? = requestedSessionId?.takeIf { it in allSessionIds }
        ?: liveSessionIds.singleOrNull()
        ?: allSessionIds.singleOrNull()

    fun liveSessionSummary(
        titles: List<String>,
        maxShown: Int = 2,
        maxTitleCodePoints: Int = DEFAULT_MAX_SUMMARY_TITLE_CODE_POINTS,
    ): String {
        val cleaned = titles.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty() || maxShown <= 0) return ""
        val formatted = if (cleaned.size >= 2 && maxTitleCodePoints > 0) {
            cleaned.take(maxShown).map { truncateCodePoints(it, maxTitleCodePoints) }
        } else {
            cleaned.take(maxShown)
        }
        val shown = formatted.joinToString(", ")
        return if (cleaned.size > maxShown) "$shown…" else shown
    }

    fun formatMultiSessionNotification(
        count: Int,
        titles: List<String>,
        formatSummary: (Int, String) -> String,
    ): String {
        val summary = liveSessionSummary(titles)
        if (summary.isNotEmpty()) return formatSummary(count, summary)
        return formatSummary(count, "").trimEnd(' ', '·')
    }

    private fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        if (text.codePointCount(0, text.length) <= maxCodePoints) return text
        val endIndex = text.offsetByCodePoints(0, maxCodePoints)
        return text.substring(0, endIndex).trimEnd() + "…"
    }

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
