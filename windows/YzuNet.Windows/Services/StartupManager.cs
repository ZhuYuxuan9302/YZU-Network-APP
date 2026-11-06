using System;
using Microsoft.Win32;

namespace YzuNet.Windows.Services;

/// <summary>开机自启：写入 HKCU\...\Run，删除即关闭</summary>
public static class StartupManager
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "YzuNet";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, false);
            return key?.GetValue(ValueName) is string;
        }
        catch
        {
            return false;
        }
    }

    public static void SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true);
            if (key is null) return;

            if (enabled)
            {
                var exePath = Environment.ProcessPath;
                if (string.IsNullOrEmpty(exePath)) return;
                key.SetValue(ValueName, $"\"{exePath}\" --tray");
            }
            else if (key.GetValue(ValueName) is not null)
            {
                key.DeleteValue(ValueName, false);
            }
        }
        catch
        {
            // ignore
        }
    }
}
