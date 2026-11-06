package com.zhu.yzunet.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhu.yzunet.data.AppSettings
import com.zhu.yzunet.data.CampusNetClient
import com.zhu.yzunet.data.LoginOutcome
import com.zhu.yzunet.data.NetworkUtils
import com.zhu.yzunet.data.SettingsStore
import com.zhu.yzunet.service.AutoLoginService
import com.zhu.yzunet.util.PinResult
import com.zhu.yzunet.util.ShortcutHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文本输入的防抖落盘时间（毫秒） */
private const val INPUT_DEBOUNCE_MS = 350L

enum class NetStatus {
    /** 还没做任何检测 */
    Idle,
    Checking,
    Online,
    Offline,
    LoggingIn,
    Success,
    Failed,
}

data class UiState(
    val settings: AppSettings = AppSettings(),
    val status: NetStatus = NetStatus.Idle,
    val statusDetail: String = "尚未检测",
    val busy: Boolean = false,
    val logs: List<String> = emptyList(),
    /** 当前是否有 Wi-Fi / 以太网连接（与能否读到 SSID 无关） */
    val hasNetwork: Boolean = false,
    /** 当前 Wi-Fi 名称；null 表示读不到（未连接 / 无定位权限 / 系统定位关闭） */
    val wifiSsid: String? = null,
    /** 是否已获得读取 SSID 所需的定位权限 */
    val canReadSsid: Boolean = false,
    /** 系统「定位」总开关是否开启（权限给了但开关关着，同样读不到 SSID） */
    val locationEnabled: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val client = CampusNetClient(application)

    private val _ui = MutableStateFlow(
        UiState(
            settings = store.load(),
            canReadSsid = NetworkUtils.hasLocationPermission(application),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var launched = false

    /** 待执行的防抖落盘任务 */
    private var pendingSave: Job? = null

    /** 「后台服务启动失败」只提示一次，避免每次 onResume 都刷日志 */
    private var serviceStartWarned = false

    /** 界面首次组合时调用：按配置决定是否自动登录 */
    fun onLaunch() {
        if (launched) return
        launched = true

        refreshNetworkInfo()

        val settings = _ui.value.settings
        if (settings.autoReconnect) {
            ensureAutoReconnectService()
        }
        if (!settings.hasCredentials) {
            addLog("还没有保存账号，请填入学工号与密码后点「一键连接」")
            return
        }
        addLog("已载入账号 ${mask(settings.userId)}，服务：${settings.serviceName}")
        if (settings.autoLoginOnLaunch) {
            login()
        } else {
            checkOnly()
        }
    }

    /** 刷新当前 Wi-Fi 名称与定位状态（授权回调、定位开关变化、网络变化、回到前台时调用） */
    fun refreshNetworkInfo() {
        val app = getApplication<Application>()
        val granted = NetworkUtils.hasLocationPermission(app)
        val locationOn = NetworkUtils.isLocationEnabled(app)
        val hasCampusNet = NetworkUtils.hasCampusNetwork(app)
        val ssid = if (granted && locationOn) NetworkUtils.currentSsid(app) else null
        val previous = _ui.value.wifiSsid
        _ui.update {
            it.copy(
                canReadSsid = granted,
                wifiSsid = ssid,
                hasNetwork = hasCampusNet,
                locationEnabled = locationOn,
            )
        }

        // 只在 Wi-Fi 变化时记一条，避免每次刷新都刷日志
        if (ssid != null && ssid != previous) {
            addLog(
                if (NetworkUtils.isCampusSsid(ssid)) {
                    "当前 Wi-Fi：$ssid（校园网）"
                } else {
                    "当前 Wi-Fi：$ssid（非 ${AppSettings.CAMPUS_SSID}）"
                }
            )
        }
    }

    /**
     * 确保后台服务真的起来了。
     * 必须在 Activity 处于前台时调用 —— Android 12+ 从后台（包括权限回调那一瞬间）
     * 启动前台服务会被系统拒绝，服务里再调 startForeground 就会抛异常。
     */
    fun ensureAutoReconnectService() {
        if (!_ui.value.settings.autoReconnect) return

        if (AutoLoginService.start(getApplication())) {
            serviceStartWarned = false
        } else if (!serviceStartWarned) {
            serviceStartWarned = true
            addLog("后台服务暂时无法启动，回到应用时会自动重试")
        }
    }

    // ---------------- 表单（始终可编辑，改动即时落盘） ----------------

    fun updateUserId(value: String) = mutateSettings(INPUT_DEBOUNCE_MS) { it.copy(userId = value) }

    fun updatePassword(value: String) = mutateSettings(INPUT_DEBOUNCE_MS) { it.copy(password = value) }

    fun updateServiceIndex(index: Int) = mutateSettings {
        it.copy(serviceIndex = index.coerceIn(1, AppSettings.SERVICES.size))
    }

    fun updateCheckUrl(value: String) =
        mutateSettings(INPUT_DEBOUNCE_MS) { it.copy(checkUrl = value) }

    /** 清除已保存的账号信息（需二次确认后调用） */
    fun clearCredentials() {
        mutateSettings { it.copy(userId = "", password = "") }
        addLog("已清除本机保存的账号信息")
    }

    /**
     * 重置全部设置：账号、网络服务、自动化开关、检测地址、运行日志全部恢复出厂状态，
     * 同时停掉后台服务并重建 Keystore 密钥。
     * 用于「配置被改坏了又不知道原值」的恢复场景。
     */
    fun resetAllSettings() {
        // 关键：先取消还在防抖队列里的落盘任务，否则它会把旧配置重新写回去
        pendingSave?.cancel()
        pendingSave = null
        serviceStartWarned = false

        // 先停服务，避免它继续拿旧配置去做自动重连
        AutoLoginService.stop(getApplication())
        store.clear()

        _ui.update {
            it.copy(
                settings = AppSettings(),
                status = NetStatus.Idle,
                statusDetail = "尚未检测",
                busy = false,
                logs = emptyList(),
            )
        }

        addLog("已重置全部设置，账号与自动化配置都已恢复默认值")
        refreshNetworkInfo()
    }

    fun updateInterval(seconds: Int) {
        mutateSettings { it.copy(checkIntervalSec = seconds.coerceIn(5, 300)) }
        if (_ui.value.settings.autoReconnect) {
            // 让后台服务重新读取间隔：重启服务最稳妥
            runCatching {
                AutoLoginService.stop(getApplication())
                AutoLoginService.start(getApplication())
            }
        }
    }

    fun setAutoLoginOnLaunch(enabled: Boolean) =
        mutateSettings { it.copy(autoLoginOnLaunch = enabled) }

    fun setAutoReconnect(enabled: Boolean) {
        mutateSettings { it.copy(autoReconnect = enabled) }
        if (enabled) {
            if (!_ui.value.settings.hasCredentials) {
                addLog("提示：账号为空，后台自动重连会等你填好账号后再工作")
            }
            addLog("已开启后台自动重连（间隔 ${_ui.value.settings.checkIntervalSec} 秒）")
            // 立刻尝试启动；若此刻 App 还没回到前台（比如刚关掉权限弹窗），
            // onResume 里会再补一次，不会因此崩溃
            ensureAutoReconnectService()
        } else {
            AutoLoginService.stop(getApplication())
            addLog("已关闭后台自动重连")
        }
    }

    // ---------------- 动作 ----------------

    /**
     * 登录入口。默认**先探测连通性，确认离线才提交认证**：
     * 已经在线时网关不会拦截请求，也就拿不到认证入口，硬登录只会得到一个假失败。
     *
     * @param force true 时跳过检测直接认证，用于切换运营商服务或强制重新登录。
     */
    fun login(force: Boolean = false) {
        val state = _ui.value
        if (state.busy) return

        // 输入框不再边输入边 trim（避免光标跳动），提交前统一去空格；密码不做 trim
        val settings = state.settings.copy(
            userId = state.settings.userId.trim(),
            checkUrl = state.settings.checkUrl.trim().ifBlank { AppSettings.DEFAULT_CHECK_URL },
        )
        if (!settings.hasCredentials) {
            addLog("请先填写学工号和密码")
            return
        }
        if (!settings.checkUrl.startsWith("http://") && !settings.checkUrl.startsWith("https://")) {
            addLog("连通性检测地址必须以 http:// 或 https:// 开头")
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }

            val app = getApplication<Application>()
            val canReadSsid = NetworkUtils.hasLocationPermission(app)
            val locationOn = NetworkUtils.isLocationEnabled(app)
            val hasCampusNet = NetworkUtils.hasCampusNetwork(app)
            val ssid = if (canReadSsid && locationOn) NetworkUtils.currentSsid(app) else null
            _ui.update {
                it.copy(
                    canReadSsid = canReadSsid,
                    wifiSsid = ssid,
                    hasNetwork = hasCampusNet,
                    locationEnabled = locationOn,
                )
            }

            // ⓪ 先确认连的确实是校园网：别的 Wi-Fi 上连通性检测同样会通过，
            //    不校验 SSID 就会出现「假成功」——检测说在线，其实根本没认证。
            if (!hasCampusNet) {
                addLog("当前没有连接到 Wi-Fi / 以太网，已中止认证")
                _ui.update {
                    it.copy(
                        busy = false,
                        status = NetStatus.Failed,
                        statusDetail = "未连接 Wi-Fi，请先连上 ${AppSettings.CAMPUS_SSID}",
                    )
                }
                return@launch
            }

            when {
                ssid == null -> addLog(
                    if (canReadSsid) {
                        "无法读取 Wi-Fi 名称，已跳过校名校验"
                    } else {
                        "未授予定位权限，无法校验 Wi-Fi 名称，已跳过校名校验"
                    }
                )

                !NetworkUtils.isCampusSsid(ssid) -> {
                    addLog("当前 Wi-Fi 是「$ssid」，不是 ${AppSettings.CAMPUS_SSID}，已中止认证")
                    _ui.update {
                        it.copy(
                            busy = false,
                            status = NetStatus.Failed,
                            statusDetail = "当前连接「$ssid」不是 ${AppSettings.CAMPUS_SSID}，未执行认证",
                        )
                    }
                    return@launch
                }

                else -> addLog("校园网校名校验通过：$ssid")
            }

            // ① 先探测：在线就没必要认证
            if (!force) {
                _ui.update { it.copy(status = NetStatus.Checking, statusDetail = "正在检测网络…") }
                addLog("先检测外网连通性…")
                val check = client.checkOnline(settings.checkUrl)
                addLog("连通性：${if (check.online) "在线" else "离线"}（${check.detail}）")

                if (check.online) {
                    _ui.update {
                        it.copy(
                            busy = false,
                            status = NetStatus.Online,
                            statusDetail = "外网已可用（${check.detail}），无需登录",
                        )
                    }
                    addLog("外网已可用，跳过认证（如需切换服务请用「强制认证」）")
                    return@launch
                }
                addLog("检测到未连接外网，开始认证…")
            } else {
                addLog("已跳过连通性检测，强制重新认证…")
            }

            // ② 执行认证
            _ui.update { it.copy(status = NetStatus.LoggingIn, statusDetail = "正在认证…") }
            addLog("提交认证 —— 服务：${settings.serviceName}")

            val outcome = client.login(
                userId = settings.userId,
                password = settings.password,
                serviceIndex = settings.serviceIndex,
            ) { message -> addLog(message) }

            when (outcome) {
                is LoginOutcome.Success -> {
                    _ui.update {
                        it.copy(
                            status = NetStatus.Success,
                            statusDetail = "认证成功，正在复检连通性…",
                        )
                    }
                }

                is LoginOutcome.Fail -> {
                    _ui.update { it.copy(status = NetStatus.Failed, statusDetail = outcome.message) }
                }

                is LoginOutcome.Error -> {
                    _ui.update { it.copy(status = NetStatus.Failed, statusDetail = outcome.message) }
                }
            }

            // ③ 认证成功后复检，确认外网真的通了
            if (outcome is LoginOutcome.Success) {
                delay(1200)
                val check = client.checkOnline(settings.checkUrl)
                addLog("连通性复检：${if (check.online) "在线" else "离线"}（${check.detail}）")
                _ui.update {
                    it.copy(
                        status = if (check.online) NetStatus.Online else NetStatus.Success,
                        statusDetail = if (check.online) {
                            "已完成认证，外网可达（${check.detail}）"
                        } else {
                            "认证成功，外网尚未就绪（${check.detail}）"
                        },
                    )
                }
            }

            _ui.update { it.copy(busy = false) }
        }
    }

    fun checkOnly() {
        if (_ui.value.busy) return

        // 点「刷新」时顺手重新读一次 Wi-Fi 名称与定位状态：
        // 用户很可能刚从设置里打开定位，界面上的旧状态需要立刻纠正
        refreshNetworkInfo()

        viewModelScope.launch {
            _ui.update { it.copy(busy = true, status = NetStatus.Checking, statusDetail = "正在检测…") }
            val check = client.checkOnline(_ui.value.settings.checkUrl.trim())
            addLog("连通性检测：${if (check.online) "在线" else "离线"}（${check.detail}）")
            _ui.update {
                it.copy(
                    busy = false,
                    status = if (check.online) NetStatus.Online else NetStatus.Offline,
                    statusDetail = check.detail,
                )
            }
        }
    }

    fun clearLogs() = _ui.update { it.copy(logs = emptyList()) }

    /** 往桌面钉一个「一键登录」快捷方式（直接指向 QuickLoginActivity） */
    fun createShortcut() {
        val message = when (ShortcutHelper.requestPin(getApplication())) {
            PinResult.Requested ->
                "已请求添加桌面快捷方式，请在系统弹窗中点「添加」"

            PinResult.AlreadyPinned ->
                "桌面已经有「一键登录」快捷方式了"

            PinResult.Unsupported ->
                "当前桌面不支持应用直接添加，请用桌面的「添加小部件 / 快捷方式」手动创建"

            PinResult.Failed ->
                "创建快捷方式失败，请稍后重试"
        }
        addLog(message)
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    // ---------------- 内部 ----------------

    private fun mutateSettings(
        debounceMs: Long = 0L,
        block: (AppSettings) -> AppSettings,
    ) {
        val next = block(_ui.value.settings)
        _ui.update { it.copy(settings = next) }

        // 文本输入做防抖，避免每个字符都触发一次 Keystore 加密 + 写盘
        pendingSave?.cancel()
        if (debounceMs <= 0L) {
            store.save(next)
        } else {
            pendingSave = viewModelScope.launch {
                delay(debounceMs)
                withContext(Dispatchers.IO) { store.save(next) }
            }
        }
    }

    override fun onCleared() {
        // 兜底：退出前把可能还在防抖队列里的改动落盘
        runCatching { store.save(_ui.value.settings) }
        super.onCleared()
    }

    private fun addLog(message: String) {
        // 每次新建 formatter：SimpleDateFormat 不是线程安全的，
        // 而 refreshNetworkInfo 可能被广播 / 网络回调从非主线程触发
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message"
        _ui.update { state -> state.copy(logs = (state.logs + line).takeLast(300)) }
    }

    private fun mask(value: String): String =
        if (value.length > 6) value.take(3) + "***" + value.takeLast(3) else "***"
}
