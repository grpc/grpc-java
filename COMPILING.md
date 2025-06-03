Building gRPC-Java
==================

Building is only necessary if you are making changes to gRPC-Java or testing/using a non-released
 version (e.g. master HEAD) of gRPC-Java library.

Building requires JDK 8, as our tests use TLS.

grpc-java has a C++ code generation plugin for protoc. Since many Java
developers don't have C compilers installed and don't need to run or modify the
codegen, the build can skip it. To skip, create the file
`<project-root>/gradle.properties` and add `skipCodegen=true`.

Some parts of grpc-java depend on Android. Since many Java developers don't have
the Android SDK installed and don't need to run or modify the Android
components, the build can skip it. To skip, create the file
`<project-root>/gradle.properties` and add `skipAndroid=true`.
Otherwise, create the file `<project-root>/gradle.properties` and add `android.useAndroidX=true`.

Then, to build, run:
```
$ ./gradlew build
```

To install the artifacts to your Maven local repository for use in your own
project, run:
```
$ ./gradlew publishToMavenLocal
```

### Notes for IntelliJ
Building in IntelliJ works best when you import the project as a Gradle project and delegate IDE
build/run actions to Gradle.

You can find this setting at:
```Settings -> Build, Execution, Deployment
      -> Build Tools -> Gradle -> Runner
      -> Delegate IDE build/run actions to gradle.
```

How to Build Code Generation Plugin
-----------------------------------
This section is only necessary if you are making changes to the code
generation. Most users only need to use `skipCodegen=true` as discussed above.

### Build Protobuf
The codegen plugin is C++ code and requires protobuf 22.5 or later.

For Linux, Mac and MinGW:
```
$ PROTOBUF_VERSION=22.5
$ curl -LO https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-all-$PROTOBUF_VERSION.tar.gz
$ tar xzf protobuf-all-$PROTOBUF_VERSION.tar.gz
$ cd protobuf-$PROTOBUF_VERSION
$ ./configure --disable-shared
$ make   # You may want to pass -j to make this run faster; see make --help
$ sudo make install
```

If you are comfortable with C++ compilation and autotools, you can specify a
``--prefix`` for Protobuf and use ``-I`` in ``CXXFLAGS``, ``-L`` in
``LDFLAGS`` to reference it. The
environment variables will be used when building grpc-java.

Protobuf installs to ``/usr/local`` by default.

For Visual C++, please refer to the [Protobuf README](https://github.com/google/protobuf/blob/master/cmake/README.md)
for how to compile Protobuf. gRPC-java assumes a Release build.

#### Mac
Some versions of Mac OS X (e.g., 10.10) don't have ``/usr/local`` in the
default search paths for header files and libraries. It will fail the build of
the codegen. To work around this, you will need to set environment variables:
```
$ export CXXFLAGS="-I/usr/local/include" LDFLAGS="-L/usr/local/lib"
```

### Notes for Visual C++

When building on Windows and VC++, you need to specify project properties for
Gradle to find protobuf:
```
.\gradlew publishToMavenLocal ^
    -PvcProtobufInclude=C:\path\to\protobuf\src ^
    -PvcProtobufLibs=C:\path\to\protobuf\vsprojects\Release ^
    -PtargetArch=x86_32
```

Since specifying those properties every build is bothersome, you can instead
create ``<project-root>\gradle.properties`` with contents like:
```
vcProtobufInclude=C:\\path\\to\\protobuf\\src
vcProtobufLibs=C:\\path\\to\\protobuf\\vsprojects\\Release
targetArch=x86_32
```

By default, the build script will build the codegen for the same architecture as
the Java runtime installed on your system. If you are using 64-bit JVM, the
codegen will be compiled for 64-bit. Since Protobuf is only built for 32-bit by
default, the `targetArch=x86_32` is necessary.

### Notes for MinGW on Windows
If you have both MinGW and VC++ installed on Windows, VC++ will be used by
default. To override this default and use MinGW, add ``-PvcDisable=true``
to your Gradle command line or add ``vcDisable=true`` to your
``<project-root>\gradle.properties``.

### Notes for Unsupported Operating Systems
The build script pulls pre-compiled ``protoc`` from Maven Central by default.
We have built ``protoc`` binaries for popular systems, but they may not work
for your system. If ``protoc`` cannot be downloaded or would not run, you can
use the one that has been built by your own, by adding this property to
``<project-root>/gradle.properties``:
```
protoc=/path/to/protoc
```

How to install Android SDK
---------------------------
This section is only necessary if you are building modules depending on Android 
(e.g., `cronet`). Non-Android users only need to use `skipAndroid=true` as 
discussed above.

### Install via Android Studio (GUI)
Download and install Android Studio from [Android Developer site](https://developer.android.com/studio).
You can find the configuration for Android SDK at:
```
Preferences -> System Settings -> Android SDK
```
Select the version of Android SDK to be installed and click `apply`. The location
of Android SDK being installed is shown at `Android SDK Location` at the same panel.
The default is `$HOME/Library/Android/sdk` for Mac OS and `$HOME/Android/Sdk` for Linux. 
You can change this to a custom location.

### Install via Command line tools only
Go to [Android SDK](https://developer.android.com/studio#command-tools) and
download the commandlinetools package for your build machine OS. Decide where
you want the Android SDK to be stored. `$HOME/Library/Android/sdk` is typical on
Mac OS and `$HOME/Android/Sdk` for Linux.

```sh
export ANDROID_HOME=$HOME/Android/Sdk # Adjust to your liking
mkdir $HOME/Android
mkdir $ANDROID_HOME
mkdir $ANDROID_HOME/cmdline-tools
unzip -d $ANDROID_HOME/cmdline-tools DOWNLOADS/commandlinetools-*.zip
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
# Android SDK is now ready. Now accept licenses so the build can auto-download packages
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses

# Add 'export ANDROID_HOME=$HOME/Android/Sdk' to your .bashrc or equivalent
```
