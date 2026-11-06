package com.zhu.yzunet.data

import android.content.Context
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CheckResult(val online: Boolean, val detail: String)

sealed interface LoginOutcome {
    /** 认证成功 */
    data object Success : LoginOutcome

    /** 服务器明确返回 result=fail */
    data class Fail(val message: String) : LoginOutcome

    /** 网络异常 / 响应无法解析 */
    data class Error(val message: String) : LoginOutcome
}

/**
 * 校园网（深澜 srun eportal）认证客户端。
 * 流程完全对齐 Python 脚本：
 *   1. 访问 123.123.123.123 触发网关拦截页
 *   2. 从拦截页里解析出实时有效的认证入口 URL
 *   3. 拆出 host 与 queryString
 *   4. POST http://{host}/eportal/InterFace.do?method=login
 *
 * 所有请求都会**绑定到 Wi-Fi / 以太网**发出：手机同时开着移动数据时，
 * 系统可能把流量路由到蜂窝网络，那样既测不准连通性，也到不了校园网关。
 */
class CampusNetClient(context: Context) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var boundNetwork: Network? = null

    @Volatile
    private var boundClient: OkHttpClient? = null

    private val defaultClient: OkHttpClient = newBuilder().build()

    private fun newBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(AppSettings.CHECK_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)

    /**
     * 取绑定到校园网的 client；没有 Wi-Fi / 以太网时退回默认 client。
     * 绑定方式：socketFactory 走该网络的底层 socket，DNS 也由该网络解析。
     */
    private fun client(): OkHttpClient {
        val network = NetworkUtils.campusNetwork(appContext) ?: return defaultClient
        boundClient?.let { cached -> if (boundNetwork == network) return cached }

        return try {
            val bound = newBuilder()
                .socketFactory(network.socketFactory)
                .dns(Dns { hostname -> network.getAllByName(hostname).toList() })
                .build()
            boundNetwork = network
            boundClient = bound
            bound
        } catch (e: Exception) {
            defaultClient
        }
    }

    /**
     * 主动探测外网连通性：返回 204 视为在线，其它状态 / 超时 / 连接失败均视为断线。
     */
    suspend fun checkOnline(checkUrl: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(checkUrl)
                .header("User-Agent", AppSettings.USER_AGENT)
                .cacheControl(CacheControl.Builder().noCache().noStore().build())
                .build()
            client().newCall(request).execute().use { response ->
                CheckResult(response.code == 204, "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            CheckResult(false, e.javaClass.simpleName)
        }
    }

    /**
     * 完整登录流程。
     * @param log 过程日志回调，用于界面实时展示
     */
    suspend fun login(
        userId: String,
        password: String,
        serviceIndex: Int,
        log: (String) -> Unit = {},
    ): LoginOutcome = withContext(Dispatchers.IO) {
        val service = AppSettings.SERVICES.getOrNull(serviceIndex - 1)
            ?: return@withContext LoginOutcome.Error("网络服务序号非法：$serviceIndex")

        val portalUrl = try {
            discoverPortalUrl(log)
        } catch (e: Exception) {
            return@withContext LoginOutcome.Error(
                "无法获取认证入口：${e.message ?: e.javaClass.simpleName}"
            )
        }
        log("认证入口：$portalUrl")

        val parsed = parsePortalUrl(portalUrl)
            ?: return@withContext LoginOutcome.Error("无法从认证入口解析 host 与 queryString：$portalUrl")
        val (host, queryString) = parsed
        log("host=$host，服务：$service，开始提交认证…")

        // 首次与脚本保持一致：service 先编码一次，再由表单整体编码（双重编码）
        var outcome = postLogin(host, queryString, userId, password, service, doubleEncode = true, log = log)
        if (outcome is LoginOutcome.Success) return@withContext outcome

        // 少数版本的网关只接受单次编码，遇到参数类失败时自动回落重试一次
        val shouldRetry = outcome is LoginOutcome.Error ||
            (outcome is LoginOutcome.Fail && outcome.message.let {
                it.contains("服务") || it.contains("service", true) || it.contains("参数")
            })
        if (shouldRetry) {
            log("认证参数可能不被接受，改用单次编码的 service 重试…")
            val second = postLogin(host, queryString, userId, password, service, doubleEncode = false, log = log)
            if (second is LoginOutcome.Success) return@withContext second
            outcome = second
        }
        outcome
    }

    /**
     * 访问探测地址，从网关拦截页里抠出认证入口 URL。
     * 主探测地址不可用时，会依次换备用地址再触发一次拦截页。
     */
    private fun discoverPortalUrl(log: (String) -> Unit): String {
        var lastError = "未知错误"

        for (probe in PROBE_URLS) {
            log("正在访问 $probe 触发网关拦截页…")
            val request = Request.Builder()
                .url(probe)
                .header("User-Agent", AppSettings.USER_AGENT)
                .cacheControl(CacheControl.Builder().noCache().noStore().build())
                .build()

            val text = try {
                client().newCall(request).execute().use { response ->
                    log("拦截页响应：HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}（$probe）"
                log("探测地址无法访问：$lastError")
                continue
            }

            val match = Regex("href='([^']+)'").find(text)
                ?: Regex("href=\"([^\"]+)\"").find(text)
            if (match != null) return match.groupValues[1]

            lastError = "页面里没有认证入口（$probe，前 120 字：${
                text.take(120).ifBlank { "<EMPTY>" }
            }）"
        }

        throw IllegalStateException(lastError)
    }

    /** 从认证入口 URL 拆出 host 与 queryString（queryString 含 eportal 路径） */
    private fun parsePortalUrl(url: String): Pair<String, String>? {
        val match = Regex("^https?://([^/]+)/(.*)$", RegexOption.IGNORE_CASE).find(url) ?: return null
        val host = match.groupValues[1]
        val queryString = match.groupValues[2]
        if (host.isBlank() || queryString.isBlank()) return null
        return host to queryString
    }

    private fun postLogin(
        host: String,
        queryString: String,
        userId: String,
        password: String,
        service: String,
        doubleEncode: Boolean,
        log: (String) -> Unit,
    ): LoginOutcome {
        val serviceValue =
            if (doubleEncode) URLEncoder.encode(service, "UTF-8") else service

        val form = FormBody.Builder()
            .add("userId", userId)
            .add("password", password)
            .add("service", serviceValue)
            .add("queryString", queryString)
            .add("validcode", "")
            .add("passwordEncrypt", "false")
            .build()

        val request = Request.Builder()
            .url("http://$host/eportal/InterFace.do?method=login")
            .header("User-Agent", AppSettings.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referrer-Policy", "strict-origin-when-cross-origin")
            .post(form)
            .build()

        return try {
            client().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                log("登录接口响应：HTTP ${response.code}")
                when {
                    text.isBlank() ->
                        LoginOutcome.Error("服务器返回空响应，可能仍处于被网关拦截状态")

                    else -> {
                        val json = runCatching { JSONObject(text) }.getOrNull()
                            ?: return@use LoginOutcome.Error("响应不是 JSON：${text.take(120)}")
                        when (json.optString("result")) {
                            "success" -> {
                                log("校园网连接成功～")
                                LoginOutcome.Success
                            }

                            "fail" -> LoginOutcome.Fail(
                                json.optString("message").ifBlank { "未知错误（result=fail）" }
                            )

                            else -> LoginOutcome.Error("登录响应异常：${text.take(160)}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LoginOutcome.Error("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * 依次尝试的探测地址。
         * 主地址 123.123.123.123 在部分网络被屏蔽，此时用连通性检测地址兜底触发拦截页。
         */
        private val PROBE_URLS = listOf(
            AppSettings.PORTAL_DETECT_URL,
            AppSettings.DEFAULT_CHECK_URL,
        )
    }
}
