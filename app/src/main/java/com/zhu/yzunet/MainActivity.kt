package com.zhu.yzunet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.zhu.yzunet.ui.HomeScreen
import com.zhu.yzunet.ui.HomeViewModel
import com.zhu.yzunet.ui.theme.YzuTheme

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    private var pendingAutoReconnect = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingAutoReconnect) {
                pendingAutoReconnect = false
                // 即便用户拒绝通知权限，前台服务依然能跑（只是通知不可见），所以照常开启
                vm.setAutoReconnect(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            YzuTheme {
                HomeScreen(
                    vm = vm,
                    onRequestAutoReconnect = { enabled ->
                        if (enabled && needsNotificationPermission()) {
                            pendingAutoReconnect = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setAutoReconnect(enabled)
                        }
                    },
                )
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }
}
