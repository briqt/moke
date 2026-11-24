package com.briqt.moke

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.terminal.MokeSessionService
import com.briqt.moke.terminal.TerminalAlerts
import com.briqt.moke.ui.MokeApp
import com.briqt.moke.ui.MokeViewModel
import com.briqt.moke.ui.theme.MokeTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响会话，仅无常驻通知 */ }

    private var notifPermissionAsked = false

    // 与 setContent 里 viewModel() 拿到的是同一个实例（同一 ViewModelStore）。
    private val vm: MokeViewModel by viewModels()

    /**
     * Android 13+ 需授权才会显示后台保活通知（拒绝仅影响通知，不影响会话）。
     *
     * **等到真的开了第一个会话再问**：冷启动就弹权限窗时，用户还没连过任何主机，无从判断这个
     * 权限是干什么用的；而这条通知只在有会话（前台服务）时才存在。
     */
    private fun requestNotifPermissionOnce() {
        if (notifPermissionAsked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notifPermissionAsked = true
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 应用内语言：按所选语言包裹 context（切换语言后 recreate() 重新走这里）。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边：系统栏透明、内容延伸到栏下，由 Compose 统一处理 insets（消除系统栏色缝）。
        // 这里先按深色铺一次，真正的明暗随主题设置在下方 LaunchedEffect 里重设。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 只处理首次创建：重建（如切换语言）时 intent 还是那一个，不能再跳一次。
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val vm: MokeViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val dynamicColor by vm.dynamicColor.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 把解析好的明暗回灌 VM：终端配色的「随明暗联动」据此选用哪一套。
            LaunchedEffect(dark) { vm.setAppIsDark(dark) }
            // 开出第一个活动会话时才问后台通知权限（见 requestNotifPermissionOnce）。
            val hasSession by vm.sessions.sessions.collectAsState()
            LaunchedEffect(hasSession) {
                if (hasSession.any { it.keepAliveEligible && it.alive.value }) requestNotifPermissionOnce()
            }
            // 系统栏图标明暗随主题：浅色主题下必须切 light 样式，否则白底上的白图标看不见。
            // 运行中切换系统深浅色时，系统处理配置变化会在我们之后再按窗口主题重置一次栏样式（时序竞争，
            // 实测时有时无），所以稍后再补设一次，以最后一次为准。
            LaunchedEffect(dark) {
                barsDark = dark
                applySystemBars(dark)
                kotlinx.coroutines.delay(400)
                applySystemBars(dark)
            }
            MokeTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                MokeApp(vm)
            }
        }
    }

    /** 当前应用明暗（供配置变化后补设系统栏样式）。 */
    private var barsDark = true

    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统在配置变化里可能重置栏样式：排到其后补设当前值（明暗若随之改变，LaunchedEffect 会再设新值）。
        window.decorView.post { applySystemBars(barsDark) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SESSIONS) vm.requestOpenSessions(intent.getStringExtra(EXTRA_SESSION_ID))
    }

    // 终端提醒只在"用户不在场"时发：界面是否在前台由这里告诉 TerminalAlerts。
    override fun onStart() {
        super.onStart()
        TerminalAlerts.onAppStarted()
        MokeSessionService.ensureRunning(this)
        // 从后台切回来时终端页并未重新进入组合，"进入会话即收起提醒"要在这里补上。
        TerminalAlerts.visibleSessionId?.let { TerminalAlerts.cancel(this, it) }
    }

    override fun onStop() {
        TerminalAlerts.onAppStopped()
        super.onStop()
    }

    companion object {
        /** 后台保活通知的点击动作：回到会话（只有一个就直接进，多个就进会话列表）。 */
        const val ACTION_OPEN_SESSIONS = "com.briqt.moke.action.OPEN_SESSIONS"

        /** 可选：直接回到这个会话（终端提醒的通知带它）；会话已不在时退回上面的规则。 */
        const val EXTRA_SESSION_ID = "com.briqt.moke.extra.SESSION_ID"
    }
}
