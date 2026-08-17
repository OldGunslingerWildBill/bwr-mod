@echo off
REM ---------------------------------------------------------------------------
REM One-word build. Produces mod\build\libs\mod-<version>.jar, which is the
REM installable mod: :core travels inside it as a jarJar nested jar, so nothing
REM else needs shipping alongside.
REM
REM   build          just the jar, about 20 seconds
REM   build full     the whole check: both modules, the acceptance suite and the
REM                  design-rule guard. Takes roughly 14 minutes.
REM
REM NeoForge 1.21.1 requires exactly Java 21. If java is not on PATH this script
REM looks in the usual places, including inside PrismLauncher, which ships a full
REM JDK 21 and is how this project is built on the machine it was written on.
REM ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion

if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\javac.exe" goto :found

REM A JDK already on PATH is good enough; Gradle will find it itself.
where javac >nul 2>&1 && goto :found

for %%D in (
    "%APPDATA%\PrismLauncher\java\java-runtime-delta"
    "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21"
    "%ProgramFiles%\Eclipse Adoptium\jdk-21"
    "%ProgramFiles%\Java\jdk-21"
    "%ProgramFiles%\Microsoft\jdk-21"
) do (
    if exist "%%~D\bin\javac.exe" (
        set "JAVA_HOME=%%~D"
        goto :found
    )
)

echo.
echo   No JDK 21 found.
echo.
echo   NeoForge 1.21.1 requires exactly Java 21. Either put one on PATH, or set
echo   JAVA_HOME to point at it, for example:
echo.
echo     set "JAVA_HOME=C:\path\to\jdk-21"
echo.
echo   A full JDK 21 also ships inside PrismLauncher, under
echo   %%APPDATA%%\PrismLauncher\java\.
echo.
exit /b 1

:found
if not "%JAVA_HOME%"=="" echo Using JAVA_HOME=%JAVA_HOME%

if /i "%~1"=="full" (
    echo Running the full build: both modules, 129 tests, design-rule guard.
    call "%~dp0gradlew.bat" build %2 %3 %4 %5
) else (
    call "%~dp0gradlew.bat" :mod:jar %1 %2 %3 %4 %5
)

if errorlevel 1 (
    echo.
    echo   BUILD FAILED - see the output above.
    exit /b 1
)

echo.
for %%J in ("%~dp0mod\build\libs\*.jar") do echo   Built: %%~fJ
echo.
echo   Drop that jar in your mods folder alongside NeoForge 21.1.248 on
echo   Minecraft 1.21.1. CC:Tweaked and Mekanism are optional, but you want
echo   CC:Tweaked if you intend to actually control the reactor.
echo.
exit /b 0
