@echo off
rem Builds and installs the Markdown Light Preview plugin into the dev IDE sandbox.
rem Also re-enables the Compose UI plugin (Jewel) in the sandbox if it was trimmed.
rem Run this with the dev IDE closed: the IDE locks the plugin jar and rewrites
rem disabled_plugins.txt on exit.
setlocal

set "REPO=D:\intellij-community"
set "BAZEL_TARGET=//custom-plugins/md-light-preview:md_light_preview"
set "JAR=%REPO%\out\bazel-bin\custom-plugins\md-light-preview\md_light_preview.jar"
set "PLUGIN_ID=com.ulasay.md-light-preview"
rem The sandbox path is fixed by the dev vmoptions:
set "SANDBOX=%REPO%\out\dev-data\idea_community\config\plugins"
set "DEST=%SANDBOX%\%PLUGIN_ID%\lib"
set "DISABLED=%REPO%\out\dev-data\idea_community\config\disabled_plugins.txt"

tasklist /FI "IMAGENAME eq idea.exe" | find /I "idea.exe" >nul
if not errorlevel 1 (
  echo ERROR: close the dev IDE first - it locks the plugin jar and the config.
  exit /b 1
)

echo Building with Bazel...
cd /d "%REPO%"
call bazel.cmd build %BAZEL_TARGET%
if errorlevel 1 exit /b 1

if not exist "%JAR%" (
  echo ERROR: build did not produce the jar: %JAR%
  exit /b 1
)

echo Installing to %DEST% ...
mkdir "%DEST%" 2>nul
rem Remove stale jars first: two descriptors with the same plugin id confuse the loader.
del /q "%DEST%\*.jar" >nul 2>&1
copy /y "%JAR%" "%DEST%\" >nul
if errorlevel 1 (
  echo ERROR: copy failed.
  exit /b 1
)

rem The Jewel renderer needs the Compose UI plugin; undo its trim if present.
findstr /R /C:"^com.intellij.modules.compose$" "%DISABLED%" >nul 2>&1
if not errorlevel 1 (
  echo Re-enabling the Compose UI plugin for the Jewel preview ...
  powershell -NoProfile -Command "(Get-Content '%DISABLED%') | Where-Object { $_ -ne 'com.intellij.modules.compose' } | Set-Content '%DISABLED%'"
)

echo Done. Start the dev IDE to load the plugin.
