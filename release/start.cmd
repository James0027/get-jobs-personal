@echo off
setlocal
cd /d "%~dp0"
title Get Jobs

rem Refuse to start when 8888 is already used by another service.
rem Exit 10 = free, 0 = this Get Jobs instance, 11/12 = occupied by something else.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-port.ps1" -Port 8888
set "PORT_STATE=%ERRORLEVEL%"

if "%PORT_STATE%"=="0" (
    echo Get Jobs is already running at http://localhost:8888
    start "" http://localhost:8888
    if /i not "%~1"=="--yes" pause
    exit /b 0
)
if not "%PORT_STATE%"=="10" (
    echo [ERROR] Port 8888 is used by another service. Get Jobs was not started.
    echo         The check above shows the actual response. Free port 8888 and retry.
    if /i not "%~1"=="--yes" pause
    exit /b 1
)

rem JDK 21 required. Uses JAVA_HOME when valid, otherwise auto-detects a local JDK 21.
set "FOUND_JDK="
for /f "delims=" %%D in ('powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0find-jdk.ps1"') do set "FOUND_JDK=%%D"
if not defined FOUND_JDK goto :nojdk
set "JAVA_HOME=%FOUND_JDK%"
:havejdk
set "PATH=%JAVA_HOME%\bin;%PATH%"

powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$v = & '%JAVA_HOME%\bin\java.exe' -version 2>&1 | Select-Object -First 1; if($v -notmatch '21\.'){ Write-Output ('[ERROR] JDK 21 required, found: '+$v); exit 1 }; exit 0"
if errorlevel 1 goto :nojdk

rem Playwright needs Node.js. Honor an existing value, else take node.exe from PATH.
if defined PLAYWRIGHT_NODEJS_PATH goto :havenode
for /f "delims=" %%N in ('where node.exe 2^>nul') do if not defined PLAYWRIGHT_NODEJS_PATH set "PLAYWRIGHT_NODEJS_PATH=%%N"
:havenode
if not defined PLAYWRIGHT_NODEJS_PATH (
    echo [WARN] Node.js was not found in PATH. Browser automation needs Node.js 20+.
    echo        Install Node.js or set PLAYWRIGHT_NODEJS_PATH and run again.
)

rem Safe defaults. Change these only through a deliberate, documented configuration review.
set "SAFE_OPTS=-Dapp.scheduling.enabled=false -Dapp.browser.auto-start=false -Dapp.browser.open-ui=false -Dboss.read-only-check-mode=true"
set "SAFE_OPTS=%SAFE_OPTS% -Dserver.address=127.0.0.1 -Dplaywright.cli.dir=%~dp0patchright-driver -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

echo ============================================
echo   Get Jobs 2.0.1
echo   UI / API : http://localhost:8888 (loopback only)
echo   Safe defaults: scheduling=off, auto-browser=off, read-only=on
echo   Close this console window titled "Get Jobs" to stop.
echo ============================================
echo.

rem Open the browser only after this instance answers its identity endpoint.
start "" /b powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "for($i=0;$i -lt 120;$i++){ try{ $r=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8888/api/login-only/status' -TimeoutSec 2; if($r.StatusCode -eq 200){ Start-Process 'http://localhost:8888'; exit 0 } }catch{}; Start-Sleep -Milliseconds 1000 }"

"%JAVA_HOME%\bin\java.exe" %SAFE_OPTS% -jar "%~dp0get_jobs-2.0.1.jar"
echo.
echo Get Jobs exited with code %ERRORLEVEL%.
if /i not "%~1"=="--yes" pause
exit /b %ERRORLEVEL%

:nojdk
echo [ERROR] JDK 21 was not found or is not version 21. Set JAVA_HOME to a JDK 21 and retry.
if /i not "%~1"=="--yes" pause
exit /b 1
