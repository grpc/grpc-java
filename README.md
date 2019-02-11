gRPC-Java - An RPC library and framework
========================================

gRPC-Java works with JDK 7. gRPC-Java clients are supported on Android API
levels 14 and up (Ice Cream Sandwich and later). Deploying gRPC servers on an
Android device is not supported.

TLS usage typically requires using Java 8, or Play Services Dynamic Security
Provider on Android. Please see the [Security Readme](SECURITY.md).

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
[![Build Status](https://travis-ci.org/grpc/grpc-java.svg?branch=master)](https://travis-ci.org/grpc/grpc-java)
[![Coverage Status](https://coveralls.io/repos/grpc/grpc-java/badge.svg?branch=master&service=github)](https://coveralls.io/github/grpc/grpc-java?branch=master)

Getting Started
---------------

For a guided tour, take a look at the [quick start
guide](https://grpc.io/docs/quickstart/java.html) or the more explanatory [gRPC
basics](https://grpc.io/docs/tutorials/basic/java.html).

The [examples](https://github.com/grpc/grpc-java/tree/v1.17.1/examples) and the
[Android example](https://github.com/grpc/grpc-java/tree/v1.17.1/examples/android)
are standalone projects that showcase the usage of gRPC.

Download
--------

Download [the JARs][]. Or for Maven with non-Android, add to your `pom.xml`:
```xml
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-netty-shaded</artifactId>
  <version>1.18.0</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-protobuf</artifactId>
  <version>1.18.0</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-stub</artifactId>
  <version>1.18.0</version>
</dependency>
```

Or for Gradle with non-Android, add to your dependencies:
```gradle
compile 'io.grpc:grpc-netty-shaded:1.18.0'
compile 'io.grpc:grpc-protobuf:1.18.0'
compile 'io.grpc:grpc-stub:1.18.0'
```

For Android client, use `grpc-okhttp` instead of `grpc-netty-shaded` and
`grpc-protobuf-lite` instead of `grpc-protobuf`:
```gradle
compile 'io.grpc:grpc-okhttp:1.18.0'
compile 'io.grpc:grpc-protobuf-lite:1.18.0'
compile 'io.grpc:grpc-stub:1.18.0'
```

[the JARs]:
https://search.maven.org/search?q=g:io.grpc%20AND%20v:1.18.0

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
      <version>1.5.0.Final</version>
    </extension>
  </extensions>
  <plugins>
    <plugin>
      <groupId>org.xolstice.maven.plugins</groupId>
      <artifactId>protobuf-maven-plugin</artifactId>
      <version>0.6.1</version>
      <configuration>
        <protocArtifact>com.google.protobuf:protoc:3.6.1:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.18.0:exe:${os.detected.classifier}</pluginArtifact>
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

For protobuf-based codegen integrated with the Gradle build system, you can use
[protobuf-gradle-plugin][]:
<details open>
<summary>Groovy DSL</summary>

```groovy
plugins {
  id "com.google.protobuf" version "0.8.8"
  java
}

task.plugins {
  // Add grpc output without any option.  grpc must have been defined in the
  // protobuf.plugins block.
  grpc { }
}

protobuf {
  protoc {
    // The artifact spec for the Protobuf Compiler
    artifact = "com.google.protobuf:protoc:3.6.1"
  }
  plugins {
    // an artifact spec for a protoc plugin, with "grpc" as
    // the identifier, which can be referred to in the "plugins"
    // container of the "generateProtoTasks" closure.
    grpc {
      artifact = 'io.grpc:protoc-gen-grpc-java:1.18.0'
    }
  }
  generateProtoTasks {
    all()*.plugins {
      // Apply the "grpc" plugin whose spec is defined above, without
      // options.  Note the braces cannot be omitted, otherwise the
      // plugin will not be added. This is because of the implicit way
      // NamedDomainObjectContainer binds the methods.
      grpc { }
    }
  }
}
```
</details>

A community-supported Kotlin-DSL example is also available:
<details>
<summary>Kotlin DSL</summary>

```kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.google.protobuf.gradle.*
import org.gradle.kotlin.dsl.provider.gradleKotlinDslOf

buildscript {
    extra.set("grpcVersion", "1.18.0")
    extra.set("protocVersion", "3.6.1")
    extra.set("kotlinVersion", "1.3.20")
    extra.set("protoGradleVersion", "0.8.8")
}

plugins {
    kotlin("jvm") version "1.3.20"
    java
    id("com.google.protobuf") version "0.8.8"
}

repositories {
    mavenCentral()    
    maven("https://plugins.gradle.org/m2/")
}

sourceSets {
    main {
        // Optional: customize where .proto are found for src/main/
        proto {
            // In addition to the default 'src/main/proto'
            srcDir 'src/main/protobuf'
            srcDir 'src/main/protocolbuffers'
        }
        // Optional: compile the generated Java code
        java {
            srcDirs(
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/grpc"
            )
        }
    }
    test {
        // Optional: customize where .proto are found for src/test/
        proto {
            In addition to the default 'src/test/proto'
            srcDir 'src/test/protocolbuffers'
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_11
}

protobuf {
    protoc {
        // The artifact spec for the Protobuf Compiler
        val protocVersion : String by rootProject.extra
        artifact = "com.google.protobuf:protoc:$protocVersion"
    }

    grpc {
        val grpcVersion : String by rootProject.extra
        artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
    }
    
    generateProtoTasks {
        // all() returns the collection of all protoc tasks
        all().forEach { 
            // Add grpc output without any option.
            grpc { }
        }
    }
}

dependencies {
    // ********************************* Kotlin ******************************************
    // Use the Kotlin JDK 8 standard library
    val kotlinVersion : String by rootProject.extra
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    
    // for Java 9+. Workaround for @javax.annotation.Generated
    // see: https://github.com/grpc/grpc-java/issues/3633
    implementation("javax.annotation:javax.annotation-api:1.3.1")
    
    val protoGradleVersion : String by rootProject.extra
    implementation("com.google.protobuf:protobuf-gradle-plugin:$protoGradleVersion")
    
    val protocVersion : String by rootProject.extra
    implementation("com.google.protobuf:protobuf-java:$protocVersion")

    val grpcVersion : String by rootProject.extra
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
}
```
</details>
<p>

[protobuf-gradle-plugin]: https://github.com/google/protobuf-gradle-plugin

The prebuilt protoc-gen-grpc-java binary uses glibc on Linux. If you are
compiling on Alpine Linux, you may want to use the [Alpine grpc-java package][]
which uses musl instead.

[Alpine grpc-java package]: https://pkgs.alpinelinux.org/package/edge/testing/x86_64/grpc-java

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

gRPC comes with three Transport implementations:

1. The Netty-based transport is the main transport implementation based on
   [Netty](http://netty.io). It is for both the client and the server.
2. The OkHttp-based transport is a lightweight transport based on
   [OkHttp](http://square.github.io/okhttp/). It is mainly for use on Android
   and is for client only.
3. The in-process transport is for when a server is in the same process as the
   client. It is useful for testing, while also being safe for production use.
