@if "%DEBUG%"=="" @echo off
@rem Gradle launcher for AntiHide V2.
@rem Named antihide-gradlew.bat because this machine blocks the conventional gradlew.bat filename.
@rem Usage:  antihide-gradlew.bat :app:testDebugUnitTest
setlocal
set APP_BASE_NAME=%~n0
set APP_HOME=%~dp0
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME is not set and no java command could be found. 1>&2
goto fail

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
goto fail

:execute
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

:end
if "%OS%"=="Windows_NT" endlocal
:fail
if not "%OS%"=="Windows_NT" exit 1
exit /b 1