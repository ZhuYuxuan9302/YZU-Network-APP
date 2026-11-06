package com.zhu.yzunet.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

object NetworkUtils {

    /** 当前认证所用的物理连接类型 */
    enum class Transport { None, Wifi, Ethernet }

    /** 无权限时系统返回的脱敏值 */
    private const val SSID_REDACTED = "<unknown ssid>"
    private const val SSID_UNKNOWN = "unknown ssid"

    /**
     * 找出用于校园网认证的本地网络：优先 Wi-Fi，其次以太网。
     *
     * 这里刻意**不要求该网络已验证联外网**：
     * 校园 Wi-Fi 未认证时系统会把它标记为「无互联网」，甚至把默认路由切到蜂窝网络，
     * 而这正是我们需要处理和认证的状态。
     */
    fun campusNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        return try {
            val networks = cm.allNetworks
            networks.firstOrNull { hasTransport(cm, it, NetworkCapabilities.TRANSPORT_WIFI) }
                ?: networks.firstOrNull {
                    hasTransport(cm, it, NetworkCapabilities.TRANSPORT_ETHERNET)
                }
                ?: activeLocalNetwork(cm)
        } catch (e: Exception) {
            // 个别 ROM 在关闭定位时会限制 getAllNetworks()，退回当前活动网络
            activeLocalNetwork(cm)
        }
    }

    private fun activeLocalNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            active
        } else {
            null
        }
    }

    fun hasCampusNetwork(context: Context): Boolean = campusNetwork(context) != null

    /**
     * 当前连接类型。有线（以太网）也支持：某些宿舍/实验室走网线，
     * 这种情况下没有 SSID，校名校验不适用。
     */
    fun currentTransport(context: Context): Transport {
        val network = campusNetwork(context) ?: return Transport.None
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Transport.None
        val caps = cm.getNetworkCapabilities(network) ?: return Transport.None

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            else -> Transport.None
        }
    }

    /**
     * Android 8.1 起，读取 Wi-Fi 名称需要定位权限；没有权限时系统会把 SSID 脱敏成
     * `<unknown ssid>`，所以必须先检查这项权限，才能判断提示语该怎么说。
     */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 系统「定位」总开关是否开启。
     * 注意与 [hasLocationPermission] 是两回事：权限给了但总开关关着，同样读不到 SSID。
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 当前 Wi-Fi 的 SSID；读不到（无权限 / 未连接 / 已脱敏）时返回 null */
    fun currentSsid(context: Context): String? {
        val network = campusNetwork(context) ?: return null

        val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(network)
            normalizeSsid((caps?.transportInfo as? WifiInfo)?.ssid)
        } else {
            null
        }

        return fromCapabilities ?: normalizeSsid(legacySsid(context))
    }

    @Suppress("DEPRECATION")
    private fun legacySsid(context: Context): String? = try {
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
    } catch (e: Exception) {
        null
    }

    /**
     * 是否连在校园网上。
     * 采用「完全相等 或 前缀 + 连字符」匹配，兼容 `YZU-WLAN-5G` 这类衍生 SSID，
     * 同时不会把 `YZU-WLANX` 之类误判成校园网。
     */
    fun isCampusSsid(ssid: String?): Boolean {
        val value = normalizeSsid(ssid)?.uppercase(Locale.ROOT) ?: return false
        val target = AppSettings.CAMPUS_SSID.uppercase(Locale.ROOT)
        return value == target || value.startsWith("$target-")
    }

    private fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().trim('"').trim()
        if (value.isEmpty()) return null
        if (value.equals(SSID_REDACTED, ignoreCase = true)) return null
        if (value.equals(SSID_UNKNOWN, ignoreCase = true)) return null
        return value
    }

    private fun hasTransport(cm: ConnectivityManager, network: Network, transport: Int): Boolean =
        cm.getNetworkCapabilities(network)?.hasTransport(transport) == true
}
