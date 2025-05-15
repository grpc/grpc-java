choco install -y pkgconfiglite
choco install -y openjdk --version=17.0
set JAVA_HOME="c:\Program Files\OpenJDK\jdk-17"
set PATH=%PATH%;"c:\Program Files\OpenJDK\jdk-17\bin"
set PROTOBUF_VER=22.5
set ABSL_VERSION=20230125.4
set CMAKE_NAME=cmake-3.26.3-windows-x86_64

if not exist "protobuf-%PROTOBUF_VER%\build\Release\" (
  call :installProto || exit /b 1
)

echo Compile gRPC-Java with something like:
echo -PtargetArch=x86_32 -PvcProtobufLibPath=%cd%\protobuf-%PROTOBUF_VER%\build\protobuf-%PROTOBUF_VER%\lib -PvcProtobufInclude=%cd%\protobuf-%PROTOBUF_VER%\build\protobuf-%PROTOBUF_VER%\include -PvcProtobufLibs=insert-list-of-libs-from-pkg-config-output-here
goto :eof


:installProto

where /q cmake
if not ERRORLEVEL 1 goto :hasCmake
if not exist "%CMAKE_NAME%" (
  call :installCmake || exit /b 1
)
set PATH=%PATH%;%cd%\%CMAKE_NAME%\bin
:hasCmake
@rem GitHub requires TLSv1.2, and for whatever reason our powershell doesn't have it enabled
powershell -command "$ProgressPreference = 'SilentlyContinue'; $ErrorActionPreference = 'stop'; & { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 ; iwr https://github.com/google/protobuf/releases/download/v%PROTOBUF_VER%/protobuf-%PROTOBUF_VER%.zip -OutFile protobuf.zip }" || exit /b 1
powershell -command "$ErrorActionPreference = 'stop'; & { Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('protobuf.zip', '.') }" || exit /b 1
del protobuf.zip
powershell -command "$ProgressPreference = 'SilentlyContinue'; $ErrorActionPreference = 'stop'; & { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 ; iwr https://github.com/abseil/abseil-cpp/archive/refs/tags/%ABSL_VERSION%.zip -OutFile absl.zip }" || exit /b 1
powershell -command "$ErrorActionPreference = 'stop'; & { Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('absl.zip', '.') }" || exit /b 1
del absl.zip
rmdir protobuf-%PROTOBUF_VER%\third_party\abseil-cpp
move abseil-cpp-%ABSL_VERSION% protobuf-%PROTOBUF_VER%\third_party\abseil-cpp
mkdir protobuf-%PROTOBUF_VER%\build
pushd protobuf-%PROTOBUF_VER%\build

@rem cmake does not detect x86_64 from the vcvars64.bat variables.
@rem If vcvars64.bat has set PLATFORM to X64, then inform cmake to use the Win64 version of VS, likewise for x32
if "%PLATFORM%" == "x64" (
  SET CMAKE_VSARCH=-A x64
) else if "%PLATFORM%" == "x86" (
  @rem -A x86 doesn't work: https://github.com/microsoft/vcpkg/issues/15465
  SET CMAKE_VSARCH=-DCMAKE_GENERATOR_PLATFORM=WIN32
) else (
  SET CMAKE_VSARCH=
)
for /f "tokens=4 delims=\" %%a in ("%VCINSTALLDIR%") do (
  SET VC_YEAR=%%a
)
for /f "tokens=1 delims=." %%a in ("%VisualStudioVersion%") do (
  SET visual_studio_major_version=%%a
)
cmake -Dprotobuf_BUILD_TESTS=OFF -DCMAKE_INSTALL_PREFIX=%cd%\protobuf-%PROTOBUF_VER% -DCMAKE_PREFIX_PATH=%cd%\protobuf-%PROTOBUF_VER% -G "Visual Studio %visual_studio_major_version% %VC_YEAR%" %CMAKE_VSARCH% .. || exit /b 1
cmake --build . --config Release --target install || exit /b 1
popd
goto :eof


:installCmake

powershell -command "$ErrorActionPreference = 'stop'; & { iwr https://cmake.org/files/v3.3/%CMAKE_NAME%.zip -OutFile cmake.zip }" || exit /b 1
powershell -command "$ErrorActionPreference = 'stop'; & { Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('cmake.zip', '.') }" || exit /b 1
del cmake.zip
goto :eof

