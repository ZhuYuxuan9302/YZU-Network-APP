using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace YzuNet.Windows.Services;

/// <summary>
/// 配置持久化：%APPDATA%\YzuNet\settings.json
/// 密码用 Windows DPAPI（当前用户作用域）加密，换账号或换机器都解不开。
/// </summary>
public sealed class SettingsStore
{
    private readonly string _dir;
    private readonly string _file;
    private readonly JsonSerializerOptions _json = new() { WriteIndented = true };

    public SettingsStore()
    {
        _dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "YzuNet");
        _file = Path.Combine(_dir, "settings.json");
    }

    public string FilePath => _file;

    public AppSettings Load()
    {
        try
        {
            if (!File.Exists(_file)) return new AppSettings();

            var json = File.ReadAllText(_file, Encoding.UTF8);
            var settings = JsonSerializer.Deserialize<AppSettings>(json);
            if (settings is null) return new AppSettings();

            settings.Password = Unprotect(settings.PasswordProtected);
            if (string.IsNullOrWhiteSpace(settings.CheckUrl))
                settings.CheckUrl = CampusNetwork.DefaultCheckUrl;
            settings.ServiceIndex = Math.Clamp(settings.ServiceIndex, 1, CampusNetwork.Services.Length);
            settings.CheckIntervalSec = Math.Clamp(settings.CheckIntervalSec, 5, 300);
            return settings;
        }
        catch
        {
            // 配置损坏时回到默认值，不让程序起不来
            return new AppSettings();
        }
    }

    public void Save(AppSettings settings)
    {
        try
        {
            Directory.CreateDirectory(_dir);
            settings.PasswordProtected = Protect(settings.Password);
            var json = JsonSerializer.Serialize(settings, _json);
            File.WriteAllText(_file, json, Encoding.UTF8);
        }
        catch
        {
            // 写盘失败不应该影响主流程
        }
    }

    public void Clear()
    {
        try
        {
            if (File.Exists(_file)) File.Delete(_file);
        }
        catch
        {
            // ignore
        }
    }

    private static string Protect(string plain)
    {
        if (string.IsNullOrEmpty(plain)) return string.Empty;
        try
        {
            var bytes = Encoding.UTF8.GetBytes(plain);
            var encrypted = ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser);
            return Convert.ToBase64String(encrypted);
        }
        catch
        {
            // 极端情况下（如用户配置文件损坏）退化为不做加密，保证功能可用
            return Convert.ToBase64String(Encoding.UTF8.GetBytes(plain));
        }
    }

    private static string Unprotect(string stored)
    {
        if (string.IsNullOrEmpty(stored)) return string.Empty;
        try
        {
            var bytes = Convert.FromBase64String(stored);
            var decrypted = ProtectedData.Unprotect(bytes, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(decrypted);
        }
        catch
        {
            try
            {
                return Encoding.UTF8.GetString(Convert.FromBase64String(stored));
            }
            catch
            {
                return string.Empty;
            }
        }
    }
}
