@echo off
rem Starts the OpenMW multiplayer server from this folder. Anything you add is passed on: start-server.bat --help
cd /d "%~dp0"
openmw-mp-server.exe %*
