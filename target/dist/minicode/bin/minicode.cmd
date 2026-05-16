@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
set "JAR_PATH=%APP_HOME%\lib\minicode.jar"
set "JAVA_EXE=java"

if defined JAVA_HOME21 (
  if exist "%JAVA_HOME21%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME21%\bin\java.exe"
) else if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not exist "%JAR_PATH%" (
  echo MiniCode launcher error: jar not found: "%JAR_PATH%" 1>&2
  exit /b 1
)

"%JAVA_EXE%" -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%
