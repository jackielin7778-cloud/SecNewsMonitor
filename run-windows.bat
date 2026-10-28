@echo off
rem Windows launcher: double-click to run (uses javaw, no console window)
rem Java lookup order:
rem   1. JDK bundled in this folder:  jdk\bin\javaw.exe
rem   2. Any jdk* folder here (e.g. jdk-21.0.9+10\bin\javaw.exe, as extracted from the zip)
rem   3. jdk\<any folder>\bin\javaw.exe (zip extracted one level too deep)
rem   4. SECNEWS_JAVA env var, 5. JAVA_HOME, 6. javaw on PATH
rem The system Java / JAVA_HOME / PATH are never modified.
rem If the Java found is older than 17, the program shows a dialog explaining it.
chcp 65001 >nul
cd /d "%~dp0"

set "JAVAW="
if exist "%~dp0jdk\bin\javaw.exe" set "JAVAW=%~dp0jdk\bin\javaw.exe"
if defined JAVAW goto run

for /d %%D in ("%~dp0jdk*") do if exist "%%D\bin\javaw.exe" set "JAVAW=%%D\bin\javaw.exe"
if defined JAVAW goto run

for /d %%D in ("%~dp0jdk\*") do if exist "%%D\bin\javaw.exe" set "JAVAW=%%D\bin\javaw.exe"
if defined JAVAW goto run

if defined SECNEWS_JAVA if exist "%SECNEWS_JAVA%" set "JAVAW=%SECNEWS_JAVA%"
if defined JAVAW goto run

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if defined JAVAW goto run

where javaw >nul 2>nul
if errorlevel 1 goto nojava
set "JAVAW=javaw"

:run
rem Low-resource settings: small heap, Serial GC, C1 compiler only
start "" "%JAVAW%" -Xms16m -Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Dfile.encoding=UTF-8 -jar "%~dp0SecNewsMonitor.jar"
exit /b 0

:nojava
echo Java not found.
echo Put JDK 17+ into "%~dp0jdk" so that "%~dp0jdk\bin\javaw.exe" exists.
pause
exit /b 1
