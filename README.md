# 扬大校园网一键登录（Android）

把原来的 Python 自动重连脚本，改写成原生 Android App：**打开应用点一下（或自动）完成校园网认证**，可选后台断线自动重连，账号密码加密保存在本机。

## 功能

| 功能 | 说明 |
| --- | --- |
| 一键登录 | 一个按钮跑完整套认证流程（探测网关 → 解析认证入口 → 提交认证） |
| 记住账号 | 首屏「账户与认证」卡片始终可见可改，改动即时保存；密码用 Android Keystore（AES-GCM）加密，可一键清除 |
| 打开即登录 | 启动后自动用保存的账号尝试认证，可在设置里关闭 |
| 断线自动重连 | 前台服务定时检测外网（默认 15s），掉线自动重登，通知栏常驻可随时停止 |
| 连通性检测 | 探测 `generate_204`，返回 204 视为在线；其余状态一律按断线处理 |
| 实时日志 | 界面内滚动显示每一步过程与服务器响应，排障用 |
| 桌面快捷方式 | 应用内一键把「一键登录」钉到桌面，点图标直接在后台完成认证，不经过主界面 |

对应关系（Python → App）：

```
YZU_USER_ID / YZU_PASSWORD  →  界面里的学工号 / 密码
YZU_SERVICE_INDEX (1-5)     →  服务类型下拉框
YZU_CHECK_URL               →  「连通性检测地址」输入框
CHECK_INTERVAL / POST_LOGIN_GRACE / CHECK_TIMEOUT  →  代码里的同义常量
主循环                       →  AutoLoginService（前台服务）
```

## 工程结构

```
app/src/main/java/com/zhu/yzunet/
├── MainActivity.kt              # 入口，处理通知权限
├── QuickLoginActivity.kt        # 无界面自动化入口，供脚本/快捷指令调用
├── data/
│   ├── AppSettings.kt           # 配置模型 + 服务列表 + 常量
│   ├── SettingsStore.kt         # Keystore 加密 + SharedPreferences 持久化
│   ├── CampusNetClient.kt       # 整个认证协议（OkHttp）
│   └── NetworkUtils.kt          # 判断当前是否 Wi-Fi
├── service/
│   └── AutoLoginService.kt      # 前台服务：定时检测 + 断线重登
└── ui/
    ├── HomeViewModel.kt         # 状态、动作、日志
    ├── HomeScreen.kt            # Compose 界面
    └── theme/Theme.kt           # 亮/暗色主题
```

技术栈：Kotlin 2.0 + Jetpack Compose (Material 3) + OkHttp，minSdk 24（Android 7.0），targetSdk 35。

## 怎么构建出 APK

### 方式一：Android Studio（推荐）

1. 下载安装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17 与 Android SDK，不用另外装）。
2. `File → Open`，选择本仓库根目录 `YZU-Network-APP`，等 Gradle 同步完成。
3. 手机开「开发者选项 → USB 调试」并连上电脑，点右上角 ▶ Run 直接装到手机。
4. 想要安装包：`Build → Build Bundle(s)/APK(s) → Build APK(s)`，产物在
   `app/build/outputs/apk/debug/app-debug.apk`，拷到手机安装即可。

> 首次同步会让填写 SDK 路径（自动生成 `local.properties`），如果缺失，手动创建并写入：
> `sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk`

### 方式二：命令行

需要 JDK 17（不是 25）与 Android SDK：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

### 方式三：GitHub Actions（本机没有环境也能出包）

仓库已带 `.github/workflows/build.yml`。推到 GitHub 后进入 `Actions → Build APK → Run workflow`，
跑完在 Artifacts 里下载 `yzu-net-debug-apk` 即可。

## 使用

1. 手机连上校园 Wi-Fi（`YZU-WLAN` 之类）。系统提示「此网络无法访问互联网」时选**保持连接**。
2. 打开 App，填学工号和密码，选对运营商服务（不确定就先试「学校互联网服务」）。
   这些内容随时可以改：账户卡片固定在首屏，右上角会显示「已保存 / 未填写」，改动即时落盘。
3. 点「一键登录校园网」。看到「认证成功 + 外网可达」就好了。
4. 想更快的话，点主按钮下方的「**把「一键登录」放到桌面**」，系统会弹窗确认，之后桌面多一个绿色闪电图标，
   以后点它就直接后台登录，不用再进 App。
5. 需要长期挂着：打开「断线自动重连」，按提示允许通知权限。

## 屏幕自适应（平板 / 分屏 / 小窗）

布局按**当前窗口尺寸**分档，而不是按设备类型判断，所以分屏拖动分隔条、旋转屏幕、桌面模式缩放都会实时切换：

| 条件 | 布局 |
| --- | --- |
| 宽度 ≥ 720dp | **双栏**：左栏「状态 + 账户」，右栏「快捷方式 + 自动化 + 日志」 |
| 宽度 ≥ 1180dp | 双栏内容居中并限宽，避免超宽平板上文字拉成一长条 |
| 高度 < 420dp | **紧凑模式**：隐藏底部登录栏，按钮改为内联在状态卡下方；状态卡瘦身、日志区降低高度 |
| 手机竖屏 | 单列 + 底部常驻登录栏 |

