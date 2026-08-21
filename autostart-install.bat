@echo off
REM Launch Printly at login for the current user.
REM Pass the exe path as an argument if you installed somewhere other than the default.
setlocal
set "EXE=%~1"
if "%EXE%"=="" set "EXE=%LOCALAPPDATA%\Printly\Printly.exe"

if not exist "%EXE%" (
  echo Could not find "%EXE%".
  echo Run this script again with the full path, e.g.:
  echo    autostart-install.bat "C:\Program Files\Printly\Printly.exe"
  exit /b 1
)

reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v Printly /t REG_SZ /d "\"%EXE%\"" /f
if errorlevel 1 exit /b 1

REM Drop the pre-rename autostart entry. Left behind it would start the old Print Bridge
REM alongside Printly, and both would race for port 9110 and for the printers' single
REM accepted connection — the loser silently prints nothing.
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v PrintBridge /f >nul 2>&1

echo Printly will start at login: %EXE%
endlocal
