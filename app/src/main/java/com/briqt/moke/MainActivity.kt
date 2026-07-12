package com.briqt.moke

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.briqt.moke.ui.MokeApp
import com.briqt.moke.ui.theme.MokeTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响会话，仅无常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边：系统栏透明、内容延伸到栏下，由 Compose 统一处理 insets（消除系统栏色缝）。
        // 应用始终深色 → 强制 dark 样式（浅色状态/导航栏图标）。
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
            MokeTheme {
                MokeApp()
            }
        }
    }
}
