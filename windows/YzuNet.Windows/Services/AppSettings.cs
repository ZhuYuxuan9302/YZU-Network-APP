using System;
using System.Text.Json.Serialization;

namespace YzuNet.Windows.Services;

/// <summary>
/// 应用配置。字段与安卓端 AppSettings 一一对应。
/// </summary>
public sealed class AppSettings
{
    public string UserId { get; set; } = string.Empty;

    /// <summary>已用 DPAPI 加密的密码（Base64），不落明文</summary>
    public string PasswordProtected { get; set; } = string.Empty;

    public int ServiceIndex { get; set; } = 1;

    public string CheckUrl { get; set; } = CampusNetwork.DefaultCheckUrl;

    /// <summary>启动程序时自动尝试连接</summary>
    public bool AutoConnectOnStart { get; set; } = true;

    /// <summary>后台断线自动重连</summary>
    public bool AutoReconnect { get; set; }

    public int CheckIntervalSec { get; set; } = 15;

    [JsonIgnore]
    public string Password { get; set; } = string.Empty;

    [JsonIgnore]
    public bool HasCredentials =>
        !string.IsNullOrWhiteSpace(UserId) && !string.IsNullOrEmpty(Password);

    [JsonIgnore]
    public string ServiceName =>
        CampusNetwork.Services[Math.Clamp(ServiceIndex, 1, CampusNetwork.Services.Length) - 1];
}
