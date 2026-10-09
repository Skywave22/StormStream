@rem Gradle start script for Windows.
@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem  Gradle startup script for Windows
@rem ##########################################################################

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Determine the Java command to use to start the JVM.
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVACMD=java
goto execute

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVACMD=%JAVA_HOME%\bin\java
if exist "%JAVACMD%" goto execute
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
exit /b 1

:execute
"%JAVACMD%" %JAVA_OPTS% -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*

:end
