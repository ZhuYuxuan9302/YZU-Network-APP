using System;
using System.Threading;
using System.Windows;
using YzuNet.Windows.Services;

namespace YzuNet.Windows;

public partial class App : Application
{
    private static Mutex? _mutex;
    private System.Windows.Forms.NotifyIcon? _tray;
    private MainWindow? _mainWindow;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _mutex = new Mutex(true, @"Local\YzuNetWindows", out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show("扬大校园网已经在运行了，请从托盘图标打开。", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        // 跟随系统深浅色
        ThemeManager.Apply(ThemeManager.SystemPrefersDark());

        // 兜底：任何未处理异常只弹提示，不让程序直接消失
        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(args.Exception.Message, "出现未处理的异常",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        _mainWindow = new MainWindow();
        MainWindow = _mainWindow;
        _mainWindow.ExitRequested += RequestExit;
        _mainWindow.BalloonRequested += ShowBalloon;

        CreateTrayIcon();

        var startHidden = Array.Exists(
            e.Args,
            arg => string.Equals(arg, "--tray", StringComparison.OrdinalIgnoreCase));

        if (!startHidden) _mainWindow.Show();
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

    private void ShowMainWindow() => _mainWindow?.ShowFromTray();

    private void QuickConnect()
    {
        var window = _mainWindow;
        if (window is null) return;

        window.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try
            {
                await window.ConnectNowAsync();
            }
            catch
            {
                // 已经在窗口里记录日志了
            }
        }));
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
        _mainWindow?.PrepareExit();
        Shutdown();
    }
}
