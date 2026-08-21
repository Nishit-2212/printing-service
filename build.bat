@echo off
REM Compile the bridge into build\dist\printly.jar. Needs nothing but a JDK 21+.
REM
REM Third-party jars are vendored in lib\ rather than fetched: there is no Maven or
REM Gradle here on purpose, and a warehouse build must not depend on the network.
setlocal enabledelayedexpansion
cd /d "%~dp0"

if exist build\classes rmdir /s /q build\classes
if exist build\dist rmdir /s /q build\dist
mkdir build\classes
mkdir build\dist\lib

copy /y lib\*.jar build\dist\lib\ >nul

REM javac wants a semicolon-separated classpath; the jar manifest wants space-separated
REM relative entries with forward slashes. Both are derived from lib\, so upgrading a
REM jar needs no edit here. The variable is not named CLASSPATH on purpose — that name
REM is an environment variable javac would also pick up.
set "CLASSPATH_ARG="
set "MANIFEST_CP="
for %%J in (lib\*.jar) do (
  set "CLASSPATH_ARG=!CLASSPATH_ARG!lib\%%~nxJ;"
  set "MANIFEST_CP=!MANIFEST_CP!lib/%%~nxJ "
)

(for /f "delims=" %%F in ('dir /s /b src\main\java\*.java') do (
  set "SRC=%%F"
  echo "!SRC:\=/!"
)) > build\sources.txt
javac -encoding UTF-8 --release 21 -cp "!CLASSPATH_ARG!" -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1

REM Class-Path is what makes both `java -jar` and the jpackage launcher find PDFBox.
REM Without it the app starts fine and then dies on the first PDF.
> build\manifest.mf echo Class-Path: !MANIFEST_CP!

jar --create --file build\dist\printly.jar ^
    --manifest build\manifest.mf ^
    --main-class com.jagdushah.printly.Main ^
    -C build\classes .
if errorlevel 1 exit /b 1

echo Built build\dist\printly.jar
endlocal
