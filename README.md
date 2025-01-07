gRPC-Java - An RPC library and framework
========================================

<table>
  <tr>
    <td><b>Homepage:</b></td>
    <td><a href="https://grpc.io/">grpc.io</a></td>
  </tr>
  <tr>
    <td><b>Mailing List:</b></td>
    <td><a href="https://groups.google.com/forum/#!forum/grpc-io">grpc-io@googlegroups.com</a></td>
  </tr>
</table>

[![Join the chat at https://gitter.im/grpc/grpc](https://badges.gitter.im/grpc/grpc.svg)](https://gitter.im/grpc/grpc?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![GitHub Actions Linux Testing](https://github.com/grpc/grpc-java/actions/workflows/testing.yml/badge.svg?branch=master)](https://github.com/grpc/grpc-java/actions/workflows/testing.yml?branch=master)
[![Line Coverage Status](https://coveralls.io/repos/grpc/grpc-java/badge.svg?branch=master&service=github)](https://coveralls.io/github/grpc/grpc-java?branch=master)
[![Branch-adjusted Line Coverage Status](https://codecov.io/gh/grpc/grpc-java/branch/master/graph/badge.svg)](https://codecov.io/gh/grpc/grpc-java)

Supported Platforms
-------------------

gRPC-Java supports Java 8 and later. Android minSdkVersion 21 (Lollipop) and
later are supported with [Java 8 language desugaring][android-java-8].

TLS usage on Android typically requires Play Services Dynamic Security Provider.
Please see the [Security Readme](SECURITY.md).

Older Java versions are not directly supported, but a branch remains available
for fixes and releases. See [gRFC P5 JDK Version Support
Policy][P5-jdk-version-support].

Java version | gRPC Branch
------------ | -----------
7            | 1.41.x

[android-java-8]: https://developer.android.com/studio/write/java8-support#supported_features
[P5-jdk-version-support]: https://github.com/grpc/proposal/blob/master/P5-jdk-version-support.md#proposal

Getting Started
---------------

For a guided tour, take a look at the [quick start
guide](https://grpc.io/docs/languages/java/quickstart) or the more explanatory [gRPC
basics](https://grpc.io/docs/languages/java/basics).

The [examples](https://github.com/grpc/grpc-java/tree/v1.68.1/examples) and the
[Android example](https://github.com/grpc/grpc-java/tree/v1.68.1/examples/android)
are standalone projects that showcase the usage of gRPC.

Download
--------

Download [the JARs][]. Or for Maven with non-Android, add to your `pom.xml`:
```xml
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-netty-shaded</artifactId>
  <version>1.68.1</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-protobuf</artifactId>
  <version>1.68.1</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-stub</artifactId>
  <version>1.68.1</version>
</dependency>
<dependency> <!-- necessary for Java 9+ -->
  <groupId>org.apache.tomcat</groupId>
  <artifactId>annotations-api</artifactId>
  <version>6.0.53</version>
  <scope>provided</scope>
</dependency>
```

Or for Gradle with non-Android, add to your dependencies:
```gradle
runtimeOnly 'io.grpc:grpc-netty-shaded:1.68.1'
implementation 'io.grpc:grpc-protobuf:1.68.1'
implementation 'io.grpc:grpc-stub:1.68.1'
compileOnly 'org.apache.tomcat:annotations-api:6.0.53' // necessary for Java 9+
```

For Android client, use `grpc-okhttp` instead of `grpc-netty-shaded` and
`grpc-protobuf-lite` instead of `grpc-protobuf`:
```gradle
implementation 'io.grpc:grpc-okhttp:1.68.1'
implementation 'io.grpc:grpc-protobuf-lite:1.68.1'
implementation 'io.grpc:grpc-stub:1.68.1'
compileOnly 'org.apache.tomcat:annotations-api:6.0.53' // necessary for Java 9+
```

For [Bazel](https://bazel.build), you can either
[use Maven](https://github.com/bazelbuild/rules_jvm_external)
(with the GAVs from above), or use `@io_grpc_grpc_java//api` et al (see below).

[the JARs]:
https://search.maven.org/search?q=g:io.grpc%20AND%20v:1.68.1

Development snapshots are available in [Sonatypes's snapshot
repository](https://oss.sonatype.org/content/repositories/snapshots/).

Generated Code
--------------

For protobuf-based codegen, you can put your proto files in the `src/main/proto`
and `src/test/proto` directories along with an appropriate plugin.

For protobuf-based codegen integrated with the Maven build system, you can use
[protobuf-maven-plugin][] (Eclipse and NetBeans users should also look at
`os-maven-plugin`'s
[IDE documentation](https://github.com/trustin/os-maven-plugin#issues-with-eclipse-m2e-or-other-ides)):
```xml
<build>
  <extensions>
    <extension>
      <groupId>kr.motd.maven</groupId>
      <artifactId>os-maven-plugin</artifactId>
      <version>1.7.1</version>
    </extension>
  </extensions>
  <plugins>
    <plugin>
      <groupId>org.xolstice.maven.plugins</groupId>
      <artifactId>protobuf-maven-plugin</artifactId>
      <version>0.6.1</version>
      <configuration>
        <protocArtifact>com.google.protobuf:protoc:3.25.5:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.68.1:exe:${os.detected.classifier}</pluginArtifact>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>compile</goal>
            <goal>compile-custom</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

[protobuf-maven-plugin]: https://www.xolstice.org/protobuf-maven-plugin/

For non-Android protobuf-based codegen integrated with the Gradle build system,
you can use [protobuf-gradle-plugin][]:
```gradle
plugins {
    id 'com.google.protobuf' version '0.9.4'
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:3.25.5"
  }
  plugins {
    grpc {
      artifact = 'io.grpc:protoc-gen-grpc-java:1.68.1'
    }
  }
  generateProtoTasks {
    all()*.plugins {
      grpc {}
    }
  }
}
```

[protobuf-gradle-plugin]: https://github.com/google/protobuf-gradle-plugin

The prebuilt protoc-gen-grpc-java binary uses glibc on Linux. If you are
compiling on Alpine Linux, you may want to use the [Alpine grpc-java package][]
which uses musl instead.

[Alpine grpc-java package]: https://pkgs.alpinelinux.org/package/edge/community/x86_64/grpc-java

For Android protobuf-based codegen integrated with the Gradle build system, also
use protobuf-gradle-plugin but specify the 'lite' options:

```gradle
plugins {
    id 'com.google.protobuf' version '0.9.4'
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:3.25.5"
  }
  plugins {
    grpc {
      artifact = 'io.grpc:protoc-gen-grpc-java:1.68.1'
    }
  }
  generateProtoTasks {
    all().each { task ->
      task.builtins {
        java { option 'lite' }
      }
      task.plugins {
        grpc { option 'lite' }
      }
    }
  }
}

```

For [Bazel](https://bazel.build), use the [`proto_library`](https://github.com/bazelbuild/rules_proto)
and the [`java_proto_library`](https://bazel.build/reference/be/java#java_proto_library) (no `load()` required) 
and `load("@io_grpc_grpc_java//:java_grpc_library.bzl", "java_grpc_library")` (from this project), as in
[this example `BUILD.bazel`](https://github.com/grpc/grpc-java/blob/master/examples/BUILD.bazel).

API Stability
-------------

APIs annotated with `@Internal` are for internal use by the gRPC library and
should not be used by gRPC users. APIs annotated with `@ExperimentalApi` are
subject to change in future releases, and library code that other projects
may depend on should not use these APIs.

We recommend using the
[grpc-java-api-checker](https://github.com/grpc/grpc-java-api-checker)
(an [Error Prone](https://github.com/google/error-prone) plugin)
to check for usages of `@ExperimentalApi` and `@Internal` in any library code
that depends on gRPC. It may also be used to check for `@Internal` usage or
unintended `@ExperimentalApi` consumption in non-library code.

How to Build
------------

If you are making changes to gRPC-Java, see the [compiling
instructions](COMPILING.md).

High-level Components
---------------------

At a high level there are three distinct layers to the library: *Stub*,
*Channel*, and *Transport*.

### Stub

The Stub layer is what is exposed to most developers and provides type-safe
bindings to whatever datamodel/IDL/interface you are adapting. gRPC comes with
a [plugin](https://github.com/google/grpc-java/blob/master/compiler) to the
protocol-buffers compiler that generates Stub interfaces out of `.proto` files,
but bindings to other datamodel/IDL are easy and encouraged.

### Channel

The Channel layer is an abstraction over Transport handling that is suitable for
interception/decoration and exposes more behavior to the application than the
Stub layer. It is intended to be easy for application frameworks to use this
layer to address cross-cutting concerns such as logging, monitoring, auth, etc.

### Transport

The Transport layer does the heavy lifting of putting and taking bytes off the
wire. The interfaces to it are abstract just enough to allow plugging in of
different implementations. Note the transport layer API is considered internal
to gRPC and has weaker API guarantees than the core API under package `io.grpc`.

gRPC comes with multiple Transport implementations:

1. The Netty-based HTTP/2 transport is the main transport implementation based
   on [Netty](https://netty.io). It is not officially supported on Android.
   There is a "grpc-netty-shaded" version of this transport. It is generally
   preferred over using the Netty-based transport directly as it requires less
   dependency management and is easier to upgrade within many applications.
2. The OkHttp-based HTTP/2 transport is a lightweight transport based on
   [Okio](https://square.github.io/okio/) and forked low-level parts of
   [OkHttp](https://square.github.io/okhttp/). It is mainly for use on Android.
3. The in-process transport is for when a server is in the same process as the
   client. It is used frequently for testing, while also being safe for
   production use.
4. The Binder transport is for Android cross-process communication on a single
   device.
