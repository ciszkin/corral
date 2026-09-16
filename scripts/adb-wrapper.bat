@echo off
rem Windows twin of adb-wrapper.sh — same API check, same teaching text.
rem ponytail: kept compiling, exercised when Windows use becomes real; generate both from one template if they drift.
setlocal EnableDelayedExpansion
if "%CORRAL_URL%"=="" set CORRAL_URL=http://127.0.0.1:8798
if defined CORRAL_REAL_ADB (set REAL_ADB=%CORRAL_REAL_ADB%) else set REAL_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
rem owner: CORRAL_OWNER > user:$USERNAME
rem (no proc ancestry tier on Windows; set CORRAL_OWNER explicitly in containers/CI)
if defined CORRAL_OWNER (set OWNER=%CORRAL_OWNER%) else set OWNER=user:%USERNAME%

set SERIAL=%ANDROID_SERIAL%
set SUB=

:parse
if "%~1"=="" goto parsed
set ARG=%~1
if "%ARG%"=="-s" (
  set SERIAL=%~2
  shift
) else if "%ARG:~0,2%"=="-s" (
  set SERIAL=!ARG:~2!
) else if "%ARG:~0,1%"=="-" (
  rem global flag (-v, --version, --help, -P host...) is not a subcommand
) else if not defined SUB set SUB=%ARG%
shift
goto parse

:parsed
if defined SERIAL (
  curl -sf --max-time 5 "%CORRAL_URL%/api/check?serial=%SERIAL%&owner=%OWNER%" > "%TEMP%\corral_check.json" 2>nul
  if errorlevel 1 (
    echo corral: daemon unreachable at %CORRAL_URL% - failing closed ^(no daemon, no leasing^)
    exit /b 1
  )
  findstr /C:"\"allowed\":true" "%TEMP%\corral_check.json" >nul
  if errorlevel 1 (
    type "%TEMP%\corral_check.json"
    echo Lease it first:  corral lease -- ^<cmd^>
    exit /b 1
  )
) else if defined SUB (
  echo %SUB%| findstr /B /I "devices start-server kill-server track-devices version help" >nul
  if errorlevel 1 (
    echo corral: no device pinned ^(no -s, no ANDROID_SERIAL^)
    echo Lease it first:  corral lease -- ^<cmd^>   ^|   ser=$(corral claim^) ^&^& ANDROID_SERIAL=%%ser%% cmd
    exit /b 1
  )
)
"%REAL_ADB%" %*
exit /b %errorlevel%