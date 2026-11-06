package com.zhu.yzunet

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.zhu.yzunet.service.AutoLoginService

/**
 * 无界面的一次性登录入口，专供自动化调用。
 *
 * 它不渲染任何 UI（透明主题），被启动后立刻把登录任务交给前台服务，然后自己结束，
 * 不会打断用户当前正在做的事情。登录结果通过通知栏反馈。
 *
 * 触发方式（任选其一）：
 *   adb shell am start -n com.zhu.yzunet/.QuickLoginActivity
 *   adb shell am start -a com.zhu.yzunet.action.QUICK_LOGIN
 *   adb shell am start -n com.zhu.yzunet/.QuickLoginActivity --ez force true   # 跳过检测强制认证
 *
 * 默认行为：先检测连通性，已在线则直接返回，不做多余认证。
 */
class QuickLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val force = intent?.getBooleanExtra(AutoLoginService.EXTRA_FORCE, false) ?: false

        val started = AutoLoginService.loginOnce(this, force)

        setResult(if (started) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    companion object {
        /** 与 AndroidManifest 里 intent-filter 的 action 保持一致 */
        const val ACTION_QUICK_LOGIN = "com.zhu.yzunet.action.QUICK_LOGIN"
    }
}
