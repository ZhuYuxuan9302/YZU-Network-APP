# 扬大校园网一键连接（Windows 版）

Android 版的桌面孪生版本：同样的认证协议、同样的校名校验、同样的「先探测后认证」逻辑，做成了 WPF 桌面程序。

## 功能

| 功能 | 说明 |
| --- | --- |
| 一键连接 | 先探测外网，已在线直接跳过；离线才出认证流程 |
| 记住账号 | 账号密码存 `%APPDATA%\YzuNet\settings.json`，密码用 **Windows DPAPI** 加密（只有当前 Windows 账户能解开） |
| 校名校验 | 无线只认 `YZU-WLAN`（含 `YZU-WLAN-5G` 这类衍生名），连错 Wi-Fi 直接提示并中止；**有线（以太网）没有 SSID，跳过校名校验直接认证** |
| 断线自动重连 | 后台线程定时检测，掉线自动重登，成功后等 30s 让网络就绪 |
| 开机自启 | 写入 `HKCU\...\CurrentVersion\Run`，启动后**静默最小化到托盘** |
| 托盘常驻 | 关闭窗口 = 最小化到托盘；托盘菜单：打开主界面 / 立即连接 / 退出 |
| 深色模式 | 首次启动跟随系统「应用模式」，也可用右下角按钮手动切换 |
| 一键重置 | 清除账号、服务、自动化开关、检测地址与日志，恢复出厂状态 |

## 怎么拿到 exe

仓库已带 `.github/workflows/build-windows.yml`，推到 GitHub 后：

`Actions → Build Windows App → Run workflow`，跑完在 Artifacts 里下载 `yzu-net-windows`（单个 `.exe`）。

产物是 **self-contained 单文件**，目标机器不需要装 .NET 运行时。

### 本地构建

需要 [.NET 8 SDK](https://dotnet.microsoft.com/download)：

```powershell
cd windows/YzuNet.Windows
dotnet publish -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish
```

不打包直接跑：`dotnet run --project windows/YzuNet.Windows`

## 使用

1. 连上校园 Wi-Fi（`YZU-WLAN`），系统若提示「无法访问 Internet」照常连接即可。
2. 打开 exe，填学工号和密码，选对运营商服务。
3. 点「一键连接校园网」。看到绿色「外网已连接」就成了。
4. 想长期挂着：勾选「断线自动重连」；想让开机就可用：勾选「开机自动启动」。

## 与安卓版的差异

| 点 | Android | Windows |
| --- | --- | --- |
| 密码存储 | Android Keystore (AES-GCM) | Windows DPAPI（当前用户作用域） |
| 读 SSID | `WifiInfo` + 定位权限 | `netsh wlan show interfaces` 解析（无需授权） |
| 有线连接 | `TRANSPORT_ETHERNET` | 网卡类型 `Ethernet`（已过滤 VM/Hyper-V 等虚拟网卡） |
| 请求绑定网卡 | `Network.socketFactory` | 不需要（桌面通常只有一条网络） |
| 常驻方式 | 前台服务 + 通知栏 | 托盘图标 + 后台线程 |
| 自动化 | `am start` / 快捷方式 | 托盘菜单 / 命令行参数 `--tray` |

> **读 SSID 的说明**：Windows 没有免授权的 SSID API，这里解析 `netsh` 输出。
> 如果系统关闭了 WLAN AutoConfig 服务或权限受限，可能读不到网络名，此时程序会提示「读不到网络名」而不是误判成"已认证"。

## 目录结构

```
windows/YzuNet.Windows/
├── App.xaml(.cs)                 # 启动、单实例唤醒、托盘图标；浅色基础配色与全部样式内联在这里
├── MainWindow.xaml(.cs)          # 主界面与全部交互
├── Assets/app.ico                # 程序图标（由 tools/gen_windows_icon.ps1 生成）
└── Services/
    ├── AppSettings.cs            # 配置模型
    ├── SettingsStore.cs          # JSON + DPAPI 持久化
    ├── CampusNetClient.cs        # 认证协议（HttpClient）
    ├── NetworkMonitor.cs         # Wi-Fi / SSID 读取
    ├── AutoReconnectService.cs   # 断线自动重连循环
    ├── StartupManager.cs         # 开机自启（注册表）
    └── ThemeManager.cs           # 深色主题（代码构造的画刷覆盖层）+ 深浅色检测
```

> **资源为什么全放在 `App.xaml` 里**：运行期从外部 xaml 文件加载 `ResourceDictionary`
> 依赖 `pack://` 解析，单文件发布下一旦解析失败，窗口创建会直接抛异常；现在配色与样式
> 编译进 BAML，深色主题只是代码构造的一组画刷叠加覆盖，基本不存在"找不到资源"的可能。

## 常见问题

**连接返回「服务器返回空响应」或「响应不是 JSON」**
请求没到认证服务：确认连着校园 Wi-Fi，网关没有把请求拦在别的页面。

**插网线能用吗？**
可以。有线连接没有 SSID 概念，程序会**跳过校名校验**直接认证，提示条显示「当前网络：有线连接 · <网卡名>」。
为避免误判，`NetworkMonitor` 已过滤 Hyper-V / VMware / WSL / VPN / Bluetooth 等虚拟网卡，只认真正的物理以太网卡。

**提示「当前 Wi-Fi 不是 YZU-WLAN」**
别的 Wi-Fi 上连通性检测同样会通，不校验 SSID 就会出现假成功，所以会直接中止。连回 `YZU-WLAN` 再点即可。

**托盘图标不见了**
Windows 11 会折叠隐藏图标，点任务栏的「^」把它拖出来固定即可。

**打开报「出现未处理的异常」，或者再次打开说"已经在运行"但托盘里没有图标**
这是旧版本的症状：窗口创建时抛异常被吞掉，进程还在、单实例锁还被占着，于是既没窗口也没托盘。
现在已经改成：
1. 配色与样式全部内联进 `App.xaml`（编译进 BAML），不再在运行期从外部 xaml 加载资源字典，`StaticResourceExtension` 类崩溃从根源上消失；
2. 主界面构造/初始化任何一步失败都不再影响窗口创建；真起不来就弹一次带完整异常链的提示并**干净退出、释放单实例锁**；
3. 完整的异常链（含 InnerException）写入 `%APPDATA%\YzuNet\crash.log`，托盘右键「打开配置与日志目录」可直接跳过去；
4. 再次双击图标会**把已运行的实例唤到前台**，而不是弹"已经在运行"。

万一还是遇到，请把 `crash.log` 里最新一段发出来（有 InnerException 才能定位真正原因）。

**想让开机自启但不弹窗**
自启写的是 `"<exe路径>" --tray`，启动即隐藏到托盘；不要把它从任务管理器「启动」里禁用。
