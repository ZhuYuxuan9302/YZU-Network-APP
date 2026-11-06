using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace YzuNet.Windows.Services;

public sealed record CheckResult(bool Online, string Detail);

public enum LoginStatus
{
    Success,
    Fail,
    Error
}

public sealed record LoginOutcome(LoginStatus Status, string Message);

public static class CampusNetwork
{
    /// <summary>与 Python 脚本 SERVICE_LIST 顺序、文案完全一致，不可调整</summary>
    public static readonly string[] Services =
    {
        "学校互联网服务",
        "联通互联网服务",
        "移动互联网服务",
        "电信互联网服务",
        "校内免费服务"
    };

    public const string PortalDetectUrl = "http://123.123.123.123";
    public const string DefaultCheckUrl = "http://connect.rom.miui.com/generate_204";
    public const string CampusSsid = "YZU-WLAN";
    public const int PostLoginGraceSec = 30;

    public const string UserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:60.0) Gecko/20100101 Firefox/60.0";
}

/// <summary>
/// 校园网（深澜 srun eportal）认证客户端。
/// 流程与安卓端 / Python 脚本保持一致：
///   1. 访问探测地址触发网关拦截页
///   2. 正则抠出实时有效的认证入口
///   3. 拆 host + queryString
///   4. POST /eportal/InterFace.do?method=login
/// </summary>
public sealed class CampusNetClient : IDisposable
{
    private static readonly string[] ProbeUrls =
    {
        CampusNetwork.PortalDetectUrl,
        CampusNetwork.DefaultCheckUrl
    };

    private readonly HttpClient _http;

