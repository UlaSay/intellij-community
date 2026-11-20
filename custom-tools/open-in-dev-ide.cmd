@echo off
rem Opens the dev IDE built from the D:\intellij-community sources. An optional
rem file argument opens that file directly. The dev IDE needs IDEA_VM_OPTIONS
rem from the Bazel output, or it starts a second instance with default settings
rem instead of the sandbox.
setlocal enabledelayedexpansion

set "FILE=%~1"

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
  echo Dev IDE dist not found. Build it first: cd /d D:\intellij-community ^&^& bazel.cmd build //build:idea_community_dist
  exit /b 1
)

if exist "%VMOPTS%" set "IDEA_VM_OPTIONS=%VMOPTS%"
if defined FILE (
  start "" "%DIST%\bin\idea.exe" "%FILE%"
) else (
  start "" "%DIST%\bin\idea.exe"
)
