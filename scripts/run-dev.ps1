$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & mvn -q package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败"
    }
    & java -jar (Join-Path $projectRoot "target\syncplugins.jar")
} finally {
    Pop-Location
}