    public CampusNetClient()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            UseCookies = true,
            CookieContainer = new CookieContainer(),
            ServerCertificateCustomValidationCallback = (_, _, _, _) => true
        };

        _http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(25) };
        _http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", CampusNetwork.UserAgent);
        _http.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "*/*");
        _http.DefaultRequestHeaders.TryAddWithoutValidation("Accept-Language", "zh-CN,zh;q=0.9");
        _http.DefaultRequestHeaders.TryAddWithoutValidation("Referrer-Policy", "strict-origin-when-cross-origin");
        _http.DefaultRequestHeaders.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue
        {
            NoCache = true,
            NoStore = true
        };
    }

    /// <summary>返回 204 视为在线；其它状态 / 超时 / 连接失败都算断线</summary>
    public async Task<CheckResult> CheckOnlineAsync(string checkUrl, CancellationToken ct)
    {
        try
        {
            using var response = await _http
                .GetAsync(checkUrl, HttpCompletionOption.ResponseHeadersRead, ct)
                .ConfigureAwait(false);
            return new CheckResult((int)response.StatusCode == 204, $"HTTP {(int)response.StatusCode}");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e)
        {
            return new CheckResult(false, e.GetType().Name);
        }
    }

    public async Task<LoginOutcome> LoginAsync(
        string userId,
        string password,
        int serviceIndex,
        Action<string>? log,
        CancellationToken ct)
    {
        var services = CampusNetwork.Services;
        if (serviceIndex < 1 || serviceIndex > services.Length)
            return new LoginOutcome(LoginStatus.Error, $"网络服务序号非法：{serviceIndex}");

        var service = services[serviceIndex - 1];

        string portalUrl;
        try
        {
            portalUrl = await DiscoverPortalUrlAsync(log, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e)
        {
            return new LoginOutcome(LoginStatus.Error, $"无法获取认证入口：{e.Message}");
        }

        log?.Invoke($"认证入口：{portalUrl}");

        var parsed = ParsePortalUrl(portalUrl);
        if (parsed is null)
            return new LoginOutcome(LoginStatus.Error, $"无法从认证入口解析 host 与 queryString：{portalUrl}");

        var (host, queryString) = parsed.Value;
        log?.Invoke($"host={host}，服务：{service}，开始提交认证…");

        var outcome = await PostLoginAsync(host, queryString, userId, password, service, true, log, ct)
            .ConfigureAwait(false);
        if (outcome.Status == LoginStatus.Success) return outcome;

        var shouldRetry = outcome.Status == LoginStatus.Error ||
                          outcome.Message.Contains("服务", StringComparison.Ordinal) ||
                          outcome.Message.Contains("参数", StringComparison.Ordinal);
        if (shouldRetry)
        {
            log?.Invoke("认证参数可能不被接受，改用单次编码的 service 重试…");
            var second = await PostLoginAsync(host, queryString, userId, password, service, false, log, ct)
                .ConfigureAwait(false);
            if (second.Status == LoginStatus.Success) return second;
            outcome = second;
        }

        return outcome;
    }

    private async Task<string> DiscoverPortalUrlAsync(Action<string>? log, CancellationToken ct)
    {
        var lastError = "未知错误";

        foreach (var probe in ProbeUrls)
        {
            log?.Invoke($"正在访问 {probe} 触发网关拦截页…");
            string text;
            try
            {
                using var response = await _http.GetAsync(probe, ct).ConfigureAwait(false);
                text = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                log?.Invoke($"拦截页响应：HTTP {(int)response.StatusCode}");
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception e)
            {
                lastError = $"{e.GetType().Name}（{probe}）";
                log?.Invoke($"探测地址无法访问：{lastError}");
                continue;
            }

            var match = Regex.Match(text, "href='([^']+)'");
            if (!match.Success) match = Regex.Match(text, "href=\"([^\"]+)\"");
            if (match.Success) return match.Groups[1].Value;

            lastError = $"页面里没有认证入口（{probe}，前 120 字：{Truncate(text, 120)}）";
        }

        throw new InvalidOperationException(lastError);
    }

    private static (string Host, string QueryString)? ParsePortalUrl(string url)
    {
        var match = Regex.Match(url, "^https?://([^/]+)/(.*)$", RegexOptions.IgnoreCase);
        if (!match.Success) return null;

        var host = match.Groups[1].Value;
        var queryString = match.Groups[2].Value;
        if (string.IsNullOrWhiteSpace(host) || string.IsNullOrWhiteSpace(queryString)) return null;

        return (host, queryString);
    }

    private async Task<LoginOutcome> PostLoginAsync(
        string host,
        string queryString,
        string userId,
        string password,
        string service,
        bool doubleEncode,
        Action<string>? log,
        CancellationToken ct)
    {
        // 首次与脚本一致：service 先编码一次，再由表单整体编码（双重编码）
        var serviceValue = doubleEncode ? Uri.EscapeDataString(service) : service;

        var form = new Dictionary<string, string>
        {
            ["userId"] = userId,
            ["password"] = password,
            ["service"] = serviceValue,
            ["queryString"] = queryString,
            ["validcode"] = string.Empty,
            ["passwordEncrypt"] = "false"
        };

        var url = $"http://{host}/eportal/InterFace.do?method=login";

        try
        {
            using var content = new FormUrlEncodedContent(form);
            using var response = await _http.PostAsync(url, content, ct).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            log?.Invoke($"登录接口响应：HTTP {(int)response.StatusCode}");

            if (string.IsNullOrWhiteSpace(body))
                return new LoginOutcome(LoginStatus.Error, "服务器返回空响应，可能仍处于被网关拦截状态");

            try
            {
                using var document = JsonDocument.Parse(body);
                var root = document.RootElement;
                var result = root.TryGetProperty("result", out var resultElement)
                    ? resultElement.GetString()
                    : null;

                switch (result)
                {
                    case "success":
                        log?.Invoke("校园网连接成功");
                        return new LoginOutcome(LoginStatus.Success, "认证成功");

                    case "fail":
                        var message = root.TryGetProperty("message", out var messageElement)
                            ? messageElement.GetString()
                            : null;
                        return new LoginOutcome(
                            LoginStatus.Fail,
                            string.IsNullOrWhiteSpace(message) ? "未知错误（result=fail）" : message!);

                    default:
                        return new LoginOutcome(LoginStatus.Error, $"登录响应异常：{Truncate(body, 160)}");
                }
            }
            catch (JsonException)
            {
                return new LoginOutcome(LoginStatus.Error, $"响应不是 JSON：{Truncate(body, 120)}");
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e)
        {
            return new LoginOutcome(LoginStatus.Error, $"网络请求失败：{e.Message}");
        }
    }

    private static string Truncate(string value, int length)
    {
        if (string.IsNullOrEmpty(value)) return "<EMPTY>";
        return value.Length <= length ? value : value.Substring(0, length);
    }

    public void Dispose() => _http.Dispose();
}
