@echo off
rem GTA WI - builds dist\GTAWI.jar + .jad from YOUR GTA III install (see BUILDING.md).
rem   build.bat            release build
rem   build.bat fps        + a small profiler (fps, frame times, memory) above the radar
rem   build.bat god        + profiler, and Claude takes no damage (for poking around)
rem   build.bat rerender   render all sprites again (otherwise they are rendered once and reused)
rem   build.bat clean      delete work\ and dist\ first (a full rebuild from scratch)
setlocal EnableExtensions
cd /d "%~dp0"
set "KIT=%CD%"
set "VER=0.7.4"
set "NAME=GTAWI" & set "TITLE=GTA WI" & set "FPS=0" & set "GOD=0" & set "RERENDER=0" & set "CLEAN=0"
for %%A in (%*) do (
  if /i "%%A"=="fps" (set "FPS=1" & set "NAME=GTAWI_fps" & set "TITLE=GTA WI fps")
  if /i "%%A"=="god" (set "FPS=1" & set "GOD=1" & set "NAME=GTAWI_god" & set "TITLE=GTA WI god")
  if /i "%%A"=="rerender" set "RERENDER=1"
  if /i "%%A"=="clean" set "CLEAN=1"
)
if "%CLEAN%"=="1" (
  if exist work rmdir /s /q work
  if exist dist rmdir /s /q dist
)

rem ---------------------------------------------------------------- tools + config
if not exist config.bat (echo config.bat is missing & exit /b 1)
call config.bat
set "J8=" & set "BL=" & set "FJ="
for /d %%D in ("tools\jdk8u*") do set "J8=%%~fD"
for /d %%D in ("tools\blender-*") do if exist "%%~fD\blender.exe" set "BL=%%~fD\blender.exe"
for /r tools\freej2me %%F in (freej2me.jar) do if exist "%%F" set "FJ=%%F"
set "PG=%KIT%\tools\proguard-7.6.1\lib\proguard.jar"
set "UV="
for /r tools\uv %%F in (uv.exe) do if exist "%%F" set "UV=%%F"
set "UV_CACHE_DIR=%KIT%\tools\uv-cache"
set "UV_PYTHON_INSTALL_DIR=%KIT%\tools\python"
if not defined J8 goto :nosetup
if not defined BL goto :nosetup
if not defined FJ goto :nosetup
if not exist "%PG%" goto :nosetup
if not defined UV goto :nosetup
if not exist "%GTA3%\models\gta3.img" (
  echo GTA III not found in "%GTA3%"
  echo Open config.bat and set GTA3 to the folder that has gta3.exe in it.
  exit /b 1
)

rem ---------------------------------------------------------------- 1-4: game files -> phone resources
echo [1/5] extracting what we need from your GTA III ...
"%UV%" run --quiet pipeline\extract.py "%GTA3%" work\gta3 || goto :fail
echo [2/5] road and pavement graphs ...
"%UV%" run --quiet pipeline\build_paths.py work\gta3 work\paths.json || goto :fail
if "%RERENDER%"=="1" if exist work\sprites\render_ok.txt del work\sprites\render_ok.txt
if exist work\sprites\render_ok.txt (
  echo [3/5] sprites already rendered ^(build.bat rerender to redo them^)
) else (
  echo [3/5] rendering the sprites in Blender ^(a few minutes, the first time only^) ...
  "%BL%" -b --python-exit-code 1 --python pipeline\render_sprites.py -- "%KIT%\work" > work\render_blender.log 2>&1
  if errorlevel 1 (type work\sprites\render_log.txt & echo RENDER FAILED - see work\render_blender.log & exit /b 1)
)
echo [4/5] map + sprites + HUD + music -^> work\res ...
"%UV%" run --quiet pipeline\export.py . work > work\export.log 2>&1 || (type work\export.log & goto :fail)

rem ---------------------------------------------------------------- 5: compile + jar
echo [5/5] compiling %NAME%.jar ...
if exist work\out rmdir /s /q work\out
mkdir work\out\classes work\out\pkg 2>nul
if not exist dist mkdir dist
"%J8%\bin\javac" -nowarn -Xlint:-options -source 1.3 -target 1.3 -encoding UTF-8 -bootclasspath "%J8%\jre\lib\rt.jar" -cp "%FJ%" -d work\out\classes src\*.java || goto :fail
xcopy /e /q /y work\out\classes work\out\pkg\ >nul
xcopy /e /q /y work\res work\out\pkg\ >nul
> work\out\MANIFEST.MF (
  echo MIDlet-1: %TITLE%,/icon.png,WhatIf
  echo MIDlet-Name: %TITLE%
  echo MIDlet-Vendor: GTA WI
  echo MIDlet-Version: %VER%
  echo MIDlet-Icon: /icon.png
  echo MicroEdition-Configuration: CLDC-1.1
  echo MicroEdition-Profile: MIDP-2.0
  echo Nokia-MIDlet-Category: Game
  echo WI-Fps: %FPS%
  echo WI-God: %GOD%
)
pushd work\out\pkg
"%J8%\bin\jar" cfm ..\raw.jar ..\MANIFEST.MF . || (popd & goto :fail)
popd
> work\out\pg.pro (
  echo -injars "%KIT%\work\out\raw.jar"
  echo -outjars "%KIT%\dist\%NAME%.jar"
  echo -libraryjars "%J8%\jre\lib\rt.jar"
  echo -libraryjars "%FJ%"
  echo -microedition
  echo -target 1.3
  echo -keep public class WhatIf
  echo -overloadaggressively
  echo -repackageclasses ''
  echo -allowaccessmodification
  echo -dontusemixedcaseclassnames
  echo -optimizationpasses 3
  echo -dontnote
  echo -dontwarn
)
if exist "dist\%NAME%.jar" del "dist\%NAME%.jar"
"%J8%\bin\java" -jar "%PG%" @work\out\pg.pro > work\out\proguard.log 2>&1 || (type work\out\proguard.log & goto :fail)
"%UV%" run --quiet pipeline\check_cldc.py "dist\%NAME%.jar" "%J8%\bin\javap.exe" || (del "dist\%NAME%.jar" & goto :fail)
for %%A in ("dist\%NAME%.jar") do set "SIZE=%%~zA"
> "dist\%NAME%.jad" (
  echo MIDlet-1: %TITLE%,/icon.png,WhatIf
  echo MIDlet-Jar-Size: %SIZE%
  echo MIDlet-Jar-URL: %NAME%.jar
  echo MIDlet-Name: %TITLE%
  echo MIDlet-Vendor: GTA WI
  echo MIDlet-Version: %VER%
  echo MicroEdition-Configuration: CLDC-1.1
  echo MicroEdition-Profile: MIDP-2.0
  echo WI-Fps: %FPS%
  echo WI-God: %GOD%
)
echo.
echo Done: dist\%NAME%.jar (%SIZE% bytes) + dist\%NAME%.jad
echo Copy both to the phone, or try it on the PC with run.bat.
exit /b 0

:nosetup
echo The build tools are missing - run setup.bat first.
exit /b 1
:fail
echo.
echo BUILD FAILED (see the messages above).
exit /b 1
