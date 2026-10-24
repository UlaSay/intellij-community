@echo off
rem Builds and installs the Markdown Light Preview plugin into the dev IDE sandbox.
rem Requires: the dev IDE dist from Bazel, and a JDK with jar.exe for packaging.
setlocal

set "DIST=C:\programdata\_bazel\nes6p7cp\execroot\_main\bazel-out\local_windows-fastbuild\bin\build\idea_community_dist.dist"
set "JAR_TOOL=C:\Program Files\Java\jdk-21.0.11\bin\jar.exe"
set "OUT=%~dp0out"
set "PLUGIN_ID=com.ulasay.md-light-preview"
rem The sandbox path is fixed by the dev vmoptions:
set "SANDBOX=D:\intellij-community\out\dev-data\idea_community\config\plugins"
set "TARGET=%SANDBOX%\%PLUGIN_ID%\lib"

if not exist "%DIST%\jbr\bin\javac.exe" (
  echo ERROR: dev IDE dist not found: %DIST%
  exit /b 1
)
if not exist "%JAR_TOOL%" (
  echo ERROR: jar tool not found: %JAR_TOOL%
  exit /b 1
)

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%\META-INF" >nul 2>&1

echo Compiling...
"%DIST%\jbr\bin\javac.exe" --release 17 -encoding UTF-8 -nowarn -cp "%DIST%\lib\*" -d "%OUT%" "%~dp0src\com\ulasay\mdpreview\MdHtml.java" "%~dp0src\com\ulasay\mdpreview\MdLightFileEditor.java" "%~dp0src\com\ulasay\mdpreview\MdLightFileEditorProvider.java" "%~dp0src\com\ulasay\mdpreview\MdPreviewAction.java" "%~dp0src\com\ulasay\mdpreview\MdPreviewDialog.java"
if errorlevel 1 exit /b 1

copy /y "%~dp0src\META-INF\plugin.xml" "%OUT%\META-INF\plugin.xml" >nul

echo Packaging...
"%JAR_TOOL%" --create --file "%OUT%\%PLUGIN_ID%.jar" -C "%OUT%" META-INF -C "%OUT%" com
if errorlevel 1 exit /b 1

echo Installing to %TARGET% ...
mkdir "%TARGET%" 2>nul
copy /y "%OUT%\%PLUGIN_ID%.jar" "%TARGET%\" >nul
if errorlevel 1 (
  echo ERROR: copy failed. Close the IDE first - it keeps the old jar memory-mapped.
  exit /b 1
)

echo Done. Restart the IDE to load the plugin.
