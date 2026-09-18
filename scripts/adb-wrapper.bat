@echo off
rem Windows twin of the corral adb wrapper: the gate rules and the identity walk live in the CLI,
rem so batch never duplicates them. Install with corral on PATH (see corral.bat).
setlocal
if "%CORRAL_REAL_ADB%"=="" set CORRAL_REAL_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
where corral >nul 2>nul
if errorlevel 1 (
  echo corral: CLI not on PATH - failing closed ^(the Windows wrapper delegates the gate to corral^)
  exit /b 1
)
call corral gate -- %*
exit /b %errorlevel%
