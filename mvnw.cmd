@REM ----------------------------------------------------------------------------
@REM Maven Wrapper (only-script flavour) for Windows.
@REM Downloads the Maven distribution named in .mvn\wrapper\maven-wrapper.properties
@REM into %USERPROFILE%\.m2\wrapper\dists on first use, then delegates to it.
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PROPS=%SCRIPT_DIR%.mvn\wrapper\maven-wrapper.properties"
if not exist "%PROPS%" (
  echo mvnw: cannot find %PROPS% 1>&2
  exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in ("%PROPS%") do (
  if "%%A"=="distributionUrl" set "DIST_URL=%%B"
)
if "%DIST_URL%"=="" (
  echo mvnw: distributionUrl is not set in %PROPS% 1>&2
  exit /b 1
)

for %%F in ("%DIST_URL%") do set "ARCHIVE=%%~nxF"
set "DIST_NAME=%ARCHIVE:-bin.tar.gz=%"
set "DIST_NAME=%DIST_NAME:-bin.zip=%"

if "%MAVEN_USER_HOME%"=="" set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "DIST_ROOT=%MAVEN_USER_HOME%\wrapper\dists\%DIST_NAME%"
set "MAVEN_HOME=%DIST_ROOT%\%DIST_NAME%"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12;" ^
    "Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile '%DIST_ROOT%\%ARCHIVE%'"
  if errorlevel 1 (
    echo mvnw: download failed: %DIST_URL% 1>&2
    exit /b 1
  )
  tar -xzf "%DIST_ROOT%\%ARCHIVE%" -C "%DIST_ROOT%"
  if errorlevel 1 (
    echo mvnw: could not extract %ARCHIVE% 1>&2
    exit /b 1
  )
  del "%DIST_ROOT%\%ARCHIVE%"
)

set "MAVEN_PROJECTBASEDIR=%SCRIPT_DIR:~0,-1%"
"%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
