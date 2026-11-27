@echo off
cd /d "%~dp0"
python upgrade.py
if errorlevel 1 pause
