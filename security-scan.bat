@echo off
rem Security static scan (Windows): SpotBugs + Find Security Bugs on SecNewsMonitor.jar
rem First run downloads the tools into tools\ (about 15 MB, needs internet). Later runs work offline.
rem Report: build\security-scan.html (open in a browser)
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "SB_VER=4.9.2"
set "SB_SHA256=2ac6f163c266d2f7c11cc89d80d07ccad6755ac468cee20ca0b1e4217e567548"
set "FSB_VER=1.14.0"
set "SB_URL=https://github.com/spotbugs/spotbugs/releases/download/%SB_VER%/spotbugs-%SB_VER%.zip"
set "FSB_URL=https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/%FSB_VER%/findsecbugs-plugin-%FSB_VER%.jar"
set "SB_DIR=tools\spotbugs-%SB_VER%"
set "FSB_JAR=tools\findsecbugs-plugin-%FSB_VER%.jar"

rem ---- Java: jdk* folder here, then JAVA_HOME, then PATH ----
set "JAVA="
for /d %%D in ("%~dp0jdk*") do if exist "%%D\bin\java.exe" set "JAVA=%%D\bin\java.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA (
  where java >nul 2>nul && set "JAVA=java"
)
if not defined JAVA ( echo Java not found ^(Java 11+ required^). & pause & exit /b 1 )

if not exist tools mkdir tools
if not exist "%SB_DIR%\lib\spotbugs.jar" (
  echo Downloading SpotBugs %SB_VER% ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
    "Invoke-WebRequest -UseBasicParsing '%SB_URL%' -OutFile 'tools\spotbugs.zip';" ^
    "$h=(Get-FileHash 'tools\spotbugs.zip' -Algorithm SHA256).Hash.ToLower();" ^
    "if ($h -ne '%SB_SHA256%') { Remove-Item 'tools\spotbugs.zip'; Write-Host ('SHA-256 mismatch: ' + $h); exit 1 }" ^
    "Expand-Archive -Force 'tools\spotbugs.zip' 'tools'; Remove-Item 'tools\spotbugs.zip'"
  if errorlevel 1 ( echo SpotBugs download failed. & pause & exit /b 1 )
)
if not exist "%FSB_JAR%" (
  echo Downloading Find Security Bugs %FSB_VER% ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
    "Invoke-WebRequest -UseBasicParsing '%FSB_URL%' -OutFile '%FSB_JAR%.tmp';" ^
    "$want=(Invoke-WebRequest -UseBasicParsing '%FSB_URL%.sha1').Content.Trim().Substring(0,40).ToLower();" ^
    "$h=(Get-FileHash '%FSB_JAR%.tmp' -Algorithm SHA1).Hash.ToLower();" ^
    "if ($h -ne $want) { Remove-Item '%FSB_JAR%.tmp'; Write-Host 'SHA-1 mismatch'; exit 1 }" ^
    "Move-Item -Force '%FSB_JAR%.tmp' '%FSB_JAR%'"
  if errorlevel 1 ( echo Find Security Bugs download failed. & pause & exit /b 1 )
)

if not exist SecNewsMonitor.jar ( echo SecNewsMonitor.jar not found. Run build.bat first. & pause & exit /b 1 )
if not exist build mkdir build
echo Scanning SecNewsMonitor.jar ...
"%JAVA%" -Xmx1g -jar "%SB_DIR%\lib\spotbugs.jar" -textui -effort:max -low -pluginList "%FSB_JAR%" -onlyAnalyze com.secnews.- -html=build\security-scan.html -xml:withMessages=build\security-scan.xml SecNewsMonitor.jar
echo.
echo Done. Report: %~dp0build\security-scan.html
echo Static analysis has false positives; review each item.
start "" "%~dp0build\security-scan.html"
pause
