@echo off
setlocal
if not defined JAVA_HOME (
  for /d %%d in ("C:\Program Files\Microsoft\jdk-21*") do set "JAVA_HOME=%%d"
)
if not defined JAVA_HOME (
  echo JAVA_HOME is not set. Install JDK 21 and try again.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call gradlew.bat runClient %*
