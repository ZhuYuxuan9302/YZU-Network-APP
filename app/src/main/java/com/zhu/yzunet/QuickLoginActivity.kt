package com.zhu.yzunet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zhu.yzunet.service.AutoLoginService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        // 全程不可见，也绝不拦截触摸/按键，保证不干扰用户当前在用的 App
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        val force = intent?.getBooleanExtra(AutoLoginService.EXTRA_FORCE, false) ?: false

        val started = AutoLoginService.loginOnce(this, force)
        setResult(if (started) RESULT_OK else RESULT_CANCELED)

        // 不要立刻 finish()：
        // Android 12+ 只有当进程还处于前台时，才允许把服务提升为前台服务。
        // 立刻退出会让进程瞬间退回后台，服务里的 startForeground() 会被拒绝，
        // 表现就是「点了快捷方式什么也没发生」。这里多停留几百毫秒等它完成提升。
        lifecycleScope.launch {
            delay(FINISH_DELAY_MS)
            finish()
        }
    }

    companion object {
        /** 与 AndroidManifest 里 intent-filter 的 action 保持一致 */
        const val ACTION_QUICK_LOGIN = "com.zhu.yzunet.action.QUICK_LOGIN"

        private const val FINISH_DELAY_MS = 600L
    }
}
