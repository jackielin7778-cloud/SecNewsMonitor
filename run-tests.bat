@echo off
rem Run automated tests (Windows). Requires JDK 17+. No network or GUI needed.
chcp 65001 >nul
cd /d "%~dp0"

set "JAVAC=javac"
set "JAVA=java"
for /d %%D in ("%~dp0jdk*") do if exist "%%D\bin\javac.exe" set "JAVAC=%%D\bin\javac.exe"& set "JAVA=%%D\bin\java.exe"

if exist build\classes rmdir /s /q build\classes
if exist build\test-classes rmdir /s /q build\test-classes
mkdir build\classes build\test-classes
"%JAVAC%" --release 8 -encoding UTF-8 -d build\classes src\com\secnews\Launcher.java 2>nul
dir /s /b src\*.java | findstr /v /i "Launcher.java" > build\sources.txt
"%JAVAC%" --release 17 -encoding UTF-8 -d build\classes @build\sources.txt || goto :err
dir /s /b test\*.java > build\test-sources.txt
"%JAVAC%" --release 17 -encoding UTF-8 -cp build\classes -d build\test-classes @build\test-sources.txt || goto :err

set FAILED=0
for %%T in (SelfTest V11Test V12Test V13Test V14Test V15Test ResetTest) do (
  echo == %%T
  "%JAVA%" -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -Xmx96m -cp build\classes;build\test-classes com.secnews.%%T || set FAILED=1
)
if "%FAILED%"=="1" goto :err
echo All tests passed
pause
exit /b 0
:err
echo Tests FAILED
pause
exit /b 1
