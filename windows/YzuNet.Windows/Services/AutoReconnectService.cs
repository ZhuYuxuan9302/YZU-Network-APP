using System;
using System.Threading;
using System.Threading.Tasks;

namespace YzuNet.Windows.Services;

/// <summary>
/// 断线自动重连的后台循环，逻辑与安卓端 AutoLoginService 一致：
/// 检测 → 在线则等待 / 离线则认证 → 成功后等待 30s 让网络真正就绪。
/// </summary>
public sealed class AutoReconnectService : IDisposable
{
    private readonly CampusNetClient _client = new();
    private readonly SettingsStore _store = new();

    private CancellationTokenSource? _cts;
    private Task? _loop;

    /// <summary>参数：标题, 详情。回调发生在后台线程，UI 侧需自行切到 Dispatcher。</summary>
    public event Action<string, string>? StatusChanged;

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

    private void Report(string title, string detail) => StatusChanged?.Invoke(title, detail);

    private async Task LoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var settings = _store.Load();

                if (!settings.HasCredentials)
                {
                    Report("未配置账号", "请先在主界面填写学工号与密码");
                    await Task.Delay(TimeSpan.FromSeconds(60), ct).ConfigureAwait(false);
                    continue;
                }

                var connection = NetworkMonitor.GetConnection();
                if (!connection.IsConnected)
                {
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
                    Report("外网连接正常", $"{check.Detail} · 下次检测 {settings.CheckIntervalSec}s 后");
                    await Task.Delay(TimeSpan.FromSeconds(settings.CheckIntervalSec), ct).ConfigureAwait(false);
                    continue;
                }

                Report("检测到断线", $"{check.Detail}，正在自动登录…");
                var outcome = await _client
                    .LoginAsync(settings.UserId, settings.Password, settings.ServiceIndex, null, ct)
                    .ConfigureAwait(false);

                if (outcome.Status == LoginStatus.Success)
                {
                    Report("自动登录成功", $"暂停检测 {CampusNetwork.PostLoginGraceSec}s，等待网络就绪");
                    await Task.Delay(TimeSpan.FromSeconds(CampusNetwork.PostLoginGraceSec), ct)
                        .ConfigureAwait(false);
                }
                else
                {
                    Report("自动登录失败", outcome.Message);
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
                Report("自动重连异常", e.Message);
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
