using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using YzuNet.Windows.Services;

namespace YzuNet.Windows;

public enum StatusKind
{
    Idle,
    Checking,
    Online,
    Success,
    Failed
}

public partial class MainWindow : Window
{
    private static readonly int[] Intervals = { 10, 15, 30, 60 };

    private readonly SettingsStore _store = new();
    private readonly CampusNetClient _client = new();
    private readonly AutoReconnectService _reconnect = new();

    private AppSettings _settings = new();
    private bool _loading = true;
    private bool _busy;
    private bool _reallyExit;
    private bool _started;

    /// <summary>托盘菜单请求退出</summary>
    public event Action? ExitRequested;

    /// <summary>托盘气泡提示（标题, 内容）</summary>
    public event Action<string, string>? BalloonRequested;

    public MainWindow()
    {
        InitializeComponent();

        // 初始化任何一步失败都不能让窗口创建失败：否则进程活着但没有窗口也没有托盘，
        // 用户再次双击只会看到"已经在运行"，只能去任务管理器杀进程。
        try
        {
            _settings = _store.Load();
            PopulateStaticItems();
            LoadSettingsIntoUi();

            _reconnect.StatusChanged += OnReconnectStatusChanged;

            UpdateThemeButton();
            UpdateWifiHint();
            UpdateSavedChip();
            SetStatus("待检测", "尚未检测", StatusKind.Idle);
        }
        catch (Exception e)
        {
            TryLog($"界面初始化异常：{e}");
        }

        TryLog("扬大校园网 · 一键连接（Windows 版）");
        TryLog("关闭窗口会最小化到托盘，右键托盘图标可退出");
        if (_settings.HasCredentials) TryLog($"已载入账号 {Mask(_settings.UserId)}");

        Loaded += (_, _) => Startup();
    }

    // ==================== 初始化 ====================

    private void PopulateStaticItems()
    {
        ServiceCombo.ItemsSource = CampusNetwork.Services;

        var labels = new List<string>();
        foreach (var seconds in Intervals) labels.Add($"{seconds} 秒");
        IntervalCombo.ItemsSource = labels;
    }

    private void LoadSettingsIntoUi()
    {
        _loading = true;
        try
        {
            UserIdBox.Text = _settings.UserId;
            PasswordBox1.Password = _settings.Password;
            ServiceCombo.SelectedIndex = Math.Clamp(_settings.ServiceIndex - 1, 0, CampusNetwork.Services.Length - 1);

            AutoConnectCheck.IsChecked = _settings.AutoConnectOnStart;
            AutoReconnectCheck.IsChecked = _settings.AutoReconnect;
            AutoStartCheck.IsChecked = StartupManager.IsEnabled();

            var index = Array.IndexOf(Intervals, _settings.CheckIntervalSec);
            IntervalCombo.SelectedIndex = index >= 0 ? index : 1;

            CheckUrlBox.Text = _settings.CheckUrl;
            HeroServiceText.Text = $"服务：{_settings.ServiceName}";
        }
        finally
        {
            _loading = false;
        }
    }

    /// <summary>
    /// 启动时的一次性初始化。由 App 在创建窗口后直接调用（--tray 隐藏启动时 Loaded 不会触发），
    /// 窗口显示时也会再调一次，内部有幂等保护。
    /// </summary>
    public void Startup()
    {
        if (_started) return;
        _started = true;

        try
        {
            RefreshWifiState();
        }
        catch (Exception e)
        {
            TryLog($"网络状态检测异常：{e.Message}");
        }

        try
        {
            if (_settings.AutoReconnect)
            {
                _reconnect.Start();
                TryLog("已恢复后台自动重连");
                AnnounceReconnectStarted();
            }

            if (_settings.AutoConnectOnStart && _settings.HasCredentials)
            {
                _ = ConnectAsync(false);
            }
        }
        catch (Exception e)
        {
            TryLog($"启动初始化异常：{e.Message}");
        }
    }

    // ==================== 对外接口（托盘用） ====================

    public void PrepareExit()
    {
        _reallyExit = true;
        _reconnect.Stop();
    }