其他适配细节：

- 旋转 / 缩放时状态存在 `ViewModel` 里，输入框内容、开关状态不会丢；`launched` 标志保证不会因重建而重复触发自动登录。
- 按钮用 `heightIn(min = 52.dp)` 而非固定高度，系统字体放大时不会被裁切。
- 长文本（服务名、状态详情）统一 `maxLines` + 省略号，窄窗口下不会撑破卡片。
- 两个 Activity 都声明了 `resizeableActivity="true"`，不会被系统强制信箱化（letterbox）。

## 自动化调用（一键触发登录）

应用里有一个**无界面**的 `QuickLoginActivity`，专供 Tasker / MacroDroid / adb / 快捷指令调用。
它不会弹出任何界面，把登录交给前台服务后立即结束，结果用通知栏告诉你。

```bash
# 方式一：按组件名启动（推荐）
adb shell am start -n com.zhu.yzunet/.QuickLoginActivity

# 方式二：按 action 启动（不用记类名）
adb shell am start -a com.zhu.yzunet.action.QUICK_LOGIN

# 跳过连通性检测，无条件重新认证
adb shell am start -n com.zhu.yzunet/.QuickLoginActivity --ez force true
```

默认行为：**先检测外网，已在线就直接返回**，避免无谓地反复认证；只有断线时才真正提交认证。

| 场景 | 配置 |
| --- | --- |
| 桌面图标一点即登录 | 应用内点「把「一键登录」放到桌面」即可，无需自动化工具 |
| Tasker | `Send Intent` → Action: `com.zhu.yzunet.action.QUICK_LOGIN`，Target: `Activity` |
| MacroDroid | `发送意图` → Action 同上，类型选 Activity |
| 桌面快捷方式 | 用 "Activity Shortcut" 类工具指向 `.QuickLoginActivity` |
| 开机/连上 Wi-Fi 自动跑 | 自动化 App 里以「Wi-Fi 已连接」为触发条件调上面任意一条 |

桌面快捷方式实现说明：`ShortcutHelper` 在 Android 8.0+ 走 `ShortcutManagerCompat.requestPinShortcut`
（系统弹确认框后由桌面固定），Android 7.x 回落到 `INSTALL_SHORTCUT` 广播；快捷方式的 Intent
直接指向 `QuickLoginActivity`，与自动化调用走同一条链路。

结果反馈：

- 通知栏会显示「自动登录成功 / 失败 / 异常 / 已在线」以及具体原因；
- 想拿机器可读的结果，让自动化工具监听这条通知即可；
- `adb logcat -s yzu` 里也会打到同样的过程日志。

> 注意：Android 10+ 限制后台应用启动 Activity。用 adb 或在前台状态的自动化 App 触发没问题；
> 如果你的自动化工具是纯后台服务，请给它开启「后台弹出界面 / 显示在其他应用上层」权限。

## 认证协议说明（与脚本一致）

```
GET  http://123.123.123.123                     → 302 到网关拦截页，页面里含 href='...'
     ↑ 用正则 href='([^']+)' 抠出实时有效的认证入口
解析 http://{host}/{queryString}                 → host 与 queryString（含 eportal 路径）
POST http://{host}/eportal/InterFace.do?method=login
     userId / password / service / queryString / validcode= / passwordEncrypt=false
     ↑ service 首次按脚本逻辑做双重编码；若网关报参数类错误，自动用单次编码重试一次
判断 result == success / fail(message)
```

## 常见问题

**登录返回「服务器返回空响应」或「响应不是 JSON」**
说明请求根本没到认证服务：多半是没连上校园 Wi-Fi，或者网关把请求拦在认证页。先确认 Wi-Fi 连着、再点一次。

**提示 result=fail，message 是密码错误/账号被锁**
App 不会去猜，按提示去 [校园网自助服务] 改密码或等锁定解除。连续失败时别狂点，避免触发风控。

**后台自动重连被系统杀掉**
去手机设置里给本应用开「自启动 / 后台运行 / 忽略电池优化」（小米、华为、OPPO、vivo 尤其需要），
并把电池策略设为「无限制」。前台服务本身会常驻通知栏，被杀一般是厂商省电策略导致。

**Android 7 及以上的证书问题**
全流程都是 HTTP 明文 + 校园网关自签页面，已在 `network_security_config.xml` 里放行明文流量，无需额外装证书。

**只想手动用，不想后台常驻**
关掉「断线自动重连」即可，不开启就不会有前台服务和通知。

## 隐私

- 账号密码只存在本机 `SharedPreferences`，值用 Android Keystore 中的 AES-256-GCM 密钥加密。
- 应用没有网络上传逻辑，除了校园网网关与连通性检测地址，不会请求任何第三方。
- `allowBackup=false`，不会随云备份外泄。
