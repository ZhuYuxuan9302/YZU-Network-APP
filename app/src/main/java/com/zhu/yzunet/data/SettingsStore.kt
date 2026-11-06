package com.zhu.yzunet.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES/GCM 密钥加密本地明文。
 * 密钥不出安全硬件/系统密钥库，不需要额外三方依赖。
 */
internal object SecureCodec {

    private const val KEY_ALIAS = "yzu_net_prefs_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(payload, Base64.NO_WRAP)
    }.getOrElse { plain }

    fun decrypt(stored: String): String = runCatching {
        if (!stored.contains(':')) return@runCatching stored
        val (ivPart, dataPart) = stored.split(':', limit = 2)
        val iv = Base64.decode(ivPart, Base64.NO_WRAP)
        val data = Base64.decode(dataPart, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrElse { "" }
}

/**
 * 配置持久化。账号密码走 Keystore 加密，其余选项明文存储。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val storedUser = prefs.getString(KEY_USER_ID, "") ?: ""
        val storedPwd = prefs.getString(KEY_PASSWORD, "") ?: ""
        return AppSettings(
            userId = if (storedUser.isNotEmpty()) SecureCodec.decrypt(storedUser) else "",
            password = if (storedPwd.isNotEmpty()) SecureCodec.decrypt(storedPwd) else "",
            serviceIndex = prefs.getInt(KEY_SERVICE_INDEX, AppSettings.DEFAULT_SERVICE_INDEX)
                .coerceIn(1, AppSettings.SERVICES.size),
            checkUrl = prefs.getString(KEY_CHECK_URL, AppSettings.DEFAULT_CHECK_URL)
                ?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_CHECK_URL,
            autoLoginOnLaunch = prefs.getBoolean(KEY_AUTO_ON_LAUNCH, true),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, false),
            checkIntervalSec = prefs.getInt(KEY_INTERVAL, AppSettings.DEFAULT_CHECK_INTERVAL)
                .coerceIn(5, 300),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_USER_ID, SecureCodec.encrypt(settings.userId))
            .putString(KEY_PASSWORD, SecureCodec.encrypt(settings.password))
            .putInt(KEY_SERVICE_INDEX, settings.serviceIndex)
            .putString(KEY_CHECK_URL, settings.checkUrl)
            .putBoolean(KEY_AUTO_ON_LAUNCH, settings.autoLoginOnLaunch)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putInt(KEY_INTERVAL, settings.checkIntervalSec)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "yzu_net_settings"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SERVICE_INDEX = "service_index"
        private const val KEY_CHECK_URL = "check_url"
        private const val KEY_AUTO_ON_LAUNCH = "auto_on_launch"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_INTERVAL = "check_interval"
    }
}
