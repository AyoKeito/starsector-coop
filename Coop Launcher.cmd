@echo off
rem Starts the Starsector co-op launcher with the JRE Starsector ships, so nothing has to be
rem installed. Every path is quoted: an install under "Program Files (x86)" has spaces in it.
rem
rem This file lives at <install>\mods\coop\Coop Launcher.cmd, so the install root is two levels up.
setlocal

set "MOD_DIR=%~dp0"
if "%MOD_DIR:~-1%"=="\" set "MOD_DIR=%MOD_DIR:~0,-1%"

pushd "%MOD_DIR%\..\.." || (
    echo Could not find the Starsector folder above "%MOD_DIR%".
    echo The mod has to sit in ^<Starsector^>\mods\coop.
    pause
    exit /b 1
)
set "ROOT=%CD%"
popd

if not exist "%ROOT%\jre\bin\javaw.exe" (
    echo Could not find "%ROOT%\jre\bin\javaw.exe".
    echo.
    echo That is the Java runtime Starsector ships with. If this install uses a replacement JRE
    echo that starts the game from a .bat file, start the launcher by hand instead:
    echo.
    echo    java -cp "%MOD_DIR%\jars\coop-launcher.jar;%MOD_DIR%\jars\coop.jar;%ROOT%\starsector-core\json.jar;%ROOT%\starsector-core\log4j-1.2.9.jar" coop.launcher.CoopLauncherApp
    echo.
    pause
    exit /b 1
)

start "" "%ROOT%\jre\bin\javaw.exe" -cp "%MOD_DIR%\jars\coop-launcher.jar;%MOD_DIR%\jars\coop.jar;%ROOT%\starsector-core\json.jar;%ROOT%\starsector-core\log4j-1.2.9.jar" coop.launcher.CoopLauncherApp
endlocal
