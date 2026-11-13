@echo off
rem Rebuilds the IDE distribution with the custom plugins bundled. Run this
rem with the dev IDE closed: it runs from the distribution directory.
setlocal

set "REPO=D:\intellij-community"
set "DIST_TARGET=//build:idea_community_dist"

tasklist /FI "IMAGENAME eq idea.exe" | findstr /I "idea.exe" >nul
if not errorlevel 1 (
  echo ERROR: close the dev IDE first - it runs from the distribution directory.
  exit /b 1
)
tasklist /FI "IMAGENAME eq idea64.exe" | findstr /I "idea64.exe" >nul
if not errorlevel 1 (
  echo ERROR: close the dev IDE first - it runs from the distribution directory.
  exit /b 1
)

echo Building the IDE distribution ...
cd /d "%REPO%"
call bazel.cmd build %DIST_TARGET%
if errorlevel 1 exit /b 1

echo Done. Start the dev IDE from the rebuilt distribution.
