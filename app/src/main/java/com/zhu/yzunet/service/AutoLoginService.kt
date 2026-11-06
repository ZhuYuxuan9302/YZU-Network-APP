package com.zhu.yzunet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zhu.yzunet.MainActivity
import com.zhu.yzunet.R
import com.zhu.yzunet.data.AppSettings
import com.zhu.yzunet.data.CampusNetClient
import com.zhu.yzunet.data.LoginOutcome
import com.zhu.yzunet.data.NetworkUtils
import com.zhu.yzunet.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：定时探测外网连通性，断线时用保存的账号自动重新认证。
 * 与 Python 脚本的主循环等价（CHECK_INTERVAL / POST_LOGIN_GRACE 一致）。
 */
class AutoLoginService : Service() {

    /**
     * 兜住所有未捕获的协程异常：
     * 没有这个 handler 的话，监控循环里任何一个意外异常都会按「未捕获异常」处理，直接让 App 闪退。
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "后台协程异常（已忽略，服务继续运行）", e)
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    /**
     * ⚠️ 必须用 by lazy，不能直接初始化。
     * Service 的字段初始化跑在构造函数里，那时系统还没调用 attachBaseContext()，
     * `this.applicationContext` 会抛 NullPointerException —— 进程直接崩溃，
     * 而且发生在 onCreate 之前，任何 try/catch 都兜不住。
     */
    private val client: CampusNetClient by lazy { CampusNetClient(this) }

    /** 常驻监控循环是否在跑 */
    private var running = false

