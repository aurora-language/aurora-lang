@echo off
setlocal
set JAR_PATH=%~dp0build\libs\aurora-0.1.0-alpha.jar
set LIB_PATH=%~dp0aurora\lib

if not exist "%JAR_PATH%" (
    echo [ERROR] Aurora JAR not found. Please run 'mvn package' first.
    exit /b 1
)

java -jar "%JAR_PATH%" %*
