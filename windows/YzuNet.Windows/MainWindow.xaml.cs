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

    private AppSettings _settings;
    private bool _loading = true;
    private bool _busy;
    private bool _reallyExit;

    /// <summary>托盘菜单请求退出</summary>
    public event Action? ExitRequested;

    /// <summary>托盘气泡提示（标题, 内容）</summary>
    public event Action<string, string>? BalloonRequested;

    public MainWindow()
    {
        InitializeComponent();

        _settings = _store.Load();
        PopulateStaticItems();
        LoadSettingsIntoUi();

        _reconnect.StatusChanged += OnReconnectStatusChanged;

        UpdateThemeButton();
        UpdateWifiHint();
        UpdateSavedChip();
        SetStatus("待检测", "尚未检测", StatusKind.Idle);

        Log("扬大校园网 · 一键连接（Windows 版）");
        Log("关闭窗口会最小化到托盘，右键托盘图标可退出");
        if (_settings.HasCredentials)
            Log($"已载入账号 {Mask(_settings.UserId)}");

        Loaded += MainWindow_Loaded;
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

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        RefreshWifiState();

        if (_settings.AutoReconnect)
        {
            _reconnect.Start();
            Log("已恢复后台自动重连");
        }

        if (_settings.AutoConnectOnStart && _settings.HasCredentials)
        {
            await ConnectAsync(false);
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
        WifiHintBorder.Background = (Brush)FindResource(backgroundKey);
        WifiHintText.Foreground = (Brush)FindResource(foregroundKey);
    }

    private void UpdateSavedChip()
    {
        var saved = _settings.HasCredentials;
        SavedChipText.Text = saved ? "已保存" : "未填写";
        SavedChipText.Foreground = (Brush)FindResource(saved ? "SuccessBrush" : "SubtleTextBrush");
        SavedChipBorder.Background = (Brush)FindResource("ChipBackBrush");
        ClearButton.Visibility = saved ? Visibility.Visible : Visibility.Collapsed;
        ForceButton.Visibility = saved ? Visibility.Visible : Visibility.Collapsed;
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
            Dispatcher.BeginInvoke(new Action(() => Log(message)));
            return;
        }

        LogList.Items.Add($"[{DateTime.Now:HH:mm:ss}] {message}");
        while (LogList.Items.Count > 300) LogList.Items.RemoveAt(0);
        LogList.ScrollIntoView(LogList.Items[LogList.Items.Count - 1]);
    }

    private static string Mask(string value) =>
        value.Length > 6 ? $"{value.Substring(0, 3)}***{value.Substring(value.Length - 3)}" : "***";

    // ==================== 自动重连回调 ====================

    private void OnReconnectStatusChanged(string title, string detail)
    {
        // 退出过程中 Dispatcher 可能已经关闭，避免后台线程抛异常
        if (Dispatcher.HasShutdownStarted) return;

        Dispatcher.BeginInvoke(new Action(() =>
        {
            Log($"[自动重连] {title}：{detail}");
            BalloonRequested?.Invoke(title, detail);

            if (title.Contains("成功", StringComparison.Ordinal))
                SetStatus("外网已连接", detail, StatusKind.Online);
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
        }
        else
        {
            _reconnect.Stop();
            Log("已关闭后台自动重连");
        }
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
