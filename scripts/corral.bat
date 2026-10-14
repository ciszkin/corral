@echo off
rem corral CLI shim. Resolve java to an absolute path before running it: cmd resolves a bare `java`
rem from the CURRENT DIRECTORY before PATH, so a planted java.exe/java.bat there would hijack the
rem CLI (and, when the adb wrapper delegates here, the gate). CORRAL_JAVA wins, then
rem %JAVA_HOME%\bin\java.exe, then the first java on PATH entries only.
setlocal
set "JAVA=%CORRAL_JAVA%"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for %%d in ("%PATH:;=" "%") do if not defined JAVA call :find_java "%%~d"
if not defined JAVA (
  echo corral: java not found ^(set CORRAL_JAVA or JAVA_HOME, or put java on PATH^) - failing closed
  exit /b 1
)
if defined CORRAL_JAR (
  call "%JAVA%" -cp "%CORRAL_JAR%" dev.corral.CorralCli %*
) else (
  call "%JAVA%" -cp "%USERPROFILE%\.corral\corral-daemon-all.jar" dev.corral.CorralCli %*
)
exit /b %errorlevel%

:find_java
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
for %%f in (com exe bat cmd) do if not defined JAVA if exist "%~1\java.%%f" set "JAVA=%~1\java.%%f"
exit /b 0
