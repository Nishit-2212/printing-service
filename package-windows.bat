@echo off
REM Build a self-contained Windows installer with a bundled JRE.
REM Pack PCs then need no Java installed at all.
REM
REM The --add-modules list is derived, not guessed — regenerate it after changing the
REM vendored jars in lib\ with:
REM
REM   jdeps --multi-release 21 --print-module-deps --ignore-missing-deps ^
REM         build\dist\printly.jar lib\*.jar
REM
REM java.logging is there for commons-logging, which PDFBox routes through. java.xml
REM is not listed because java.desktop already requires it transitively.
REM
REM Heap: PDFBox rasterizes, and an A4 sheet at 300dpi is ~35MB of ARGB before fonts.
REM Two document-lane threads render concurrently, so the old 128m ceiling OOMs on
REM invoices while working fine on 4x6 labels.
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Called by full path, not bare name: a machine with NoDefaultCurrentDirectoryInExePath
REM set (common under some group policies) will not search the current directory, and the
REM bare name fails with "'build.bat' is not recognized".
call "%~dp0build.bat" || exit /b 1

if exist build\installer rmdir /s /q build\installer
mkdir build\installer

REM Fixed across every build on purpose: this is what lets Windows Installer treat a
REM new --app-version as an upgrade of the old one instead of a conflicting product
REM with the same name. Never regenerate it.
set "UPGRADE_UUID=bcab9fe6-fbdd-4ff0-a1fc-bf9972911158"

REM VERSION always holds the version this build is about to consume. On success it is
REM overwritten with the next patch bump, so a plain rebuild-and-install never collides
REM with what's already on the machine and nobody has to remember to touch this file.
REM To jump to a specific version (a new minor/major), just edit VERSION by hand first.
if not exist "%~dp0VERSION" echo 1.0.0>"%~dp0VERSION"
set /p APP_VERSION=<"%~dp0VERSION"

jpackage ^
  --type msi ^
  --name Printly ^
  --app-version %APP_VERSION% ^
  --win-upgrade-uuid %UPGRADE_UUID% ^
  --vendor "Jagdushah" ^
  --description "Local TSPL print service" ^
  --input build\dist ^
  --main-jar printly.jar ^
  --main-class com.jagdushah.printly.Main ^
  --add-modules java.base,java.desktop,java.logging,jdk.httpserver ^
  --java-options "-Xms16m -Xmx512m" ^
  --win-menu ^
  --win-shortcut ^
  --win-per-user-install ^
  --win-dir-chooser ^
  --dest build\installer

if errorlevel 1 exit /b 1

for /f "tokens=1,2,3 delims=." %%a in ("%APP_VERSION%") do (
    set /a "NEXT_PATCH=%%c+1"
    (echo %%a.%%b.!NEXT_PATCH!)>"%~dp0VERSION"
)

echo.
echo Built version %APP_VERSION% - installer written to build\installer
echo After installing, run autostart-install.bat once to launch it at login.
endlocal
