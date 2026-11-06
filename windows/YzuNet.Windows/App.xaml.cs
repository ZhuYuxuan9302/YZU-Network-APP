using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using YzuNet.Windows.Services;

namespace YzuNet.Windows;

public partial class App : Application
{
    private const string MutexName = @"Local\YzuNetWindows_SingleInstance";
    private const string ShowEventName = @"Local\YzuNetWindows_ShowWindow";

    private static Mutex? _mutex;
    private static int _fatalDialogShown;

    private EventWaitHandle? _showEvent;
    private System.Windows.Forms.NotifyIcon? _tray;
    private MainWindow? _mainWindow;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _mutex = new Mutex(true, MutexName, out var createdNew);
        if (!createdNew)
        {
            // 已经有一个实例在跑：让它把窗口弹到前台，然后自己安静退出
            if (SignalExistingInstance())
            {
                Shutdown();
                return;
            }

            MessageBox.Show(
                "扬大校园网已经在运行了，请从桌面右下角的托盘图标打开。\n\n" +
                "如果托盘里找不到图标，说明上一个进程没退干净：请在任务管理器里结束 YzuNet.exe 后重新打开。",
                "提示", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        RegisterGlobalExceptionHandlers();

        // 跟随系统深浅色。主题失败不影响启动，最多是配色不对。
        try
        {
            ThemeManager.Apply(ThemeManager.SystemPrefersDark());
        }
        catch (Exception ex)
        {
            LogCrash("主题加载失败", ex);
        }

        // 先建托盘：即使主界面出问题，用户也还有入口和退出方式
        try
        {
            CreateTrayIcon();
        }
        catch (Exception ex)
        {
            LogCrash("托盘图标创建失败", ex);
        }

        // 供"再次双击图标"时把已运行的实例唤到前台
        StartShowListener();

        if (!EnsureMainWindow())
        {
            // 主界面起不来就干净退出，绝不留下"进程在跑但没有窗口也没有托盘"的僵尸状态
            RequestExit();
            return;
        }

        var startHidden = Array.Exists(
            e.Args,
            arg => string.Equals(arg, "--tray", StringComparison.OrdinalIgnoreCase));

        if (!startHidden)
        {
            _mainWindow!.Show();
            _mainWindow.Activate();
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_tray is not null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }

        try
        {
            _showEvent?.Set(); // 叫醒监听线程，让它自己退出
            _showEvent?.Dispose();
        }
        catch
        {
            // ignore
        }

        _showEvent = null;

        try
        {
            _mutex?.ReleaseMutex();
        }
        catch
        {
            // ignore
        }

        _mutex?.Dispose();
        _mutex = null;

        base.OnExit(e);
    }

    // ==================== 主窗口 ====================

    private bool EnsureMainWindow()
    {
        if (_mainWindow is not null) return true;

        try
        {
            var window = new MainWindow();
            window.ExitRequested += RequestExit;
            window.BalloonRequested += ShowBalloon;

            _mainWindow = window;
            MainWindow = window;

            // --tray 隐藏启动时 Loaded 不会触发，这里主动跑一次初始化（内部幂等）
            window.Startup();
            return true;
        }
        catch (Exception ex)
        {
            LogCrash("主界面加载失败", ex);
            ShowFatalOnce("主界面加载失败，程序即将退出。\n详细信息已写入 %APPDATA%\\YzuNet\\crash.log", ex);
            return false;
        }
    }

    private void ShowMainWindow()
    {
        if (!EnsureMainWindow())
        {
            RequestExit();
            return;
        }

        _mainWindow!.ShowFromTray();
    }

    private void QuickConnect()
    {
        if (!EnsureMainWindow())
        {
            RequestExit();
            return;
        }

        var window = _mainWindow!;
        window.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try
            {
                await window.ConnectNowAsync();
            }
            catch (Exception ex)
            {
                LogCrash("托盘「立即连接」失败", ex);
            }
        }));
    }

    // ==================== 单实例唤醒 ====================

    private static bool SignalExistingInstance()
    {
        try
        {
            if (EventWaitHandle.TryOpenExisting(ShowEventName, out var handle))
            {
                using (handle)
                {
                    handle.Set();
                }

                return true;
            }
        }
        catch
        {
            // ignore
        }

        return false;
    }

    private void StartShowListener()
    {
        try
        {
            _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);

            // 后台线程，不阻止进程退出
            var listener = new Thread(ShowListenerLoop)
            {
                IsBackground = true,
                Name = "YzuNet-ShowListener"
            };
            listener.Start();
        }
        catch (Exception ex)
        {
            LogCrash("单实例唤醒通道创建失败", ex);
        }
    }

    private void ShowListenerLoop()
    {
        while (true)
        {
            try
            {
                var handle = _showEvent;
                if (handle is null) return;

                if (!handle.WaitOne()) continue;

                Dispatcher.BeginInvoke(new Action(ShowMainWindow));
            }
            catch
            {
                // 事件已释放（正在退出）或 Dispatcher 已关闭
                if (Dispatcher.HasShutdownStarted) return;
                Thread.Sleep(200);
            }
        }
    }

    // ==================== 托盘 ====================

    private void CreateTrayIcon()
    {
        _tray = new System.Windows.Forms.NotifyIcon
        {
            Text = "扬大校园网",
            Visible = true,
            Icon = LoadIcon()
        };

        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("打开主界面", null, (_, _) => ShowMainWindow());
        menu.Items.Add("立即连接", null, (_, _) => QuickConnect());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("打开配置与日志目录", null, (_, _) => OpenDataFolder());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => RequestExit());

        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowMainWindow();
    }

    private static System.Drawing.Icon LoadIcon()
    {
        try
        {
            var exePath = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(exePath))
            {
                var icon = System.Drawing.Icon.ExtractAssociatedIcon(exePath);
                if (icon is not null) return icon;
            }
        }
        catch
        {
            // ignore
        }

        return System.Drawing.SystemIcons.Application;
    }

    private void ShowBalloon(string title, string text)
    {
        try
        {
            _tray?.ShowBalloonTip(3000, title, text, System.Windows.Forms.ToolTipIcon.Info);
        }
        catch
        {
            // ignore
        }
    }

    private void RequestExit()
    {
        try
        {
            _mainWindow?.PrepareExit();
        }
        catch
        {
            // ignore
        }

        Shutdown();
    }

    private static void OpenDataFolder()
    {
        try
        {
            var directory = DataDirectory();
            Directory.CreateDirectory(directory);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = directory,
                UseShellExecute = true
            });
        }
        catch
        {
            // ignore
        }
    }

    // ==================== 异常诊断 ====================

    private void RegisterGlobalExceptionHandlers()
    {
        DispatcherUnhandledException += (_, args) =>
        {
            LogCrash("UI 线程未处理异常", args.Exception);

            // 启动阶段之外不再弹窗刷屏，只记录日志；不设 Handled 会让进程直接死掉，
            // 所以这里仍然吞掉，但下面 ShowFatalOnce 会保证用户至少看过一次原因。
            ShowFatalOnce("出现未处理的异常", args.Exception);
            args.Handled = true;
        };

        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            var exception = args.ExceptionObject as Exception
                            ?? new Exception(args.ExceptionObject?.ToString() ?? "未知的异常对象");
            LogCrash("非 UI 线程未处理异常", exception);
        };

        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            LogCrash("未观察到的任务异常", args.Exception);
            args.SetObserved();
        };
    }

    private static void ShowFatalOnce(string title, Exception exception)
    {
        if (Interlocked.Exchange(ref _fatalDialogShown, 1) == 1) return;
        ShowFatal(title, exception);
    }

    private static void ShowFatal(string title, Exception exception)
    {
        try
        {
            var detail = exception.ToString();
            if (detail.Length > 2000) detail = detail.Substring(0, 2000) + Environment.NewLine + "…";

            MessageBox.Show(detail, title, MessageBoxButton.OK, MessageBoxImage.Error);
        }
        catch
        {
            // 连弹窗都失败就算了，日志里有
        }
    }

    private static string DataDirectory()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "YzuNet");
        Directory.CreateDirectory(directory);
        return directory;
    }

    private static void LogCrash(string title, Exception exception)
    {
        try
        {
            var builder = new StringBuilder();
            builder.Append('[').Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")).Append("] ");
            builder.AppendLine(title);
            builder.AppendLine(exception.ToString());
            builder.AppendLine(new string('-', 60));

            File.AppendAllText(
                Path.Combine(DataDirectory(), "crash.log"),
                builder.ToString(),
                Encoding.UTF8);
        }
        catch
        {
            // 记录失败就算了
        }
    }
}