    public void ShowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    public Task ConnectNowAsync() => ConnectAsync(false);

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_reallyExit)
        {
            e.Cancel = true;
            Hide();
            return;
        }

        base.OnClosing(e);
    }

    // ==================== 连接流程 ====================

    private async Task ConnectAsync(bool force)
    {
        if (_busy) return;

        var settings = SnapshotSettings();
        if (!settings.HasCredentials)
        {
            Log("请先填写学工号和密码");
            return;
        }

        if (!settings.CheckUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !settings.CheckUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            Log("连通性检测地址必须以 http:// 或 https:// 开头");
            return;
        }

        RefreshWifiState();

        var connection = NetworkMonitor.GetConnection();
        if (!connection.IsConnected)
        {
            SetStatus("未连接网络", $"请连上 {CampusNetwork.CampusSsid} 或插上网线", StatusKind.Failed);
            Log("当前没有可用的网络连接（无线 / 有线都没有），已中止认证");
            return;
        }

        if (connection.Kind == ConnectionKind.Wifi)
        {
            if (connection.Ssid is not null && !NetworkMonitor.IsCampusSsid(connection.Ssid))
            {
                SetStatus("不在校园网",
                    $"当前 Wi-Fi「{connection.Ssid}」不是 {CampusNetwork.CampusSsid}，未执行认证",
                    StatusKind.Failed);
                Log($"当前 Wi-Fi 是「{connection.Ssid}」，不是 {CampusNetwork.CampusSsid}，已中止认证");
                return;
            }
        }
        else
        {
            // 有线连接没有 SSID 概念，校名校验不适用，直接进入认证
            Log($"当前是{connection.DisplayName}，跳过校名校验");
        }

        _busy = true;
        ConnectButton.IsEnabled = false;

        try
        {
            if (!force)
            {
                SetStatus("正在检测网络…", "先探测外网连通性", StatusKind.Checking);
                Log("先检测外网连通性…");

                var check = await _client.CheckOnlineAsync(settings.CheckUrl, CancellationToken.None);
                Log($"连通性：{(check.Online ? "在线" : "离线")}（{check.Detail}）");

                if (check.Online)
                {
                    SetStatus("外网已连接", $"外网已可用（{check.Detail}），无需登录", StatusKind.Online);
                    Log("外网已可用，跳过认证（如需切换服务请点「强制认证」）");
                    return;
                }

                Log("检测到未连接外网，开始认证…");
            }
            else
            {
                Log("已跳过连通性检测，强制重新认证…");
            }

            SetStatus("正在认证…", "提交认证请求", StatusKind.Checking);
            Log($"提交认证 —— 服务：{settings.ServiceName}");

            var outcome = await _client.LoginAsync(
                settings.UserId,
                settings.Password,
                settings.ServiceIndex,
                Log,
                CancellationToken.None);

            if (outcome.Status == LoginStatus.Success)
            {
                SetStatus("认证成功", "正在复检连通性…", StatusKind.Success);
                await Task.Delay(1200);

                var recheck = await _client.CheckOnlineAsync(settings.CheckUrl, CancellationToken.None);
                Log($"连通性复检：{(recheck.Online ? "在线" : "离线")}（{recheck.Detail}）");

                if (recheck.Online)
                    SetStatus("外网已连接", $"已完成认证，外网可达（{recheck.Detail}）", StatusKind.Online);
                else
                    SetStatus("认证成功", $"认证成功，外网尚未就绪（{recheck.Detail}）", StatusKind.Success);
            }
            else
            {
                SetStatus("认证失败", outcome.Message, StatusKind.Failed);
                Log($"认证失败：{outcome.Message}");
            }
        }
        catch (Exception e)
        {
            SetStatus("出现异常", e.Message, StatusKind.Failed);
            Log($"出现异常：{e}");
        }
        finally
        {
            _busy = false;
            ConnectButton.IsEnabled = true;
        }
    }

    private AppSettings SnapshotSettings()
    {
        // 以控件当前值为准，避免刚输入还没触发保存时的偏差
        var snapshot = new AppSettings
        {
            UserId = UserIdBox.Text.Trim(),
            Password = PasswordBox1.Password,
            ServiceIndex = Math.Clamp(ServiceCombo.SelectedIndex + 1, 1, CampusNetwork.Services.Length),
            CheckUrl = CheckUrlBox.Text.Trim(),
            AutoConnectOnStart = AutoConnectCheck.IsChecked == true,
            AutoReconnect = AutoReconnectCheck.IsChecked == true,
            CheckIntervalSec = SelectedInterval()
        };

        if (string.IsNullOrWhiteSpace(snapshot.CheckUrl))
            snapshot.CheckUrl = CampusNetwork.DefaultCheckUrl;

        return snapshot;
    }

    private int SelectedInterval()
    {
        var index = IntervalCombo.SelectedIndex;
        return index >= 0 && index < Intervals.Length ? Intervals[index] : 15;
    }

    // ==================== 状态与提示 ====================

    private void SetStatus(string title, string detail, StatusKind kind)
    {
        HeroTitleText.Text = title;
        HeroDetailText.Text = detail;

        Color start;
        Color end;

        switch (kind)
        {
            case StatusKind.Online:
            case StatusKind.Success:
                start = Color.FromRgb(0x34, 0xD3, 0x99);
                end = Color.FromRgb(0x05, 0x96, 0x69);
                break;

            case StatusKind.Failed:
                start = Color.FromRgb(0xF8, 0x71, 0x71);
                end = Color.FromRgb(0xDC, 0x26, 0x26);
                break;

            default:
                start = Color.FromRgb(0x60, 0xA5, 0xFA);
                end = Color.FromRgb(0x25, 0x63, 0xEB);
                break;
        }

        HeroBorder.Background = new LinearGradientBrush(start, end, new Point(0, 0), new Point(1, 1));
    }

    private void RefreshWifiState()
    {
        UpdateWifiHint();

        if (!string.IsNullOrEmpty(_settings.UserId))
            HeroServiceText.Text = $"服务：{_settings.ServiceName}";
    }

    private void UpdateWifiHint()
    {
        var connection = NetworkMonitor.GetConnection();

        switch (connection.Kind)
        {
            case ConnectionKind.None:
                SetHint($"未连接网络，请连上 {CampusNetwork.CampusSsid} 或插上网线",
                    "HintWarnBackBrush", "WarnBrush");
                return;

            case ConnectionKind.Ethernet:
                SetHint($"当前网络：{connection.DisplayName}（校名校验仅适用于无线）",
                    "HintSuccessBackBrush", "SuccessBrush");
                return;
        }

        // 以下为 Wi-Fi
        if (string.IsNullOrEmpty(connection.Ssid))
        {
            SetHint("已连上 Wi-Fi，但读不到网络名（可稍后点「刷新」重试）", "HintWarnBackBrush", "WarnBrush");
            return;
        }

        if (!NetworkMonitor.IsCampusSsid(connection.Ssid))
        {
            SetHint($"当前 Wi-Fi「{connection.Ssid}」不是 {CampusNetwork.CampusSsid}，不会执行认证",
                "HintDangerBackBrush", "DangerBrush");
            return;
        }

        SetHint($"当前 Wi-Fi：{connection.Ssid}", "HintSuccessBackBrush", "SuccessBrush");
    }

    private void SetHint(string text, string backgroundKey, string foregroundKey)
    {
        WifiHintText.Text = text;
        WifiHintBorder.Background = ResourceBrush(backgroundKey);
        WifiHintText.Foreground = ResourceBrush(foregroundKey);
    }

    private void UpdateSavedChip()
    {
        var saved = _settings.HasCredentials;
        SavedChipText.Text = saved ? "已保存" : "未填写";
        SavedChipText.Foreground = ResourceBrush(saved ? "SuccessBrush" : "SubtleTextBrush");
        SavedChipBorder.Background = ResourceBrush("ChipBackBrush");
        ClearButton.Visibility = saved ? Visibility.Visible : Visibility.Collapsed;
        ForceButton.Visibility = saved ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>
    /// 取主题画刷。FindResource 在资源缺失时会抛 ResourceReferenceKeyNotFoundException，
    /// 而本方法会在窗口构造期间被调用——一旦抛出就会出现"窗口建不出来、进程还在"的僵尸状态。
    /// 所以统一用 TryFindResource + 硬编码兜底色。
    /// </summary>
    private Brush ResourceBrush(string key)
    {
        try
        {
            if (TryFindResource(key) is Brush brush) return brush;
        }
        catch
        {
            // ignore
        }

        var fallback = new SolidColorBrush(FallbackColor(key));
        fallback.Freeze();
        return fallback;
    }

    private static Color FallbackColor(string key)
    {
        var dark = ThemeManager.IsDark;

        switch (key)
        {
            case "HintSuccessBackBrush":
                return dark ? Color.FromRgb(0x12, 0x30, 0x1F) : Color.FromRgb(0xE9, 0xF8, 0xEF);
            case "HintWarnBackBrush":
                return dark ? Color.FromRgb(0x32, 0x26, 0x12) : Color.FromRgb(0xFF, 0xF5, 0xE3);
            case "HintDangerBackBrush":
                return dark ? Color.FromRgb(0x34, 0x18, 0x1A) : Color.FromRgb(0xFD, 0xEC, 0xEC);
            case "ChipBackBrush":
                return dark ? Color.FromRgb(0x23, 0x2B, 0x36) : Color.FromRgb(0xEA, 0xEF, 0xF7);
            case "SuccessBrush":
                return dark ? Color.FromRgb(0x4A, 0xDE, 0x80) : Color.FromRgb(0x16, 0xA3, 0x4A);
            case "WarnBrush":
                return dark ? Color.FromRgb(0xFB, 0xBF, 0x24) : Color.FromRgb(0xB4, 0x53, 0x09);
            case "DangerBrush":
                return dark ? Color.FromRgb(0xF8, 0x71, 0x71) : Color.FromRgb(0xDC, 0x26, 0x26);
            case "SubtleTextBrush":
                return dark ? Color.FromRgb(0xA7, 0xB0, 0xBE) : Color.FromRgb(0x5A, 0x64, 0x72);
            case "TextBrush":
                return dark ? Color.FromRgb(0xE3, 0xE6, 0xEC) : Color.FromRgb(0x14, 0x1A, 0x22);
            case "CardBackgroundBrush":
                return dark ? Color.FromRgb(0x16, 0x1C, 0x25) : Colors.White;
            default:
                return Colors.Transparent;
        }
    }

    private void UpdateThemeButton()
    {
        ThemeButton.Content = ThemeManager.IsDark ? "浅色" : "深色";
    }

    // ==================== 日志 ====================

    private void Log(string message)
    {
        if (!Dispatcher.CheckAccess())
        {
            if (Dispatcher.HasShutdownStarted) return;

            try
            {
                Dispatcher.BeginInvoke(new Action(() => Log(message)));
            }
            catch
            {
                // 退出过程中 Dispatcher 可能已经关停
            }

            return;
        }

        LogList.Items.Add($"[{DateTime.Now:HH:mm:ss}] {message}");
        while (LogList.Items.Count > 300) LogList.Items.RemoveAt(0);

        try
        {
            if (LogList.Items.Count > 0)
                LogList.ScrollIntoView(LogList.Items[LogList.Items.Count - 1]);
        }
        catch
        {
            // 窗口还没完成布局时滚动可能失败，忽略即可
        }
    }

    /// <summary>写日志且绝不抛异常：构造阶段日志面板也未必完全可用</summary>
    private void TryLog(string message)
    {
        try
        {
            Log(message);
        }
        catch
        {
            // ignore
        }
    }

    private static string Mask(string value) =>
        value.Length > 6 ? $"{value.Substring(0, 3)}***{value.Substring(value.Length - 3)}" : "***";

    // ==================== 自动重连回调 ====================

    private void OnReconnectStatusChanged(string title, string detail, bool notify)
    {
        // 退出过程中 Dispatcher 可能已经关闭，避免后台线程抛异常
        if (Dispatcher.HasShutdownStarted) return;

        Dispatcher.BeginInvoke(new Action(() =>
        {
            Log($"[自动重连] {title}：{detail}");

            // 只有"刚开启 / 检测到断线 / 登录成功失败 / 异常"才会带 notify，
            // 例行轮询（在线、未联网、不在校园网）只在日志里留一行，不弹气泡。
            if (notify) BalloonRequested?.Invoke(title, detail);

            if (title.Contains("成功", StringComparison.Ordinal))
                SetStatus("外网已连接", detail, StatusKind.Online);
            else if (title.Contains("检测到断线", StringComparison.Ordinal))
                SetStatus("检测到断线", detail, StatusKind.Checking);
            else if (title.Contains("失败", StringComparison.Ordinal) ||
                     title.Contains("异常", StringComparison.Ordinal) ||
                     title.Contains("不在校园网", StringComparison.Ordinal))
                SetStatus(title, detail, StatusKind.Failed);
        }));
    }

    // ==================== 事件处理 ====================

    private void UserIdBox_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        if (_loading) return;
        _settings.UserId = UserIdBox.Text.Trim();
        _store.Save(_settings);
        UpdateSavedChip();
    }

    private void PasswordBox_PasswordChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        _settings.Password = PasswordBox1.Password;
        _store.Save(_settings);
        UpdateSavedChip();
    }

    private void ServiceCombo_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_loading) return;
        _settings.ServiceIndex = Math.Clamp(ServiceCombo.SelectedIndex + 1, 1, CampusNetwork.Services.Length);
        _store.Save(_settings);
        HeroServiceText.Text = $"服务：{_settings.ServiceName}";
    }

    private void IntervalCombo_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_loading) return;
        _settings.CheckIntervalSec = SelectedInterval();
        _store.Save(_settings);

        if (_reconnect.IsRunning)
        {
            _reconnect.Stop();
            _reconnect.Start();
        }
    }

    private void CheckUrlBox_LostFocus(object sender, RoutedEventArgs e)
    {
        var url = CheckUrlBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url)) url = CampusNetwork.DefaultCheckUrl;

        CheckUrlBox.Text = url;
        _settings.CheckUrl = url;
        _store.Save(_settings);
    }

    private void AutoConnectCheck_Click(object sender, RoutedEventArgs e)
    {
        _settings.AutoConnectOnStart = AutoConnectCheck.IsChecked == true;
        _store.Save(_settings);
    }

    private void AutoReconnectCheck_Click(object sender, RoutedEventArgs e)
    {
        var enabled = AutoReconnectCheck.IsChecked == true;
        _settings.AutoReconnect = enabled;
        _store.Save(_settings);

        if (enabled)
        {
            _reconnect.Start();
            Log($"已开启后台自动重连（间隔 {_settings.CheckIntervalSec} 秒）");
            AnnounceReconnectStarted();
        }
        else
        {
            _reconnect.Stop();
            Log("已关闭后台自动重连");
        }
    }

    /// <summary>开启自动重连时给一次提示，之后只在真正断线重连时才打扰用户</summary>
    private void AnnounceReconnectStarted()
    {
        BalloonRequested?.Invoke(
            "自动重连已开启",
            $"每 {_settings.CheckIntervalSec} 秒静默检测一次，断线后自动登录；期间不再弹提示");
    }

    private void AutoStartCheck_Click(object sender, RoutedEventArgs e)
    {
        var enabled = AutoStartCheck.IsChecked == true;
        StartupManager.SetEnabled(enabled);
        Log(enabled ? "已设置开机自启（启动后最小化到托盘）" : "已取消开机自启");
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        await ConnectAsync(false);
    }

    private async void ForceButton_Click(object sender, RoutedEventArgs e)
    {
        await ConnectAsync(true);
    }

    private async void HeroRefreshButton_Click(object sender, RoutedEventArgs e)
    {
        RefreshWifiState();
        await ConnectAsync(false);
    }

    private void ClearLogButton_Click(object sender, RoutedEventArgs e)
    {
        LogList.Items.Clear();
    }

    private void ClearButton_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(this,
            "将清除本机保存的学工号与密码。",
            "清除账号？",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Warning);

        if (result != MessageBoxResult.OK) return;

        _settings.UserId = string.Empty;
        _settings.Password = string.Empty;
        _store.Save(_settings);

        _loading = true;
        try
        {
            UserIdBox.Text = string.Empty;
            PasswordBox1.Password = string.Empty;
        }
        finally
        {
            _loading = false;
        }

        UpdateSavedChip();
        Log("已清除本机保存的账号信息");
    }

    private void ResetButton_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(this,
            "将清除账号、密码、网络服务选择、自动化开关、开机自启、检测地址和运行日志，并关闭后台自动重连。\n此操作不可撤销。",
            "重置全部设置？",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Warning);

        if (result != MessageBoxResult.OK) return;

        _reconnect.Stop();
        StartupManager.SetEnabled(false);
        _store.Clear();
        _settings = new AppSettings();

        LoadSettingsIntoUi();
        UpdateSavedChip();
        RefreshWifiState();
        SetStatus("待检测", "设置已重置，尚未检测", StatusKind.Idle);
        Log("已重置全部设置，所有配置恢复默认值");
    }

    private void ThemeButton_Click(object sender, RoutedEventArgs e)
    {
        ThemeManager.Apply(!ThemeManager.IsDark);
        UpdateThemeButton();

        // 主题切换后，代码里手工设置的画刷需要重新取一次
        UpdateWifiHint();
        UpdateSavedChip();
    }
}
