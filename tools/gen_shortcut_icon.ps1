Add-Type -AssemblyName System.Drawing
$sizes = @{ 'mdpi'=48; 'hdpi'=72; 'xhdpi'=96; 'xxhdpi'=144; 'xxxhdpi'=192 }
$root = "D:\Data\Repositories\YZU-Network-APP\app\src\main\res"

# bolt outline, normalized 0..1, clockwise
$boltFlat = "0.60,0.10;0.26,0.56;0.46,0.56;0.40,0.92;0.74,0.42;0.54,0.42"

foreach ($k in $sizes.Keys) {
  $S = $sizes[$k]
  $dir = Join-Path $root "mipmap-$k"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null

  $bmp = New-Object System.Drawing.Bitmap($S, $S)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.Clear([System.Drawing.Color]::Transparent)

  $bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 46, 125, 91))
  $r = [Math]::Max(2, [int]($S * 0.22))
  $d = $r * 2
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $path.AddArc(0, 0, $d, $d, 180, 90)
  $path.AddArc($S - 1 - $d, 0, $d, $d, 270, 90)
  $path.AddArc($S - 1 - $d, $S - 1 - $d, $d, $d, 0, 90)
  $path.AddArc(0, $S - 1 - $d, $d, $d, 90, 90)
  $path.CloseFigure()
  $g.FillPath($bg, $path)
  $path.Dispose()

  $ptList = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
  foreach ($pair in $boltFlat.Split(';')) {
    $xy = $pair.Split(',')
    $nx = [double]$xy[0]
    $ny = [double]$xy[1]
    $x = [single](($S - 1) * (0.5 + ($nx - 0.5) * 0.74))
    $y = [single](($S - 1) * (0.5 + ($ny - 0.5) * 0.74))
    $ptList.Add((New-Object System.Drawing.PointF($x, $y)))
  }
  $wb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
  $g.FillPolygon($wb, $ptList.ToArray())

  $bmp.Save((Join-Path $dir "ic_shortcut.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $wb.Dispose(); $bg.Dispose(); $g.Dispose(); $bmp.Dispose()
}
Get-ChildItem -Recurse -Filter "ic_shortcut.png" $root | Select-Object Length, DirectoryName | Format-Table -AutoSize
