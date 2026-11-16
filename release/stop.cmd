@echo off
setlocal
echo To stop Get Jobs, close the console window titled "Get Jobs".
echo.
echo This script never kills processes and never guesses ownership from a port.
echo Closing that console window lets the application run its normal shutdown hooks.
echo.
if /i "%~1"=="--yes" exit /b 0
pause
