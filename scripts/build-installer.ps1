param(
    [switch]$Portable,
    [string]$WixBin = $env:WIX_BIN
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & mvn clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建或测试失败"
    }

    $inputDirectory = Join-Path $projectRoot "target\jpackage-input"
    New-Item -ItemType Directory -Force -Path $inputDirectory | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $inputDirectory "lib") | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "target\syncplugins.jar") `
        -Destination (Join-Path $inputDirectory "syncplugins.jar") -Force
    Copy-Item -Path (Join-Path $projectRoot "target\lib\*.jar") `
        -Destination (Join-Path $inputDirectory "lib") -Force

    $javaProperties = & java -XshowSettings:properties -version 2>&1
    $javaHomeLine = $javaProperties | Where-Object { $_ -match '^\s*java\.home = (.+)$' } | Select-Object -First 1
    if (-not $javaHomeLine -or $javaHomeLine -notmatch '^\s*java\.home = (.+)$') {
        throw "无法确定 JDK 安装目录"
    }
    $javaHome = $Matches[1].Trim()
    $jpackage = Join-Path $javaHome "bin\jpackage.exe"
    if (-not (Test-Path -LiteralPath $jpackage)) {
        throw "未找到 jpackage，请使用完整的 JDK 21（而不是 JRE）构建"
    }

    $dist = Join-Path $projectRoot "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    if (-not $Portable -and $WixBin) {
        $env:PATH = $WixBin + ";" + $env:PATH
    }
    $commonArguments = @(
        "--name", "SyncPlugins",
        "--app-version", "1.2.6",
        "--vendor", "SyncPlugins",
        "--description", "Automatic GitHub synchronization for a Git worktree",
        "--input", $inputDirectory,
        "--main-jar", "syncplugins.jar",
        "--main-class", "com.syncplugins.Main",
        "--dest", $dist,
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dstdout.encoding=UTF-8",
        "--java-options", "-Dstderr.encoding=UTF-8",
        "--java-options", "-Xms32m",
        "--java-options", "-Xmx256m",
        "--java-options", "-XX:MaxMetaspaceSize=128m",
        "--java-options", "-XX:+UseSerialGC"
    )

    if ($Portable) {
        & $jpackage "--type" "app-image" @commonArguments
    } else {
        & $jpackage "--type" "exe" `
            "--win-per-user-install" `
            "--win-shortcut" `
            "--win-menu" `
            "--win-menu-group" "SyncPlugins" @commonArguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw @"
jpackage 构建失败。生成 EXE 安装包需要 WiX Toolset 3.14 或更高版本；
请安装 WiX，或通过 -WixBin 传入包含 candle.exe 和 light.exe 的目录。
如果只需免安装测试，可运行：.\scripts\build-installer.ps1 -Portable
"@
    }
} finally {
    Pop-Location
}

