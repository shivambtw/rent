@echo off
rem Compile RentEase.java and package it as dist\rentease.jar (Windows)
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build
if not exist dist mkdir dist
javac -encoding UTF-8 -d build src\RentEase.java || exit /b 1
echo Main-Class: RentEase> build\manifest.txt
jar cfm dist\rentease.jar build\manifest.txt -C build . || exit /b 1
echo Built dist\rentease.jar
