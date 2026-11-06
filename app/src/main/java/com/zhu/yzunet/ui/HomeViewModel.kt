package com.zhu.yzunet.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhu.yzunet.data.AppSettings
import com.zhu.yzunet.data.CampusNetClient
import com.zhu.yzunet.data.LoginOutcome
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
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val client = CampusNetClient()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _ui = MutableStateFlow(UiState(settings = store.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var launched = false

    /** 待执行的防抖落盘任务 */
    private var pendingSave: Job? = null

    /** 界面首次组合时调用：按配置决定是否自动登录 */
    fun onLaunch() {
        if (launched) return
        launched = true

        val settings = _ui.value.settings
        if (settings.autoReconnect) {
            runCatching { AutoLoginService.start(getApplication()) }
        }
        if (!settings.hasCredentials) {
            addLog("还没有保存账号，请填入学工号与密码后点「一键登录」")
            return
        }
        addLog("已载入账号 ${mask(settings.userId)}，服务：${settings.serviceName}")
        if (settings.autoLoginOnLaunch) {
            login()
        } else {
            checkOnly()
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
            runCatching { AutoLoginService.start(getApplication()) }
                .onFailure { addLog("启动后台服务失败：${it.message}") }
            addLog("已开启后台自动重连（间隔 ${_ui.value.settings.checkIntervalSec} 秒）")
        } else {
            runCatching { AutoLoginService.stop(getApplication()) }
            addLog("已关闭后台自动重连")
        }
    }

    // ---------------- 动作 ----------------

    fun login() {
        val state = _ui.value
        if (state.busy) return

        // 输入框不再边输入边 trim（避免光标跳动），提交前统一去空格；密码不做 trim
        val settings = state.settings.copy(
            userId = state.settings.userId.trim(),
            checkUrl = state.settings.checkUrl.trim(),
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
            _ui.update { it.copy(busy = true, status = NetStatus.LoggingIn, statusDetail = "正在登录…") }
            addLog("开始登录 —— 服务：${settings.serviceName}")

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
        val line = "[${timeFormat.format(Date())}] $message"
        _ui.update { state -> state.copy(logs = (state.logs + line).takeLast(300)) }
    }

    private fun mask(value: String): String =
        if (value.length > 6) value.take(3) + "***" + value.takeLast(3) else "***"
}
