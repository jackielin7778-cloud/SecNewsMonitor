@echo off
rem Rebuild from source (Windows). Requires JDK 17+.
rem Launcher is compiled for Java 8 so old Java can still show a "version too old" dialog.
chcp 65001 >nul
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
javac --release 8 -encoding UTF-8 -d build\classes src\com\secnews\Launcher.java || goto :err
dir /s /b src\*.java | findstr /v /i "Launcher.java" > build\sources.txt
javac --release 17 -encoding UTF-8 -d build\classes @build\sources.txt || goto :err
(echo Main-Class: com.secnews.Launcher& echo Implementation-Version: 1.7.1)> build\MANIFEST.MF
jar --create --file SecNewsMonitor.jar --manifest build\MANIFEST.MF -C build\classes . || goto :err
echo Done: SecNewsMonitor.jar
pause
exit /b 0
:err
echo Build failed
pause
exit /b 1
