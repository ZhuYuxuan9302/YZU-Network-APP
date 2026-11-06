using System;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Text;

namespace YzuNet.Windows.Services;

public enum ConnectionKind
{
    None,
    Wifi,
    Ethernet
}

/// <summary>当前网络连接信息</summary>
public sealed record ConnectionInfo(ConnectionKind Kind, string? Ssid, string Description)
{
    public static readonly ConnectionInfo Empty = new(ConnectionKind.None, null, string.Empty);

    public bool IsConnected => Kind != ConnectionKind.None;

    public bool IsWired => Kind == ConnectionKind.Ethernet;

    /// <summary>给人看的短描述</summary>
    public string DisplayName => Kind switch
    {
        ConnectionKind.Wifi => string.IsNullOrEmpty(Ssid) ? "Wi-Fi（网络名未知）" : Ssid!,
        ConnectionKind.Ethernet => string.IsNullOrEmpty(Description) ? "有线连接" : $"有线连接 · {Description}",
        _ => "未连接"
    };
}

/// <summary>
/// 连接状态读取：无线 + 有线都支持。
/// .NET 没有直接暴露 SSID 的 API，Wi-Fi 名靠解析 `netsh wlan show interfaces`。
/// 有线连接没有 SSID 概念，因此**不做校名校验**，直接进入认证流程。
/// </summary>
public static class NetworkMonitor
{
    private static readonly string[] VirtualAdapterKeywords =
    {
        "virtual", "vmware", "hyper-v", "loopback", "tap-", "vpn", "bluetooth", "wsl"
    };

    /// <summary>取当前可用的连接，优先 Wi-Fi（这样才能校验校名）</summary>
    public static ConnectionInfo GetConnection()
    {
        try
        {
            NetworkInterface? ethernet = null;

            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (!IsUsable(nic)) continue;

                if (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
                {
                    // 无线优先：能拿到 SSID 才有意义
                    return new ConnectionInfo(ConnectionKind.Wifi, GetCurrentSsid(), nic.Description ?? string.Empty);
                }

                if (nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet)
                {
                    ethernet ??= nic;
                }
            }

            if (ethernet is not null)
                return new ConnectionInfo(ConnectionKind.Ethernet, null, ethernet.Description ?? string.Empty);
        }
        catch
        {
            // ignore
        }

        return ConnectionInfo.Empty;
    }

    public static bool HasNetwork() => GetConnection().IsConnected;

    private static bool IsUsable(NetworkInterface nic)
    {
        if (nic.OperationalStatus != OperationalStatus.Up) return false;

        if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) return false;
        if (nic.NetworkInterfaceType == NetworkInterfaceType.Tunnel) return false;
        if (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ||
            nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet)
        {
            var description = nic.Description ?? string.Empty;
            foreach (var keyword in VirtualAdapterKeywords)
            {
                if (description.IndexOf(keyword, StringComparison.OrdinalIgnoreCase) >= 0) return false;
            }

            return true;
        }

        return false;
    }

    public static string? GetCurrentSsid()
    {
        try
        {
            var startInfo = new ProcessStartInfo("netsh", "wlan show interfaces")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8
            };

            using var process = Process.Start(startInfo);
            if (process is null) return null;

            var output = process.StandardOutput.ReadToEnd();
            if (!process.WaitForExit(3000))
            {
                try { process.Kill(); } catch { }
            }

            foreach (var raw in output.Split('\n'))
            {
                var line = raw.Trim();
                // "BSSID   : xx:xx" 也包含 SSID 字样，所以必须从行首精确匹配
                if (!line.StartsWith("SSID", StringComparison.OrdinalIgnoreCase)) continue;

                var index = line.IndexOf(':');
                if (index < 0) continue;

                var ssid = line.Substring(index + 1).Trim();
                return string.IsNullOrWhiteSpace(ssid) ? null : ssid;
            }
        }
        catch
        {
            // ignore
        }

        return null;
    }

    /// <summary>完全相等或前缀 + 连字符，兼容 YZU-WLAN-5G 这类衍生名</summary>
    public static bool IsCampusSsid(string? ssid)
    {
        if (string.IsNullOrWhiteSpace(ssid)) return false;

        var value = ssid.Trim().Trim('"').ToUpperInvariant();
        var target = CampusNetwork.CampusSsid.ToUpperInvariant();

        return value == target || value.StartsWith(target + "-", StringComparison.Ordinal);
    }
}
