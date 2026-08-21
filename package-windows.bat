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
setlocal
cd /d "%~dp0"

call build.bat || exit /b 1

if exist build\installer rmdir /s /q build\installer
mkdir build\installer

jpackage ^
  --type msi ^
  --name Printly ^
  --app-version 1.0.0 ^
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
echo.
echo Installer written to build\installer
echo After installing, run autostart-install.bat once to launch it at login.
endlocal
