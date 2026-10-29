@echo off
setlocal
cd /d "%~dp0"

rem JDK 21 required. Uses JAVA_HOME when valid, otherwise auto-detects a local JDK 21.
rem No personal absolute paths are hardcoded in this published copy.
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :havejdk
set "JAVA_HOME="
for /d %%D in ("%USERPROFILE%\.jdks\jdk-21*") do if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
for /d %%D in ("%USERPROFILE%\.gradle\jdks\jdk-21*") do if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME goto :nojdk
:havejdk
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem Playwright node bridge: honor an existing value, else take node.exe from PATH.
if defined PLAYWRIGHT_NODEJS_PATH goto :havenode
for /f "delims=" %%N in ('where node.exe 2^>nul') do if not defined PLAYWRIGHT_NODEJS_PATH set "PLAYWRIGHT_NODEJS_PATH=%%N"
:havenode

echo ============================================
echo   Get Jobs starting
echo   Frontend: http://localhost:6866
echo   API:      http://localhost:8888
echo   Stop with ֹͣ.cmd (double click) or close this window
echo ============================================
echo.

if exist "build\libs\get_jobs-2.0.1.jar" goto :fast

echo Jar not found, running Gradle bootRun (first run takes 1-2 minutes)...
call gradlew.bat bootRun
goto :done

:fast
echo Starting from packaged jar (~10s)...
"%JAVA_HOME%\bin\java.exe" -Dplaywright.cli.dir="%CD%\build\patchright-driver" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "build\libs\get_jobs-2.0.1.jar"
goto :done

:nojdk
echo [ERROR] JDK 21 not found. Install JDK 21 and set JAVA_HOME, then run again.
pause
exit /b 1

:done
echo.
echo Process exited.
pause

