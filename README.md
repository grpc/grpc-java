gRPC-Java - An RPC library and framework
========================================

[![Build Status](https://travis-ci.org/grpc/grpc-java.svg?branch=master)](https://travis-ci.org/grpc/grpc-java)

How to Build
------------

grpc-java has a C++ code generation plugin for protoc. Since many Java
developers don't have C compilers installed and don't need to modify the
codegen, the build can skip it. To skip, create the file
`<project-root>/gradle.properties` and add `skipCodegen=true`.

Then, to build, run:
```
$ ./gradlew build
```

To install the artifacts to your Maven local repository for use in your own
project, run:
```
$ ./gradlew install
```

How to Build Code Generation Plugin
-----------------------------------
This section is only necessary if you are making changes to the code
generation. Most users only need to use `skipCodegen=true` as discussed above.

### Build Protobuf
The codegen plugin is C++ code and requires protobuf 3.0.0-alpha-2.

For Linux, Mac and MinGW:
```
$ git clone https://github.com/google/protobuf.git
$ cd protobuf
$ git checkout v3.0.0-alpha-2
$ ./autogen.sh
$ ./configure
$ make
$ make check
$ sudo make install
```

If you are comfortable with C++ compilation and autotools, you can specify a
``--prefix`` for Protobuf and use ``-I`` in ``CXXFLAGS``, ``-L`` in
``LDFLAGS``, ``LD_LIBRARY_PATH``, and ``PATH`` to reference it. The
environment variables will be used when building grpc-java.

Protobuf installs to ``/usr/local`` by default.

For Visual C++, please refer to the [Protobuf README](https://github.com/google/protobuf/blob/master/vsprojects/readme.txt)
for how to compile Protobuf.

#### Linux and MinGW
If ``/usr/local/lib`` is not in your library search path, you can add it by running:
```
$ sudo sh -c 'echo /usr/local/lib >> /etc/ld.so.conf'
$ sudo ldconfig
```

#### Mac
Some versions of Mac OS X (e.g., 10.10) doesn't have ``/usr/local`` in the
default search paths for header files and libraries. It will fail the build of
the codegen. To work around this, you will need to set environment variables:
```
$ export CXXFLAGS="-I/usr/local/include" LDFLAGS="-L/usr/local/lib"
```

### Notes for Visual C++

When building on Windows and VC++, you need to specify project properties for
Gradle to find protobuf:
```
.\gradlew install ^
    -PvcProtobufInclude=C:\path\to\protobuf-3.0.0-alpha-2\src ^
    -PvcProtobufLibs=C:\path\to\protobuf-3.0.0-alpha-2\vsprojects\Release
```

Since specifying those properties every build is bothersome, you can instead
create ``<project-root>\gradle.properties`` with contents like:
```
vcProtobufInclude=C:\\path\\to\\protobuf-3.0.0-alpha-2\\src
vcProtobufLibs=C:\\path\\to\\protobuf-3.0.0-alpha-2\\vsprojects\\Release
```

The build script will build the codegen for the same architecture as the Java
runtime installed on your system. If you are using 64-bit JVM, the codegen will
be compiled for 64-bit, that means you must have compiled Protobuf in 64-bit.

### Notes for MinGW on Windows
If you have both MinGW and VC++ installed on Windows, VC++ will be used by
default. To override this default and use MinGW, add ``-PvcDisable=true``
to your Gradle command line or add ``vcDisable=true`` to your
``<project-root>\gradle.properties``.

### Notes for unsupported operating systems
The build script pulls pre-compiled ``protoc`` from Maven Central by default.
We have built ``protoc`` binaries for popular systems, but they may not work
for your system. If ``protoc`` cannot be downloaded or would not run, you can
use the one that has been built by your own, by adding this property to
``<project-root>/gradle.properties``:
```
protoc=/path/to/protoc
```

Navigating Around the Source
----------------------------

Heres a quick readers guide to the code to help folks get started. At a high level there are three distinct layers
to the library: stub, channel & transport.

### Stub

The 'stub'  layer is what is exposed to most developers and provides type-safe bindings to whatever 
datamodel/IDL/interface you are adapting. An example is provided of a binding to code generated by the protocol-buffers compiler but others should be trivial to add and are welcome.

#### Key Interfaces

[Stream Observer](https://github.com/google/grpc-java/blob/master/stub/src/main/java/io/grpc/stub/StreamObserver.java)


### Channel

The 'channel' layer is an abstraction over transport handling that is suitable for interception/decoration and exposes more behavior to the application than the stub layer. It is intended to be easy for application frameworks to use this layer to address cross-cutting concerns such as logging, monitoring, auth etc. Flow-control is also exposed at this layer to allow more sophisticated applications to interact with it directly.

#### Common

* [Metadata - headers & trailers](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/Metadata.java)
* [Status - error code namespace & handling](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/Status.java)

#### Client
* [Channel - client side binding](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/Channel.java)
* [Call](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/Call.java)
* [Client Interceptor](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/ClientInterceptor.java)

#### Server
* [Server call handler - analog to Channel on server](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/ServerCallHandler.java)
* [Server Call](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/ServerCall.java)


### Transport

The 'transport' layer does the heavy lifting of putting & taking bytes off the wire. The interfaces to it are abstract just enough to allow plugging in of different implementations. Transports are modeled as 'Stream' factories. The variation in interface between a server stream and a client stream exists to codify their differing semantics for cancellation and error reporting.

#### Common

* [Stream](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/transport/Stream.java)
* [Stream Listener](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/transport/StreamListener.java)

#### Client

* [Client Stream](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/transport/ClientStream.java)
* [Client Stream Listener](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/transport/ClientStreamListener.java)

#### Server

* [Server Stream](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/transport/ServerStream.java)
* [Server Stream Listener](https://github.com/google/grpc-java/blob/master/core/src/main/java/io/grpc/transport/ServerStreamListener.java)


### Examples

Tests showing how these layers are composed to execute calls using protobuf messages can be found here https://github.com/google/grpc-java/tree/master/integration-testing/src/main/java/io/grpc/testing/integration
