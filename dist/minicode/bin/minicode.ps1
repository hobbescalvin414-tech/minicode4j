$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appHome = Split-Path -Parent $scriptDir
$jarPath = Join-Path $appHome "lib\minicode.jar"
$javaExe = "java"

if ($env:JAVA_HOME21) {
    $candidate = Join-Path $env:JAVA_HOME21 "bin\java.exe"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $javaExe = $candidate
    }
} elseif ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $javaExe = $candidate
    }
}

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    Write-Error "MiniCode launcher error: jar not found: $jarPath"
    exit 1
}

& $javaExe -jar $jarPath @args
exit $LASTEXITCODE
