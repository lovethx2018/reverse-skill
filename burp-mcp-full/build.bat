@echo off
setlocal enabledelayedexpansion

:: BurpSuite MCP Full Control - Windows Build Script
:: Produces: build\libs\burp-mcp-full.jar (fat jar, Burp-loadable)

call "%~dp0gradlew.bat" jar
exit /b %errorlevel%
