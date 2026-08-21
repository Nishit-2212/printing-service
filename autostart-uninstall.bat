@echo off
REM Stop launching Printly at login. Also clears the pre-rename Print Bridge entry.
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v Printly /f
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v PrintBridge /f >nul 2>&1
echo Autostart removed.
