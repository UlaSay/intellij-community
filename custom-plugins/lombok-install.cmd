@echo off
rem Builds the platform Lombok plugin from plugins/lombok and installs it into the dev IDE sandbox.
rem Run this with the dev IDE closed. The marketplace has no build for IC-271.SNAPSHOT, so build it here.
setlocal

set "REPO=D:\intellij-community"
set "TARGET=//plugins/lombok/plugin:intellij.lombok.plugin_dev_plugin"
set "FLAG=--//build:dev_dist_product_info=//build/dev-dist-descriptors:Idea_product_info"
set "MAINJAR=%REPO%\out\bazel-bin\plugins\lombok\plugin\intellij.lombok.plugin_dev_plugin\lib\lombok-plugin.jar"
set "STDIR=%REPO%\out\bazel-out\local_windows-fastbuild-ST-48d0a4d03be7\bin\plugins\lombok"
rem The sandbox path is fixed by the dev vmoptions:
set "SANDBOX=%REPO%\out\dev-data\idea_community\config\plugins"
set "DEST=%SANDBOX%\lombok\lib"

tasklist /FI "IMAGENAME eq idea.exe" | find /I "idea.exe" >nul
if not errorlevel 1 (
  echo ERROR: close the dev IDE first - it locks the plugin jars and the config.
  exit /b 1
)

echo Building with Bazel...
cd /d "%REPO%"
call bazel.cmd build %TARGET% --output_groups=dev_dist_plugin_outputs %FLAG%
if errorlevel 1 exit /b 1

for %%j in ("%MAINJAR%" "%STDIR%\lombok_content_module_jar\intellij.lombok.jar" "%STDIR%\generated\generated_content_module_jar\intellij.lombok.generated.jar") do (
  if not exist %%j (
    echo ERROR: build produced no %%j - the ST output path may have changed, check bazel cquery.
    exit /b 1
  )
)

echo Installing to %DEST% ...
mkdir "%DEST%\modules" 2>nul
rem Remove stale jars first: two descriptors with the same plugin id confuse the loader.
del /q "%DEST%\*.jar" >nul 2>&1
del /q "%DEST%\modules\*.jar" >nul 2>&1
copy /y "%MAINJAR%" "%DEST%\" >nul
copy /y "%STDIR%\lombok_content_module_jar\intellij.lombok.jar" "%DEST%\modules\" >nul
copy /y "%STDIR%\generated\generated_content_module_jar\intellij.lombok.generated.jar" "%DEST%\modules\" >nul
if errorlevel 1 (
  echo ERROR: copy failed.
  exit /b 1
)

echo Done. Start the dev IDE to load the plugin.
