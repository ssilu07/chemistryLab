@echo off
setlocal
cd /d "%~dp0"
echo ========================================================
echo   Chemistry Lab - Automated Build ^& Release Runner
echo ========================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release.ps1" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Release failed with error code %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)
pause
