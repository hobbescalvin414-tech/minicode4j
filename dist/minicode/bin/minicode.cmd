@echo off
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
set "JAR_PATH=%APP_HOME%\lib\minicode.jar"
set "TS_MAIN=%APP_HOME%\ts-cli\src\main.js"
set "JAVA_EXE=java"
set "NODE_EXE=node"
set "FORCE_JAVA=0"

if defined JAVA_HOME21 (
  if exist "%JAVA_HOME21%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME21%\bin\java.exe"
) else if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not exist "%JAR_PATH%" (
  echo MiniCode launcher error: jar not found: "%JAR_PATH%" 1>&2
  exit /b 1
)

if /I "%~1"=="--tty" (
  shift
  goto RUN_JAVA_ARGS
)

if /I "%~1"=="--help" (
  goto RUN_JAVA_ORIGINAL_ARGS
)

if /I "%~1"=="-h" (
  goto RUN_JAVA_ORIGINAL_ARGS
)

if /I "%~1"=="--version" (
  goto RUN_JAVA_ORIGINAL_ARGS
)

if /I "%~1"=="--snake" (
  goto RUN_JAVA_ORIGINAL_ARGS
)

if /I "%~1"=="snake" (
  shift
  "%JAVA_EXE%" -jar "%JAR_PATH%" --snake %*
  exit /b %ERRORLEVEL%
)

if /I "%~1"=="session" (
  goto RUN_JAVA_ORIGINAL_ARGS
)

if not "%~1"=="" if /I "%~1"=="--fork" set "FORCE_JAVA=1"

if "%FORCE_JAVA%"=="1" (
  goto RUN_JAVA_ORIGINAL_ARGS
)

where node >nul 2>nul
if errorlevel 1 (
  echo MiniCode launcher error: Node.js not found in PATH. Install Node.js 20+ and retry, or run "minicode --tty" for the Java TTY fallback. 1>&2
  exit /b 1
)

if not exist "%TS_MAIN%" (
  echo MiniCode launcher error: TS frontend entry not found: "%TS_MAIN%" 1>&2
  exit /b 1
)

"%NODE_EXE%" "%TS_MAIN%" --real %*
exit /b %ERRORLEVEL%

:RUN_JAVA_ORIGINAL_ARGS
"%JAVA_EXE%" -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%

:RUN_JAVA_ARGS
set "JAVA_ARGS="
:COLLECT_JAVA_ARGS
if "%~1"=="" goto RUN_JAVA_COLLECTED
set "JAVA_ARGS=!JAVA_ARGS! "%~1""
shift
goto COLLECT_JAVA_ARGS

:RUN_JAVA_COLLECTED
"%JAVA_EXE%" -jar "%JAR_PATH%" !JAVA_ARGS!
exit /b %ERRORLEVEL%
