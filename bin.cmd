@echo off
setlocal
set "jar=%~dp0target\mylang-1.0-SNAPSHOT.jar"

if not exist "%jar%" (
    echo Build first with: mvn package 1>&2
    exit /b 1
)

java -jar "%jar%" %*
exit /b %errorlevel%
