set RETRY=0
:install_pkgconfig
choco install -y pkgconfiglite --allow-empty-checksums --force
if %ERRORLEVEL% neq 0 (
  if %RETRY% lss 3 (
    set /a RETRY=%RETRY%+1
    echo pkgconfiglite installation failed. Retrying %RETRY% of 3 in 5 seconds...
    @rem Sleep for 5 seconds using the loopback ping trick (timeout command fails in non-interactive CI)
    ping -n 6 127.0.0.1 >nul
    goto :install_pkgconfig
  )
  echo Failed to install pkgconfiglite after 3 attempts.
  exit /b 1
)

choco install -y openjdk --version=17.0
set PATH=%PATH%;"c:\Program Files\OpenJDK\jdk-17\bin"
set PROTOBUF_VER=36.0-rc2
set ABSL_VERSION=20250512.1
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
call :RunPowershellWithRetry "$ProgressPreference = 'SilentlyContinue'; $ErrorActionPreference = 'stop'; & { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 ; iwr https://github.com/google/protobuf/releases/download/v%PROTOBUF_VER%/protobuf-%PROTOBUF_VER%.zip -OutFile protobuf.zip }" || exit /b 1
call :RunPowershellWithRetry "$ErrorActionPreference = 'stop'; & { Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('protobuf.zip', '.') }" || exit /b 1
del protobuf.zip
call :RunPowershellWithRetry "$ProgressPreference = 'SilentlyContinue'; $ErrorActionPreference = 'stop'; & { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 ; iwr https://github.com/abseil/abseil-cpp/archive/refs/tags/%ABSL_VERSION%.zip -OutFile absl.zip }" || exit /b 1
call :RunPowershellWithRetry "$ErrorActionPreference = 'stop'; & { Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('absl.zip', '.') }" || exit /b 1
del absl.zip
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
cmake -DCMAKE_CXX_STANDARD=17 -DABSL_MSVC_STATIC_RUNTIME=ON -Dprotobuf_BUILD_TESTS=OFF -DCMAKE_INSTALL_PREFIX=%cd%\protobuf-%PROTOBUF_VER% -DCMAKE_PREFIX_PATH=%cd%\protobuf-%PROTOBUF_VER% -G "Visual Studio %visual_studio_major_version% %VC_YEAR%" %CMAKE_VSARCH% .. || exit /b 1
cmake --build . --config Release --target install || exit /b 1
popd
goto :eof


:installCmake

call :RunPowershellWithRetry "$ErrorActionPreference = 'stop'; & { iwr https://cmake.org/files/v3.3/%CMAKE_NAME%.zip -OutFile cmake.zip }" || exit /b 1
call :RunPowershellWithRetry "$ErrorActionPreference = 'stop'; & { Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('cmake.zip', '.') }" || exit /b 1
del cmake.zip
goto :eof

@rem Helper to retry powershell commands (e.g. for transient network failures during iwr)
:RunPowershellWithRetry
set "PS_CMD=%~1"
set PS_RETRY=0
:ps_retry_loop
powershell -command "%PS_CMD%"
if %ERRORLEVEL% equ 0 exit /b 0
if %PS_RETRY% lss 3 (
  set /a PS_RETRY=%PS_RETRY%+1
  echo PowerShell command failed. Retrying %PS_RETRY% of 3 in 5 seconds...
  ping -n 6 127.0.0.1 >nul
  goto :ps_retry_loop
)
echo PowerShell command failed after 3 attempts: %PS_CMD%
exit /b 1


