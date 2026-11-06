package com.zhu.yzunet.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.zhu.yzunet.QuickLoginActivity
import com.zhu.yzunet.R

enum class PinResult {
    /** 已向桌面发起固定请求，等系统弹窗确认 */
    Requested,

    /** 桌面上已经存在这个快捷方式 */
    AlreadyPinned,

    /** 当前桌面/系统不支持由应用直接固定快捷方式 */
    Unsupported,

    /** 调用出错 */
    Failed,
}

/**
 * 把「一键登录」钉到桌面。
 *
 * Android 8.0+ 走 ShortcutManagerCompat.requestPinShortcut（系统会弹确认框）；
 * Android 7.x 走已废弃的 INSTALL_SHORTCUT 广播兜底。
 */
object ShortcutHelper {

    private const val SHORTCUT_ID = "quick_login"
    private const val SHORT_LABEL = "一键登录"
    private const val LONG_LABEL = "校园网一键登录"

    fun requestPin(context: Context): PinResult {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pinModern(app)
        } else {
            pinLegacy(app)
        }
    }

    /** 目标 Intent：直接打开无界面的 QuickLoginActivity */
    private fun targetIntent(context: Context): Intent =
        Intent(context, QuickLoginActivity::class.java)
            .setAction(QuickLoginActivity.ACTION_QUICK_LOGIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun pinModern(context: Context): PinResult {
        val supported = runCatching {
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        }.getOrDefault(false)
        if (!supported) return PinResult.Unsupported

        val alreadyPinned = runCatching {
            ShortcutManagerCompat
                .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .any { it.id == SHORTCUT_ID }
        }.getOrDefault(false)
        if (alreadyPinned) return PinResult.AlreadyPinned

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(SHORT_LABEL)
            .setLongLabel(LONG_LABEL)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut))
            .setIntent(targetIntent(context))
            .build()

        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            PinResult.Requested
        }.getOrDefault(PinResult.Failed)
    }

    @Suppress("DEPRECATION")
    private fun pinLegacy(context: Context): PinResult = runCatching {
        val install = Intent(LEGACY_INSTALL_ACTION).apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, targetIntent(context))
            putExtra(Intent.EXTRA_SHORTCUT_NAME, SHORT_LABEL)
            putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(context, R.mipmap.ic_shortcut),
            )
            // 部分桌面支持，避免重复创建
            putExtra("duplicate", false)
        }
        context.sendBroadcast(install)
        PinResult.Requested
    }.getOrDefault(PinResult.Failed)

    private const val LEGACY_INSTALL_ACTION = "com.android.launcher.action.INSTALL_SHORTCUT"
}
