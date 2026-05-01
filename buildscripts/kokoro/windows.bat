@rem ##########################################################################
@rem
@rem Script to set up Kokoro worker and run Windows tests
@rem
@rem ##########################################################################

type c:\VERSION

@rem Enter repo root
cd /d %~dp0\..\..

set WORKSPACE=T:\src\github\grpc-java
set ESCWORKSPACE=%WORKSPACE:\=\\%
@rem vswhere is too old, so it crashes gradle. https://github.com/gradle/gradle/issues/21993
rename "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" vswhere-disabled.exe


cmd.exe /C "%WORKSPACE%\buildscripts\kokoro\windows32.bat" || exit /b 1
cmd.exe /C "%WORKSPACE%\buildscripts\kokoro\windows64.bat" || exit /b 1

mkdir mvn-artifacts
move artifacts\io mvn-artifacts
