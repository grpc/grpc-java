# Security Policy

For information on gRPC Security Policy and reporting potentional security
issues, please see [gRPC CVE Process][].

[gRPC CVE Process]: https://github.com/grpc/proposal/blob/master/P4-grpc-cve-process.md

# Authentication

gRPC supports a number of different mechanisms for asserting identity between an
client and server. This document provides code samples demonstrating how to
provide SSL/TLS encryption support and identity assertions in Java, as well as
passing OAuth2 tokens to services that support it.

# Transport Security (TLS)

HTTP/2 over TLS mandates the use of [ALPN](https://tools.ietf.org/html/rfc7301)
to negotiate the use of the h2 protocol and support for the GCM mode of AES.

There are multiple options available, but on Android we recommend using the
[Play Services Provider](#tls-on-android) and for non-Android systems we
recommend [netty-tcnative with
BoringSSL](#tls-with-netty-tcnative-on-boringssl).

## TLS on Android

On Android we recommend the use of the [Play Services Dynamic Security
Provider][] to ensure your application has an up-to-date OpenSSL library with
the necessary cipher-suites and a reliable ALPN implementation. This requires
[updating the security provider at runtime][config-psdsp].

Although ALPN mostly works on newer Android releases (especially since 5.0),
there are bugs and discovered security vulnerabilities that are only fixed by
upgrading the security provider. Thus, we recommend using the Play Service
Dynamic Security Provider for all Android versions.

*Note: The Dynamic Security Provider must be installed **before** creating a
gRPC OkHttp channel. gRPC statically initializes the security protocol(s)
available, which means that changes to the security provider after the first
channel is created will not be noticed by gRPC.*

[Play Services Dynamic Security Provider]: https://www.appfoundry.be/blog/2014/11/18/Google-Play-Services-Dynamic-Security-Provider/
[config-psdsp]: https://developer.android.com/training/articles/security-gms-provider.html

### Bundling Conscrypt

If depending on Play Services is not an option for your app, then you may bundle
[Conscrypt](https://conscrypt.org) with your application. Binaries are available
on [Maven Central][conscrypt-maven].

Like the Play Services Dynamic Security Provider, you must still "install"
Conscrypt before use.

```java
import org.conscrypt.Conscrypt;
import java.security.Security;
...

Security.insertProviderAt(Conscrypt.newProvider(), 1);
```

[conscrypt-maven]: https://search.maven.org/#search%7Cga%7C1%7Cg%3Aorg.conscrypt%20a%3Aconscrypt-android

## TLS on non-Android

OpenJDK versions prior to Java 8u252 do not support ALPN. Java 8 has 10% the
performance of OpenSSL.

We recommend most users use grpc-netty-shaded, which includes netty-tcnative on
BoringSSL. It includes pre-built libraries for 64 bit Windows, OS X, and 64 bit
Linux. For 32 bit Windows, Conscrypt is an option. For all other platforms, Java
9+ is required.

For users of xDS management protocol, the grpc-netty-shaded transport is
particularly appropriate since it is already used internally for the xDS
protocol and is a runtime dependency of grpc-xds.

For users of grpc-netty we recommend [netty-tcnative with
BoringSSL](#tls-with-netty-tcnative-on-boringssl), although using the built-in
JDK support in Java 9+, [Conscrypt](#tls-with-conscrypt), and [netty-tcnative
with OpenSSL](#tls-with-netty-tcnative-on-openssl) are other valid options.

[Netty TCNative](https://github.com/netty/netty-tcnative) is a fork of
[Apache Tomcat's tcnative](https://tomcat.apache.org/native-doc/) and is a JNI
wrapper around OpenSSL/BoringSSL/LibreSSL.

We recommend BoringSSL for its simplicity and low occurrence of security
vulnerabilities relative to OpenSSL. BoringSSL is used by Conscrypt as well.

### TLS with netty-tcnative on BoringSSL

Netty-tcnative with BoringSSL includes BoringSSL statically linked in the
binary. This means the system's pre-installed TLS libraries will not be used.
Production systems that have centralized upgrade agility in the face of
security vulnerabilities may want to use [netty-tcnative on
OpenSSL](#tls-with-netty-tcnative-on-openssl) instead.

Users of grpc-netty-shaded will automatically use netty-tcnative with
BoringSSL.

grpc-netty users will need to add the appropriate
`netty-tcnative-boringssl-static` artifact to the application's classpath.
Artifacts are available for 64 bit Windows, OS X, and 64 bit Linux.

Depending on netty-tcnative-boringssl-static will include binaries for all
supported platforms. For Maven:

```xml
  <dependencies>
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-tcnative-boringssl-static</artifactId>
      <version>2.0.20.Final</version> <!-- See table for correct version -->
      <scope>runtime</scope>
    </dependency>
  </dependencies>
```

And for Gradle:

```gradle
dependencies {
  // See table for correct version
  runtime 'io.netty:netty-tcnative-boringssl-static:2.0.20.Final'
}
```

For projects sensitive to binary size, specify the classifier for the precise
platform you need: `windows-x86_64`, `osx-x86_64`, `linux-x86_64`. You can also
use [os-maven-plugin](https://github.com/trustin/os-maven-plugin) or
[osdetector-gradle-plugin](https://github.com/google/osdetector-gradle-plugin),
to choose the classifier for the platform running the build.

### TLS with netty-tcnative on OpenSSL

Using OpenSSL can have more initial configuration issues, but can be useful if
your OS's OpenSSL version is recent and kept up-to-date with security fixes.
OpenSSL is not included with tcnative, but instead is dynamically linked using
your operating system's OpenSSL.

To use OpenSSL you will use the `netty-tcnative` artifact. It requires:

1. [OpenSSL](https://www.openssl.org/) version >= 1.0.2 for ALPN support.
2. [Apache APR library (libapr-1)](https://apr.apache.org/) version >= 1.5.2.

You must specify a classifier for the correct netty-tcnative binary:
`windows-x86_64`, `osx-x86_64`, `linux-x86_64`, or `linux-x86_64-fedora`.
Fedora derivatives use a different soname from other Linux distributations, so
you must select the "fedora" version on those distributions.

In Maven, you can use the
[os-maven-plugin](https://github.com/trustin/os-maven-plugin) to help simplify
the dependency.

```xml
<project>
  <dependencies>
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-tcnative</artifactId>
      <version>2.0.20.Final</version> <!-- see table for correct version -->
      <classifier>${tcnative.classifier}</classifier>
      <scope>runtime</scope>
    </dependency>
  </dependencies>

  <build>
    <extensions>
      <!-- Use os-maven-plugin to initialize the "os.detected" properties -->
      <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>1.7.1</version>
      </extension>
    </extensions>
    <plugins>
      <!-- Use Ant to configure the appropriate "tcnative.classifier" property -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-antrun-plugin</artifactId>
        <executions>
          <execution>
            <phase>initialize</phase>
            <configuration>
              <exportAntProperties>true</exportAntProperties>
              <target>
                <condition property="tcnative.classifier"
                           value="${os.detected.classifier}-fedora"
                           else="${os.detected.classifier}">
                  <isset property="os.detected.release.fedora"/>
                </condition>
              </target>
            </configuration>
            <goals>
              <goal>run</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

And in Gradle you can use the
[osdetector-gradle-plugin](https://github.com/google/osdetector-gradle-plugin).

```gradle
buildscript {
  repositories {
    mavenCentral()
  }
  dependencies {
    classpath 'com.google.gradle:osdetector-gradle-plugin:1.4.0'
  }
}

// Use the osdetector-gradle-plugin
apply plugin: "com.google.osdetector"

def tcnative_classifier = osdetector.classifier;
// Fedora variants use a different soname for OpenSSL than other linux distributions
// (see http://netty.io/wiki/forked-tomcat-native.html).
if (osdetector.os == "linux" && osdetector.release.isLike("fedora")) {
  tcnative_classifier += "-fedora";
}

dependencies {
    runtime 'io.netty:netty-tcnative:2.0.20.Final:' + tcnative_classifier
}
```

### TLS with Conscrypt

[Conscrypt](https://conscrypt.org) provides an implementation of the JSSE
security APIs based on BoringSSL. Pre-built binaries are available for 32 and
64 bit Windows, OS X, and 64 bit Linux.

Depend on `conscrypt-openjdk-uber` for binaries of all supported JRE platforms.
For projects sensitive to binary size, depend on `conscrypt-openjdk` and
specify the appropriate classifier. `os-maven-plugin` and
`osdetector-gradle-plugin` may also be used. See the documentation for
[netty-tcnative-boringssl-static](#tls-with-netty-tcnative-on-boringssl) for
example usage of the plugins.

Generally you will "install" Conscrypt before use, for gRPC to find.

```java
import org.conscrypt.Conscrypt;
import java.security.Security;
...

// Somewhere in main()
Security.insertProviderAt(Conscrypt.newProvider(), 1);
```

## Enabling TLS on a server

To use TLS on the server, a certificate chain and private key need to be
specified in PEM format. The standard TLS port is 443, but we use 8443 below to
avoid needing extra permissions from the OS.

```java
ServerCredentials creds = TlsServerCredentials.create(certChainFile, privateKeyFile);
Server server = Grpc.newServerBuilderForPort(8443, creds)
    .addService(serviceImplementation)
    .build()
    .start();
```

If the issuing certificate authority is not known to the client then a properly
configured trust manager should be provided to TlsChannelCredentials and used to
construct the channel.

## Mutual TLS

[Mutual authentication][] (or "client-side authentication") configuration is similar to the server by providing truststores, a client certificate and private key to the client channel.  The server must also be configured to request a certificate from clients, as well as truststores for which client certificates it should allow.

```java
ServerCredentials creds = TlsServerCredentials.newBuilder()
    .keyManager(certChainFile, privateKeyFile)
    .trustManager(clientCAsFile)
    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
    .build();
```

Negotiated client certificates are available in the SSLSession, which is found
in the `Grpc.TRANSPORT_ATTR_SSL_SESSION` attribute of the call. A server
interceptor can provide details in the current Context.

```java
// The application uses this in its handlers.
public static final Context.Key<MySecurityInfo> SECURITY_INFO = Context.key("my.security.Info");

@Override
public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    if (sslSession == null) {
        return next.startCall(call, headers);
    }
    // This interceptor can provide a centralized policy to process the client's
    // certificate. Avoid exposing low-level details (like SSLSession) and
    // instead provide a higher-level concept like "authenticated user."
    MySecurityInfo info = process(sslSession);
    return Contexts.interceptCall(
        Context.current().withValue(SECURITY_INFO, info), call, headers, next);
}
```

[Mutual authentication]: http://en.wikipedia.org/wiki/Transport_Layer_Security#Client-authenticated_TLS_handshake

## Troubleshooting

If you received an error message "ALPN is not configured properly" or "Jetty ALPN/NPN has not been properly configured", it most likely means that:
 - ALPN related dependencies are either not present in the classpath
 - or that there is a classpath conflict
 - or that a wrong version is used due to dependency management
 - or you are on an unsupported platform (e.g., 32-bit OS). See [Transport
   Security](#transport-security-tls) for supported platforms.

### Netty
If you aren't using gRPC on Android devices, you are most likely using `grpc-netty` transport.

If you are developing for Android and have a dependency on `grpc-netty`, you should remove it as `grpc-netty` is unsupported on Android. Use `grpc-okhttp` instead.

If you are on a 32-bit operating system, using Java 11+ may be the easiest
solution, as ALPN was added to Java in Java 9. If on 32-bit Windows, [Conscrypt
is an option](#tls-with-conscrypt). Otherwise you need to [build your own 32-bit
version of
`netty-tcnative`](https://netty.io/wiki/forked-tomcat-native.html#wiki-h2-6).

If on Alpine Linux and you see "Error loading shared library libcrypt.so.1: No
such file or directory". Run `apk update && apk add libc6-compat` to install the
necessary dependency.

If on Alpine Linux, try to use `grpc-netty-shaded` instead of `grpc-netty` or
(if you need `grpc-netty`) `netty-tcnative-boringssl-static` instead of
`netty-tcnative`. If those are not an option, you may consider using
[netty-tcnative-alpine](https://github.com/pires/netty-tcnative-alpine).

If on Fedora 30 or later and you see "libcrypt.so.1: cannot open shared object
file: No such file or directory". Run `dnf -y install libxcrypt-compat` to
install the necessary dependency.

Most dependency versioning problems can be solved by using
`io.grpc:grpc-netty-shaded` instead of `io.grpc:grpc-netty`, although this also
limits your usage of the Netty-specific APIs. `io.grpc:grpc-netty-shaded`
includes the proper version of Netty and `netty-tcnative-boringssl-static` in a
way that won't conflict with other Netty usages.

Find the dependency tree (e.g., `mvn dependency:tree`), and look for versions of:
 - `io.grpc:grpc-netty`
 - `io.netty:netty-handler` (really, make sure all of io.netty except for
   netty-tcnative has the same version)
 - `io.netty:netty-tcnative-boringssl-static:jar` 

If `netty-tcnative-boringssl-static` is missing, then you either need to add it as a dependency, or use alternative methods of providing ALPN capability by reading the *Transport Security (TLS)* section carefully.

If you have both `netty-handler` and `netty-tcnative-boringssl-static` dependencies, then check the versions carefully. These versions could've been overridden by dependency management from another BOM. You would receive the "ALPN is not configured properly" exception if you are using incompatible versions.

If you have other `netty` dependencies, such as `netty-all`, that are pulled in from other libraries, then ultimately you should make sure only one `netty` dependency is used to avoid classpath conflict. The easiest way is to exclude transitive Netty dependencies from all the immediate dependencies, e.g., in Maven use `<exclusions>`, and then add an explict Netty dependency in your project along with the corresponding `tcnative` versions. See the versions table below.

If you are running in a runtime environment that also uses Netty (e.g., Hadoop, Spark, Spring Boot 2) and you have no control over the Netty version at all, then you should use a shaded gRPC Netty dependency to avoid classpath conflicts with other Netty versions in runtime the classpath:
 - Remove `io.grpc:grpc-netty` dependency
 - Add `io.grpc:grpc-netty-shaded` dependency

Below are known to work version combinations:

grpc-netty version | netty-handler version | netty-tcnative-boringssl-static version
------------------ |-----------------------| ---------------------------------------
1.0.0-1.0.1        | 4.1.3.Final           | 1.1.33.Fork19
1.0.2-1.0.3        | 4.1.6.Final           | 1.1.33.Fork23
1.1.x-1.3.x        | 4.1.8.Final           | 1.1.33.Fork26
1.4.x              | 4.1.11.Final          | 2.0.1.Final
1.5.x              | 4.1.12.Final          | 2.0.5.Final
1.6.x              | 4.1.14.Final          | 2.0.5.Final
1.7.x-1.8.x        | 4.1.16.Final          | 2.0.6.Final
1.9.x-1.10.x       | 4.1.17.Final          | 2.0.7.Final
1.11.x-1.12.x      | 4.1.22.Final          | 2.0.7.Final
1.13.x             | 4.1.25.Final          | 2.0.8.Final
1.14.x-1.15.x      | 4.1.27.Final          | 2.0.12.Final
1.16.x-1.17.x      | 4.1.30.Final          | 2.0.17.Final
1.18.x-1.19.x      | 4.1.32.Final          | 2.0.20.Final
1.20.x-1.21.x      | 4.1.34.Final          | 2.0.22.Final
1.22.x             | 4.1.35.Final          | 2.0.25.Final
1.23.x-1.24.x      | 4.1.38.Final          | 2.0.25.Final
1.25.x-1.27.x      | 4.1.42.Final          | 2.0.26.Final
1.28.x             | 4.1.45.Final          | 2.0.28.Final
1.29.x-1.31.x      | 4.1.48.Final          | 2.0.30.Final
1.32.x-1.34.x      | 4.1.51.Final          | 2.0.31.Final
1.35.x-1.41.x      | 4.1.52.Final          | 2.0.34.Final
1.42.x-1.43.x      | 4.1.63.Final          | 2.0.38.Final
1.44.x-1.47.x      | 4.1.72.Final          | 2.0.46.Final
1.48.x-1.49.x      | 4.1.77.Final          | 2.0.53.Final
1.50.x-1.53.x      | 4.1.79.Final          | 2.0.54.Final
1.54.x-1.55.x      | 4.1.87.Final          | 2.0.56.Final
1.56.x             | 4.1.87.Final          | 2.0.61.Final
1.57.x-1.58.x      | 4.1.93.Final          | 2.0.61.Final
1.59.x             | 4.1.97.Final          | 2.0.61.Final
1.60.x-1.66.x      | 4.1.100.Final         | 2.0.61.Final
1.67.x-1.70.x      | 4.1.110.Final         | 2.0.65.Final
1.71.x-            | 4.1.110.Final         | 2.0.70.Final

_(grpc-netty-shaded avoids issues with keeping these versions in sync.)_

### OkHttp
If you are using gRPC on Android devices, you are most likely using
`grpc-okhttp` transport.

Find the dependency tree (e.g., `mvn dependency:tree`), and look for
`io.grpc:grpc-okhttp`. If you don't have `grpc-okhttp`, you should add it as a
dependency.

# gRPC over plaintext

An option is provided to use gRPC over plaintext without TLS. While this is convenient for testing environments, users must be aware of the security risks of doing so for real production systems.

# Using OAuth2

The following code snippet shows how you can call the Google Cloud PubSub API using gRPC with a service account. The credentials are loaded from a key stored in a well-known location or by detecting that the application is running in an environment that can provide one automatically, e.g. Google Compute Engine. While this example is specific to Google and it's services, similar patterns can be followed for other service providers.

```java
// Use the default credentials from the environment
ChannelCredentials creds = GoogleDefaultChannelCredentials.create();
// Create a channel to the service
ManagedChannel channel = Grpc.newChannelBuilder("dns:///pubsub.googleapis.com", creds)
    .build();
// Create a stub and send an RPC
PublisherGrpc.PublisherBlockingStub publisherStub = PublisherGrpc.newBlockingStub(channel);
publisherStub.publish(someMessage);
```
