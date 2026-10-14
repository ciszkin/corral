@echo off
rem Windows twin of the corral adb wrapper: the gate rules and the identity walk live in the CLI,
rem so batch never duplicates them. Install with corral on PATH (see corral.bat).
setlocal
if "%CORRAL_REAL_ADB%"=="" set CORRAL_REAL_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
rem Resolve the corral CLI from PATH entries only, to an absolute path: `where`/`call` also search
rem the current directory, so a planted corral.bat there could both satisfy the guard and be run as
rem the gate; scanning PATH ourselves and invoking the absolute path closes that.
set "CORRAL_CLI="
for %%d in ("%PATH:;=" "%") do if not defined CORRAL_CLI call :find_cli "%%~d"
if not defined CORRAL_CLI (
  echo corral: CLI not on PATH - failing closed ^(the Windows wrapper delegates the gate to corral^)
  exit /b 1
)
call "%CORRAL_CLI%" gate -- %*
exit /b %errorlevel%

:find_cli
rem %~1 is one PATH entry (quotes stripped): skip empty, ".", and non-absolute entries.
set "e=%~1"
if "%e%"=="" exit /b 0
if "%e%"=="." exit /b 0
set "abs="
if "%e:~1,1%"==":" if "%e:~2,1%"=="\" set "abs=1"
if "%e:~1,1%"==":" if "%e:~2,1%"=="/" set "abs=1"
if "%e:~0,2%"=="\\" set "abs=1"
if not defined abs exit /b 0
rem cmd's default PATHEXT order (.com;.exe;.bat;.cmd); first hit wins like PATH lookup.
for %%f in (com exe bat cmd) do if not defined CORRAL_CLI if exist "%~1\corral.%%f" set "CORRAL_CLI=%~1\corral.%%f"
exit /b 0
