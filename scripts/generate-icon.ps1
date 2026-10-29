$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $projectRoot "src\main\resources\icons"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$size = 1024
$bitmap = New-Object System.Drawing.Bitmap $size, $size
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$graphics.Clear([System.Drawing.Color]::Transparent)

function New-RoundedRectanglePath(
    [float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $radius * 2
    $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
    $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
    $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

$outer = New-RoundedRectanglePath 34 34 956 956 215
$background = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.PointF 120, 100),
    (New-Object System.Drawing.PointF 900, 950),
    [System.Drawing.Color]::FromArgb(255, 39, 111, 235),
    [System.Drawing.Color]::FromArgb(255, 9, 40, 102))
$graphics.FillPath($background, $outer)

$shinePath = New-RoundedRectanglePath 76 76 872 430 135
$shine = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.PointF 0, 76),
    (New-Object System.Drawing.PointF 0, 506),
    [System.Drawing.Color]::FromArgb(55, 255, 255, 255),
    [System.Drawing.Color]::FromArgb(0, 255, 255, 255))
$graphics.FillPath($shine, $shinePath)

$whitePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(250, 255, 255, 255)), 74
$whitePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$whitePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$cyanPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 75, 221, 238)), 66
$cyanPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$cyanPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

# Upper sync arrow: left to right.
$graphics.DrawLine($whitePen, 265, 355, 682, 355)
$topHead = New-Object System.Drawing.Drawing2D.GraphicsPath
$topHead.AddPolygon([System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF 664, 285),
    (New-Object System.Drawing.PointF 764, 355),
    (New-Object System.Drawing.PointF 664, 425)))
$graphics.FillPath((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(250, 255, 255, 255))), $topHead)

# Lower sync arrow: right to left.
$graphics.DrawLine($cyanPen, 759, 669, 342, 669)
$bottomHead = New-Object System.Drawing.Drawing2D.GraphicsPath
$bottomHead.AddPolygon([System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF 360, 599),
    (New-Object System.Drawing.PointF 260, 669),
    (New-Object System.Drawing.PointF 360, 739)))
$graphics.FillPath((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 75, 221, 238))), $bottomHead)

# Three file nodes in the center imply folders and file synchronization.
$dotBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(235, 255, 255, 255))
foreach ($x in @(438, 512, 586)) {
    $graphics.FillEllipse($dotBrush, $x - 22, 490, 44, 44)
}

$bitmap.Save((Join-Path $outputDirectory "app-icon.png"), [System.Drawing.Imaging.ImageFormat]::Png)

$sizes = @(256, 128, 64, 48, 32, 16)
$pngs = @()
foreach ($targetSize in $sizes) {
    $scaled = New-Object System.Drawing.Bitmap $targetSize, $targetSize
    $scaledGraphics = [System.Drawing.Graphics]::FromImage($scaled)
    $scaledGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $scaledGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $scaledGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $scaledGraphics.DrawImage($bitmap, 0, 0, $targetSize, $targetSize)
    $memory = New-Object System.IO.MemoryStream
    $scaled.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += ,@($targetSize, $memory.ToArray())
    $scaledGraphics.Dispose()
    $scaled.Dispose()
    $memory.Dispose()
}

$icoPath = Join-Path $outputDirectory "app-icon.ico"
$stream = [System.IO.File]::Open($icoPath, [System.IO.FileMode]::Create)
$writer = New-Object System.IO.BinaryWriter $stream
$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]$pngs.Count)
$offset = 6 + (16 * $pngs.Count)
foreach ($entry in $pngs) {
    $dimension = [byte]$(if ($entry[0] -eq 256) { 0 } else { $entry[0] })
    $writer.Write($dimension)
    $writer.Write($dimension)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]32)
    $writer.Write([UInt32]$entry[1].Length)
    $writer.Write([UInt32]$offset)
    $offset += $entry[1].Length
}
foreach ($entry in $pngs) {
    $writer.Write($entry[1])
}
$writer.Flush()
$writer.Dispose()
$stream.Dispose()

$graphics.Dispose()
$bitmap.Dispose()
$outer.Dispose()
$background.Dispose()
$shinePath.Dispose()
$shine.Dispose()
$whitePen.Dispose()
$cyanPen.Dispose()
$topHead.Dispose()
$bottomHead.Dispose()
$dotBrush.Dispose()

Write-Output "Generated:"
Write-Output "  $outputDirectory\app-icon.png"
Write-Output "  $icoPath"
