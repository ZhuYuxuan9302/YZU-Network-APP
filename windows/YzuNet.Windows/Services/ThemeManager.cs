using System;
using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;

namespace YzuNet.Windows.Services;

/// <summary>
/// 主题切换。
///
/// 关键设计：**浅色基础配色与全部样式都内联在 App.xaml 里（编译进 BAML）**，
/// 运行期完全不从外部 xaml 文件加载 ResourceDictionary——单文件发布时 pack:// 解析
/// 出问题的话，加载字典会抛异常，进而让窗口创建失败（表现为"进程活着但没有窗口也没有托盘"）。
///
/// 深色主题只是**代码构造的一组画刷**，叠加到 Application.Resources.MergedDictionaries 里，
/// 靠"后加入的优先"覆盖浅色值；样式本身引用的是 DynamicResource，会自动跟着变。
/// 这样即使深色字典构造失败，界面也只是维持浅色，绝不会崩。
/// </summary>
public static class ThemeManager
{
    /// <summary>用来标记"这是我们叠加的深色字典"，避免靠 Uri 字符串判断</summary>
    private const string OverlayMarkerKey = "__YzuNetThemeOverlay";

    public static bool IsDark { get; private set; }

    /// <summary>读取系统"应用模式"（浅色 / 深色）</summary>
    public static bool SystemPrefersDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            if (key?.GetValue("AppsUseLightTheme") is int lightTheme) return lightTheme == 0;
        }
        catch
        {
            // 读不到就按浅色处理
        }

        return false;
    }

    /// <summary>切换主题。内部吞掉所有异常：主题只是外观，不允许影响功能。</summary>
    public static void Apply(bool dark)
    {
        var application = Application.Current;
        if (application is null) return;

        // 先移除上一次叠加的深色字典，保证不会重复叠加
        try
        {
            var merged = application.Resources.MergedDictionaries;
            for (var i = merged.Count - 1; i >= 0; i--)
            {
                if (merged[i].Contains(OverlayMarkerKey)) merged.RemoveAt(i);
            }
        }
        catch
        {
            // ignore
        }

        if (!dark)
        {
            IsDark = false;
            return;
        }

        try
        {
            application.Resources.MergedDictionaries.Add(BuildDarkOverlay());
            IsDark = true;
        }
        catch
        {
            // 深色没叠上就维持浅色，不要半死不活
            IsDark = false;
        }
    }

    // ==================== 深色画刷 ====================

    private static ResourceDictionary BuildDarkOverlay()
    {
        var dictionary = new ResourceDictionary { [OverlayMarkerKey] = true };

        Add(dictionary, "WindowBackgroundBrush", 0x0F, 0x14, 0x1B);
        Add(dictionary, "CardBackgroundBrush", 0x16, 0x1C, 0x25);
        Add(dictionary, "CardBorderBrush", 0x2B, 0x35, 0x42);
        Add(dictionary, "TextBrush", 0xE3, 0xE6, 0xEC);
        Add(dictionary, "SubtleTextBrush", 0xA7, 0xB0, 0xBE);
        Add(dictionary, "PrimaryBrush", 0x7F, 0xB0, 0xFF);
        Add(dictionary, "PrimaryTextBrush", 0x00, 0x20, 0x4A);
        Add(dictionary, "SuccessBrush", 0x4A, 0xDE, 0x80);
        Add(dictionary, "WarnBrush", 0xFB, 0xBF, 0x24);
        Add(dictionary, "DangerBrush", 0xF8, 0x71, 0x71);
        Add(dictionary, "InputBackgroundBrush", 0x1D, 0x24, 0x2E);
        Add(dictionary, "LogBackgroundBrush", 0x13, 0x1A, 0x22);
        Add(dictionary, "HintSuccessBackBrush", 0x12, 0x30, 0x1F);
        Add(dictionary, "HintWarnBackBrush", 0x32, 0x26, 0x12);
        Add(dictionary, "HintDangerBackBrush", 0x34, 0x18, 0x1A);
        Add(dictionary, "ChipBackBrush", 0x23, 0x2B, 0x36);

        return dictionary;
    }

    private static void Add(ResourceDictionary dictionary, string key, byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        dictionary[key] = brush;
    }
}
