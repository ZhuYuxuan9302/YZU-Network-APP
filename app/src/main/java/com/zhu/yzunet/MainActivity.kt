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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (pendingAutoReconnect) {
                pendingAutoReconnect = false
                // 即便用户拒绝通知权限，前台服务依然能跑（只是通知不可见），所以照常开启
                vm.setAutoReconnect(true)
            }
        }

    /**
     * 读取 Wi-Fi 名称（SSID）在 Android 8.1+ 需要定位权限。
     * 拒绝也能用，只是无法校验当前是不是 YZU-WLAN，界面会给出提示。
     */
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.refreshNetworkInfo()
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
                    onRequestSsidPermission = {
                        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能在后台切换了 Wi-Fi，回到前台刷新一次
        vm.refreshNetworkInfo()
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }
}
