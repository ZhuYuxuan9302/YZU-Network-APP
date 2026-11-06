using System;
using System.Threading;
using System.Threading.Tasks;

namespace YzuNet.Windows.Services;

/// <summary>
/// 断线自动重连的后台循环，逻辑与安卓端 AutoLoginService 一致：
/// 检测 → 在线则等待 / 离线则认证 → 成功后等待 30s 让网络真正就绪。
///
/// 通知分级：日常轮询（在线、未连网、不在校园网）只更新日志与界面状态，
/// 只有「检测到断线开始重连 / 登录成功 / 登录失败 / 重连异常」才弹托盘气泡；
/// 同类通知还有 5 分钟冷却，避免一直失败时刷屏。
/// </summary>
public sealed class AutoReconnectService : IDisposable
{
    /// <summary>同一类通知的最短间隔，防止后台反复失败时连续弹气泡</summary>
    private static readonly TimeSpan NoticeCooldown = TimeSpan.FromMinutes(5);

    private readonly CampusNetClient _client = new();
    private readonly SettingsStore _store = new();

    private CancellationTokenSource? _cts;
    private Task? _loop;

    private string? _lastNoticeKey;
    private DateTime _lastNoticeAtUtc = DateTime.MinValue;

    /// <summary>参数：标题, 详情, 是否需要弹通知。回调发生在后台线程，UI 侧需自行切到 Dispatcher。</summary>
    public event Action<string, string, bool>? StatusChanged;

    public bool IsRunning => _loop is { IsCompleted: false };

    public void Start()
    {
        if (IsRunning) return;

        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _loop = Task.Run(() => LoopAsync(token), token);
    }

    public void Stop()
    {
        try
        {
            _cts?.Cancel();
        }
        catch
        {
            // ignore
        }

        _cts?.Dispose();
        _cts = null;
        _loop = null;
    }

    /// <summary>日常状态：只更新界面与日志，不弹通知</summary>
    private void Report(string title, string detail) => StatusChanged?.Invoke(title, detail, false);

    /// <summary>值得打扰用户的事件：刚发现断线、重连结果、异常</summary>
    private void Notify(string title, string detail) =>
        StatusChanged?.Invoke(title, detail, ShouldNotify(title));

    private bool ShouldNotify(string key)
    {
        var now = DateTime.UtcNow;

        // 同一类事件在冷却期内只弹一次（例如断线后每 15s 失败一次）
        if (string.Equals(_lastNoticeKey, key, StringComparison.Ordinal) &&
            now - _lastNoticeAtUtc < NoticeCooldown)
        {
            return false;
        }

        _lastNoticeKey = key;
        _lastNoticeAtUtc = now;
        return true;
    }

    private async Task LoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var settings = _store.Load();

                if (!settings.HasCredentials)
                {
                    Notify("未配置账号", "请先在主界面填写学工号与密码");
                    await Task.Delay(TimeSpan.FromSeconds(60), ct).ConfigureAwait(false);
                    continue;
                }

                var connection = NetworkMonitor.GetConnection();
                if (!connection.IsConnected)
                {
                    // 网线/Wi-Fi 都没连，属于正常待机状态，只写日志不打扰
                    Report("未连接网络", "自动重连已暂停（无线 / 有线都没有连接）");
                    await Task.Delay(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false);
                    continue;
                }

                // 校名校验只对无线有意义；有线连接直接放行
                if (connection.Kind == ConnectionKind.Wifi &&
                    connection.Ssid is not null &&
                    !NetworkMonitor.IsCampusSsid(connection.Ssid))
                {
                    Report("不在校园网", $"当前 Wi-Fi「{connection.Ssid}」不是 {CampusNetwork.CampusSsid}");
                    await Task.Delay(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false);
                    continue;
                }

                var check = await _client.CheckOnlineAsync(settings.CheckUrl, ct).ConfigureAwait(false);
                if (check.Online)
                {
                    // 一切正常的例行探测：静默，不弹通知
                    Report("外网连接正常", $"{check.Detail} · 下次检测 {settings.CheckIntervalSec}s 后");
                    await Task.Delay(TimeSpan.FromSeconds(settings.CheckIntervalSec), ct).ConfigureAwait(false);
                    continue;
                }

                Notify("检测到断线", $"{check.Detail}，正在自动登录…");
                var outcome = await _client
                    .LoginAsync(settings.UserId, settings.Password, settings.ServiceIndex, null, ct)
                    .ConfigureAwait(false);

                if (outcome.Status == LoginStatus.Success)
                {
                    Notify("自动登录成功", $"暂停检测 {CampusNetwork.PostLoginGraceSec}s，等待网络就绪");
                    await Task.Delay(TimeSpan.FromSeconds(CampusNetwork.PostLoginGraceSec), ct)
                        .ConfigureAwait(false);
                }
                else
                {
                    Notify("自动登录失败", outcome.Message);
                    await Task.Delay(TimeSpan.FromSeconds(settings.CheckIntervalSec), ct)
                        .ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception e)
            {
                Notify("自动重连异常", e.Message);
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(15), ct).ConfigureAwait(false);
                }
                catch
                {
                    return;
                }
            }
        }
    }

    public void Dispose()
    {
        Stop();
        _client.Dispose();
    }
}
