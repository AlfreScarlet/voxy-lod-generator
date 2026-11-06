@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME="
if exist "%ROOT%.tools\jdk-25\bin\java.exe" set "JAVA_HOME=%ROOT%.tools\jdk-25"
for /d %%D in ("%ROOT%.tools\jdk-*" "%ROOT%.tools\amazon-corretto-*") do (
  if exist "%%~fD\bin\java.exe" if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
)
if not defined JAVA_HOME (
  echo No project-local JDK 25 found under %ROOT%.tools 1>&2
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=%ROOT%.gradle-home"
if exist "%ROOT%.tools\gradle-9.5.1\bin\gradle.bat" (
  call "%ROOT%.tools\gradle-9.5.1\bin\gradle.bat" %*
) else if exist "%ROOT%.tools\gradle-8.14\bin\gradle.bat" (
  call "%ROOT%.tools\gradle-8.14\bin\gradle.bat" %*
) else (
  call "%ROOT%gradlew.bat" %*
)
exit /b %ERRORLEVEL%
