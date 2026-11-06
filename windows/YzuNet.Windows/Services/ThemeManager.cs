using System;
using System.Windows;
using Microsoft.Win32;

namespace YzuNet.Windows.Services;

/// <summary>主题切换：跟随系统深浅设置，也可以手动切换</summary>
public static class ThemeManager
{
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
            // ignore
        }

        return false;
    }

    public static void Apply(bool dark)
    {
        IsDark = dark;

        var application = Application.Current;
        if (application is null) return;

        var dictionary = new ResourceDictionary
        {
            Source = new Uri(dark ? "Themes/Dark.xaml" : "Themes/Light.xaml", UriKind.Relative)
        };

        if (application.Resources.MergedDictionaries.Count == 0)
        {
            application.Resources.MergedDictionaries.Add(dictionary);
        }
        else
        {
            application.Resources.MergedDictionaries[0] = dictionary;
        }
    }
}
