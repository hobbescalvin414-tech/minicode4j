$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appHome = Split-Path -Parent $scriptDir
$jarPath = Join-Path $appHome "lib\minicode.jar"
$tsMain = Join-Path $appHome "ts-cli\src\main.js"
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

if ($args.Count -gt 0 -and $args[0] -eq "snake") {
    if ($args.Count -gt 1) {
        & $javaExe -jar $jarPath --snake @($args[1..($args.Count - 1)])
    } else {
        & $javaExe -jar $jarPath --snake
    }
    exit $LASTEXITCODE
}

if ($args.Count -gt 0 -and $args[0] -in @("--tty", "--help", "-h", "--version", "--snake", "session", "--fork")) {
    if ($args[0] -eq "--tty") {
        if ($args.Count -gt 1) {
            & $javaExe -jar $jarPath @($args[1..($args.Count - 1)])
        } else {
            & $javaExe -jar $jarPath
        }
    } else {
        & $javaExe -jar $jarPath @args
    }
    exit $LASTEXITCODE
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error 'MiniCode launcher error: Node.js not found in PATH. Install Node.js 20+ and retry, or run "minicode --tty" for the Java TTY fallback.'
    exit 1
}

if (-not (Test-Path -LiteralPath $tsMain -PathType Leaf)) {
    Write-Error "MiniCode launcher error: TS frontend entry not found: $tsMain"
    exit 1
}

& node $tsMain --real @args
exit $LASTEXITCODE
