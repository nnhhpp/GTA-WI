@echo off
rem Plays dist\GTAWI.jar on the PC in FreeJ2ME (240x320, window x2). run.bat fps / god for those builds.
rem FreeJ2ME keys: arrows = 2/4/6/8, Enter = 5, digit keys = 0-9, E = *, R = #, Q or W = soft key (pause).
setlocal
cd /d "%~dp0"
set "J8=" & set "FJ="
for /d %%D in ("tools\jdk8u*") do set "J8=%%~fD"
for /r tools\freej2me %%F in (freej2me.jar) do if exist "%%F" set "FJ=%%F"
if not defined J8 (echo Run setup.bat first. & exit /b 1)
set "NAME=GTAWI"
if /i "%~1"=="fps" set "NAME=GTAWI_fps"
if /i "%~1"=="god" set "NAME=GTAWI_god"
if not exist "dist\%NAME%.jar" (echo dist\%NAME%.jar is missing - run build.bat %~1 first. & exit /b 1)
set "JARURL=%CD%\dist\%NAME%.jar"
set "JARURL=file:///%JARURL:\=/%"
start "" "%J8%\bin\javaw" -cp "%FJ%" org.recompile.freej2me.FreeJ2ME "%JARURL%" 240 320 2
