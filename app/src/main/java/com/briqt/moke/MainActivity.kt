package com.briqt.moke

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.ui.MokeApp
import com.briqt.moke.ui.MokeViewModel
import com.briqt.moke.ui.theme.MokeTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响会话，仅无常驻通知 */ }

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
        // Android 13+ 需授权才会显示后台保活通知（拒绝仅影响通知，不影响会话）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            // 系统栏图标明暗随主题：浅色主题下必须切 light 样式，否则白底上的白图标看不见。
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            MokeTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                MokeApp(vm)
            }
        }
    }
}
