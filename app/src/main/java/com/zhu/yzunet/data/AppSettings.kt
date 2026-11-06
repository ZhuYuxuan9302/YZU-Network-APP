package com.zhu.yzunet.data

/**
 * 应用配置模型。字段含义与 Python 脚本的环境变量一一对应：
 * userId/password -> YZU_USER_ID / YZU_PASSWORD
 * serviceIndex    -> YZU_SERVICE_INDEX（1-5）
 * checkUrl        -> YZU_CHECK_URL
 */
data class AppSettings(
    val userId: String = "",
    val password: String = "",
    val serviceIndex: Int = DEFAULT_SERVICE_INDEX,
    val checkUrl: String = DEFAULT_CHECK_URL,
    /** 打开应用即自动尝试登录 */
    val autoLoginOnLaunch: Boolean = true,
    /** 后台常驻，检测到断线自动重连 */
    val autoReconnect: Boolean = false,
    /** 后台自动重连时的检测间隔（秒） */
    val checkIntervalSec: Int = DEFAULT_CHECK_INTERVAL,
) {
    val hasCredentials: Boolean
        get() = userId.isNotBlank() && password.isNotBlank()

    val serviceName: String
        get() = SERVICES.getOrElse(serviceIndex - 1) { SERVICES[0] }

    companion object {
        /** 与脚本中的 SERVICE_LIST 顺序、文案完全一致，顺序不可调整 */
        val SERVICES: List<String> = listOf(
            "学校互联网服务",
            "联通互联网服务",
            "移动互联网服务",
            "电信互联网服务",
            "校内免费服务",
        )

        const val DEFAULT_SERVICE_INDEX = 1
        const val DEFAULT_CHECK_URL = "http://connect.rom.miui.com/generate_204"
        const val DEFAULT_CHECK_INTERVAL = 15

        /** 触发网关拦截页的探测地址 */
        const val PORTAL_DETECT_URL = "http://123.123.123.123"

        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/60.0"

        /** 单次连通性检测超时（秒） */
        const val CHECK_TIMEOUT_SEC = 5

        /** 登录成功后暂停检测的时长（秒），等待网络实际就绪 */
        const val POST_LOGIN_GRACE_SEC = 30
    }
}
