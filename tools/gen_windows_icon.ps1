Add-Type -AssemblyName System.Drawing

$dir = "D:\Data\Repositories\YZU-Network-APP\windows\YzuNet.Windows\Assets"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$S = 256
$bmp = New-Object System.Drawing.Bitmap($S, $S)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::Transparent)

# rounded rect background
$bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 27, 110, 243))
$r = [int]($S * 0.22)
$d = $r * 2
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc(0, 0, $d, $d, 180, 90)
$path.AddArc($S - 1 - $d, 0, $d, $d, 270, 90)
$path.AddArc($S - 1 - $d, $S - 1 - $d, $d, $d, 0, 90)
$path.AddArc(0, $S - 1 - $d, $d, $d, 90, 90)
$path.CloseFigure()
$g.FillPath($bg, $path)
$path.Dispose()

# wifi arcs
$penW = [Math]::Max(2.0, $S * 0.072)
$pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, $penW)
$pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$cx = $S / 2.0
$baseY = $S * 0.735
foreach ($rf in @(0.155, 0.275, 0.395)) {
  $rr = $S * $rf
  $g.DrawArc($pen, $cx - $rr, $baseY - $rr, $rr * 2, $rr * 2, 225, 90)
}

# dot
$dotR = [Math]::Max(2.0, $S * 0.058)
$wb = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$g.FillEllipse($wb, $cx - $dotR, $baseY - $dotR, $dotR * 2, $dotR * 2)

$icoPath = Join-Path $dir "app.ico"
$icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
$stream = [System.IO.File]::Create($icoPath)
$icon.Save($stream)
$stream.Close()
$stream.Dispose()
$icon.Dispose()

Get-Item $icoPath | Select-Object Name, Length