    /** 一次性登录是否在跑（自动化入口触发） */
    private var oneShotRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { createChannel() }
            .onFailure { Log.w(TAG, "创建通知渠道失败", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val oneShot = intent?.action == ACTION_LOGIN_ONCE

        // startForeground 在 Android 12+ 可能抛 ForegroundServiceStartNotAllowedException
        // （比如从权限回调里启动、App 还没真正回到前台）。这里必须兜住：
        // 它跑在主线程上，漏出去就是直接闪退。
        val foregroundReady = runCatching {
            if (oneShot) {
                startAsForeground(buildNotification("正在登录校园网…", "使用已保存的账号"))
            } else {
                startAsForeground(buildNotification("正在监控校园网连接", "等待检测…"))
            }
        }.onFailure {
            Log.w(TAG, "startForeground 失败，停止服务", it)
        }.isSuccess

        if (!foregroundReady) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (oneShot) {
            if (!oneShotRunning) {
                oneShotRunning = true
                val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
                scope.launch { runOnce(force) }
            }
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            scope.launch { monitorLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 一次性登录：用于 QuickLoginActivity / 自动化调用。
     * 默认先检测连通性，已在线则直接跳过；force=true 时无条件重新认证。
     */
    private suspend fun runOnce(force: Boolean) {
        var title = "自动登录完成"
        var text = ""

        try {
            val settings = SettingsStore(this).load()

            if (!settings.hasCredentials) {
                title = "无法自动登录"
                text = "还没有保存账号密码，请先打开应用填写"
                return
            }

            // 非校园网直接跳过，避免连通性检测通过造成「假成功」
            val ssid = NetworkUtils.currentSsid(this)
            if (ssid != null && !NetworkUtils.isCampusSsid(ssid)) {
                title = "不在校园网"
                text = "当前 Wi-Fi「$ssid」不是 ${AppSettings.CAMPUS_SSID}，未执行认证"
                return
            }

            val check = client.checkOnline(settings.checkUrl)
            if (check.online && !force) {
                title = "已在线"
                text = "外网连通正常（${check.detail}），无需重新登录"
                return
            }

            updateNotification("正在登录校园网…", "服务：${settings.serviceName}")

            when (
                val outcome = client.login(
                    settings.userId,
                    settings.password,
                    settings.serviceIndex,
                ) { message -> Log.d(TAG, message) }
            ) {
                is LoginOutcome.Success -> {
                    title = "自动登录成功"
                    text = "服务：${settings.serviceName}"
                }

                is LoginOutcome.Fail -> {
                    title = "自动登录失败"
                    text = outcome.message
                }

                is LoginOutcome.Error -> {
                    title = "自动登录异常"
                    text = outcome.message
                }
            }
        } catch (e: Exception) {
            title = "自动登录异常"
            text = e.message ?: e.javaClass.simpleName
        } finally {
            oneShotRunning = false
            val body = text.ifBlank { "（无详情）" }

            if (running) {
                // 常驻监控还在跑：结果通知给它覆盖，服务继续存活
                updateNotification(title, body)
            } else {
                // 投递结果通知后退出，通知留在通知栏供自动化工具/用户确认
                postNotification(title, body)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun monitorLoop() {
        val store = SettingsStore(this)

        while (currentCoroutineContext().isActive) {
            val settings: AppSettings = store.load()

            if (!settings.hasCredentials) {
                updateNotification("未配置账号", "请先在应用中填写学工号与密码")
                delay(60_000L)
                continue
            }

            if (!NetworkUtils.hasCampusNetwork(this)) {
                updateNotification("未连接网络", "自动重连已暂停（无线 / 有线都没有连接）")
                delay(30_000L)
                continue
            }

            val ssid = NetworkUtils.currentSsid(this)
            if (ssid != null && !NetworkUtils.isCampusSsid(ssid)) {
                updateNotification(
                    "不在校园网",
                    "当前 Wi-Fi「$ssid」不是 ${AppSettings.CAMPUS_SSID}，已暂停自动重连",
                )
                delay(30_000L)
                continue
            }

            val check = client.checkOnline(settings.checkUrl)
            if (check.online) {
                updateNotification("外网连接正常", "${check.detail} · 下次检测 ${settings.checkIntervalSec}s 后")
                delay(settings.checkIntervalSec * 1000L)
                continue
            }

            updateNotification("检测到断线", "${check.detail}，正在自动登录…")
            when (val outcome = client.login(settings.userId, settings.password, settings.serviceIndex)) {
                is LoginOutcome.Success -> {
                    updateNotification("自动登录成功", "暂停检测 ${AppSettings.POST_LOGIN_GRACE_SEC}s，等待网络就绪")
                    delay(AppSettings.POST_LOGIN_GRACE_SEC * 1000L)
                }

                is LoginOutcome.Fail -> {
                    updateNotification("自动登录失败", outcome.message)
                    delay(settings.checkIntervalSec * 1000L)
                }

                is LoginOutcome.Error -> {
                    updateNotification("自动登录异常", outcome.message)
                    delay(settings.checkIntervalSec * 1000L)
                }
            }
        }
    }

    // ---------------- 通知相关 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "校园网自动重连",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "断线时自动帮你重新登录校园网"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean = true,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AutoLoginService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (ongoing) addAction(0, "停止", stopIntent) }
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    /** 投递一条可手动清除的结果通知（一次性登录结束后用） */
    private fun postNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(title, text, ongoing = false)) }
    }

    companion object {
        private const val CHANNEL_ID = "yzu_auto_login"
        private const val NOTIFICATION_ID = 1001

        /** logcat 标签：adb logcat -s yzu */
        const val TAG = "yzu"

        const val ACTION_STOP = "com.zhu.yzunet.action.STOP_AUTO_LOGIN"
        const val ACTION_LOGIN_ONCE = "com.zhu.yzunet.action.LOGIN_ONCE"

        /** true 时跳过连通性检测，强制重新认证 */
        const val EXTRA_FORCE = "force"

        /** 启动常驻监控服务；返回是否成功发起（Android 12+ 后台启动会被拒绝，不抛异常） */
        fun start(context: Context): Boolean =
            startServiceWith(context, Intent(context, AutoLoginService::class.java))

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AutoLoginService::class.java)) }
        }

        /**
         * 一次性登录（供 QuickLoginActivity / 自动化调用）。
         * 必须由处于前台的组件调用，否则 Android 12+ 不允许后台启动前台服务。
         */
        fun loginOnce(context: Context, force: Boolean): Boolean {
            val intent = Intent(context, AutoLoginService::class.java)
                .setAction(ACTION_LOGIN_ONCE)
                .putExtra(EXTRA_FORCE, force)
            return startServiceWith(context, intent)
        }

        private fun startServiceWith(context: Context, intent: Intent): Boolean =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.onFailure {
                Log.w(TAG, "启动前台服务失败", it)
            }.getOrDefault(false)
    }
}
