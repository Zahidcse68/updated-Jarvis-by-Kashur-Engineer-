@echo off
title MARK LIV Launcher
cd /d "%~dp0"

echo ===================================================
echo   Starting MARK LIV (JARVIS AI Assistant)...
echo ===================================================

set PYTHONIOENCODING=utf-8

if exist ".venv\Scripts\python.exe" (
    .venv\Scripts\python.exe main.py
) else (
    python main.py
)

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Application closed with an error (Code: %ERRORLEVEL%).
    echo If this is your first time running, make sure to run: python setup.py
    pause
)
