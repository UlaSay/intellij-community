@echo off
rem Opens a file in the dev IDE built from the D:\intellij-community sources.
rem The dev IDE needs IDEA_VM_OPTIONS from the Bazel output, or it starts a
rem second instance with default settings instead of the sandbox.
setlocal enabledelayedexpansion

set "FILE=%~1"
if "%FILE%"=="" (
  echo Usage: open-in-dev-ide.cmd ^<file^>
  exit /b 1
)

set "DIST="
set "VMOPTS="
for /d %%A in ("C:\ProgramData\_bazel\*") do (
  for /d %%B in ("%%A\execroot\_main\bazel-out\*") do (
    if exist "%%B\bin\build\idea_community_dist.dist\bin\idea.exe" (
      set "DIST=%%B\bin\build\idea_community_dist.dist"
      set "VMOPTS=%%B\bin\build\idea_community.vmoptions"
    )
  )
)
if not defined DIST (
  echo Dev IDE dist not found. Build it first: cd /d D:\intellij-community ^&^& bazel.cmd run //build:idea_community
  exit /b 1
)

if exist "%VMOPTS%" set "IDEA_VM_OPTIONS=%VMOPTS%"
start "" "%DIST%\bin\idea.exe" "%FILE%"
