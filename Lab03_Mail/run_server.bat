@echo off
pushd "%~dp0"
echo Starting Lab 03 - Mail Server ...
set "MVN_CMD=mvn"
where mvn >nul 2>nul
if errorlevel 1 (
  if exist "C:\opt\apache-maven-3.9.9\bin\mvn.cmd" (
    set "MVN_CMD=C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
  ) else (
    echo [ERROR] Maven (mvn) not found in PATH or C:\opt\apache-maven-3.9.9\bin\mvn.cmd
    pause
    popd
    exit /b 1
  )
)
call "%MVN_CMD%" compile exec:java "-Dexec.mainClass=com.ltm.lab03.MailServerApp"
if errorlevel 1 pause
popd
