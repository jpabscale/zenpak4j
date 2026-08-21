@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gradlew startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem The Gradle wrapper jar is NOT committed to the repo. Fetch it on first use
@rem (and re-fetch if it does not match the pinned checksum) via curl, which
@rem ships with Windows 10+ and Git for Windows, so the build bootstrap stays
@rem reproducible. Version and checksum are read from gradle.properties.
set "WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"
set "WRAPPER_PROPS=%APP_HOME%\gradle.properties"
set "WRAPPER_VERSION="
set "WRAPPER_SHA256="
if exist "%WRAPPER_PROPS%" (
    for /f "usebackq tokens=1,* delims==" %%a in ("%WRAPPER_PROPS%") do (
        if "%%a"=="gradle.wrapper.version" set "WRAPPER_VERSION=%%b"
        if "%%a"=="gradle.wrapper.sha256" set "WRAPPER_SHA256=%%b"
    )
)
if "%WRAPPER_VERSION%"=="" goto wrapperPropsMissing
if "%WRAPPER_SHA256%"=="" goto wrapperPropsMissing
set "WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/v%WRAPPER_VERSION%/gradle/wrapper/gradle-wrapper.jar"
goto wrapperPropsOk

:wrapperPropsMissing
echo ERROR: gradle.wrapper.version / gradle.wrapper.sha256 not found in %WRAPPER_PROPS% 1>&2
"%COMSPEC%" /c exit 1

:wrapperPropsOk

if not exist "%WRAPPER_JAR%" goto fetchWrapper
for /f %%h in ('certutil -hashfile "%WRAPPER_JAR%" SHA256 ^| findstr /r "^[0-9a-f][0-9a-f]*$"') do set "WRAPPER_ACTUAL=%%h"
if /I "%WRAPPER_ACTUAL%"=="%WRAPPER_SHA256%" goto wrapperReady

:fetchWrapper
echo Fetching Gradle wrapper jar (gradle-%WRAPPER_VERSION%) ... 1>&2
curl.exe -fsSL -o "%WRAPPER_JAR%.tmp" "%WRAPPER_URL%" 1>&2
if errorlevel 1 (
    echo ERROR: failed to download %WRAPPER_URL% 1>&2
    "%COMSPEC%" /c exit 1
)
for /f %%h in ('certutil -hashfile "%WRAPPER_JAR%.tmp" SHA256 ^| findstr /r "^[0-9a-f][0-9a-f]*$"') do set "WRAPPER_ACTUAL=%%h"
if /I NOT "%WRAPPER_ACTUAL%"=="%WRAPPER_SHA256%" (
    del "%WRAPPER_JAR%.tmp"
    echo ERROR: gradle-wrapper.jar checksum mismatch ^(expected %WRAPPER_SHA256%^) 1>&2
    "%COMSPEC%" /c exit 1
)
move /Y "%WRAPPER_JAR%.tmp" "%WRAPPER_JAR%" >NUL

:wrapperReady

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem Setup the command line



@rem Execute gradlew
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
