@echo off
rem Start the WLJS Notebook with symjascript as its kernel, for manual testing (Windows).
rem   src\test\wljs\start-wljs.cmd           start with the binaries as they are
rem   src\test\wljs\start-wljs.cmd --build   rebuild symja (parser, core) and the console first
rem Environment: SYMJA_DIR, WLJS_NOTEBOOK_DIR (default: checkouts side by side with this one).
rem Close the window, or Ctrl-C, to stop; check Task Manager for a leftover java.exe kernel.
setlocal
set "CONSOLE_DIR=%~dp0..\..\.."
for %%I in ("%CONSOLE_DIR%") do set "CONSOLE_DIR=%%~fI"
for %%I in ("%CONSOLE_DIR%\..\..") do set "GIT_ROOT=%%~fI"
if not defined SYMJA_DIR set "SYMJA_DIR=%GIT_ROOT%\symja_android_library"
if not defined WLJS_NOTEBOOK_DIR set "WLJS_NOTEBOOK_DIR=%GIT_ROOT%\wljs-notebook"
set "SYMJASCRIPT=%CONSOLE_DIR%\target\appassembler\bin\symjascript.bat"

if "%~1"=="--build" (
  pushd "%SYMJA_DIR%\symja_android_library" || exit /b 1
  call mvn -o -q install -pl matheclipse-parser,matheclipse-core -DskipTests || exit /b 1
  popd
  pushd "%CONSOLE_DIR%" || exit /b 1
  call mvn -o -q package -DskipTests || exit /b 1
  popd
)

if not exist "%SYMJASCRIPT%" ( echo no %SYMJASCRIPT% - run with --build & exit /b 1 )
if not exist "%WLJS_NOTEBOOK_DIR%\Packages\CSockets\Kernel\Symja.wl" (
  echo no Symja adapter in %WLJS_NOTEBOOK_DIR% - check out the symja-backend branch & exit /b 1 )

echo Starting the notebook (about 90 s); open http://127.0.0.1:20560 once it says "Open http"
cd /d "%WLJS_NOTEBOOK_DIR%"
call "%SYMJASCRIPT%" -script Scripts\start.wls
