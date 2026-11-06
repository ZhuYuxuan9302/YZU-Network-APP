Add-Type -AssemblyName System.Drawing
$sizes = @{ 'mdpi'=48; 'hdpi'=72; 'xhdpi'=96; 'xxhdpi'=144; 'xxxhdpi'=192 }
$root = "D:\Data\Repositories\YZU-Network-APP\app\src\main\res"
foreach ($k in $sizes.Keys) {
  $S = $sizes[$k]
  $dir = Join-Path $root "mipmap-$k"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  foreach ($round in @($false, $true)) {
    $bmp = New-Object System.Drawing.Bitmap($S, $S)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $bg = [System.Drawing.Color]::FromArgb(255, 27, 110, 243)
    $brush = New-Object System.Drawing.SolidBrush($bg)
    if ($round) {
      $g.FillEllipse($brush, 0, 0, $S - 1, $S - 1)
    } else {
      $r = [Math]::Max(2, [int]($S * 0.22))
      $path = New-Object System.Drawing.Drawing2D.GraphicsPath
      $d = $r * 2
      $path.AddArc(0, 0, $d, $d, 180, 90)
      $path.AddArc($S - 1 - $d, 0, $d, $d, 270, 90)
      $path.AddArc($S - 1 - $d, $S - 1 - $d, $d, $d, 0, 90)
      $path.AddArc(0, $S - 1 - $d, $d, $d, 90, 90)
      $path.CloseFigure()
      $g.FillPath($brush, $path)
      $path.Dispose()
    }
    $penW = [Math]::Max(1.8, $S * 0.075)
    $white = New-Object System.Drawing.Pen([System.Drawing.Color]::White, $penW)
    $white.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $white.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $cx = $S / 2.0
    $baseY = $S * 0.735
    foreach ($rf in @(0.155, 0.275, 0.395)) {
      $rr = $S * $rf
      $g.DrawArc($white, $cx - $rr, $baseY - $rr, $rr * 2, $rr * 2, 225, 90)
    }
    $dotR = [Math]::Max(1.5, $S * 0.058)
    $wb = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillEllipse($wb, $cx - $dotR, $baseY - $dotR, $dotR * 2, $dotR * 2)
    if ($round) { $name = "ic_launcher_round.png" } else { $name = "ic_launcher.png" }
    $bmp.Save((Join-Path $dir $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $wb.Dispose(); $white.Dispose(); $brush.Dispose(); $g.Dispose(); $bmp.Dispose()
  }
}
Get-ChildItem -Recurse -Filter "ic_launcher*.png" $root | Select-Object Name, Length | Format-Table -AutoSize
