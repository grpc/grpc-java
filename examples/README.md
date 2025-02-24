gRPC Examples
==============================================

The examples require `grpc-java` to already be built. You are strongly encouraged
to check out a git release tag, since there will already be a build of gRPC
available. Otherwise you must follow [COMPILING](../COMPILING.md).

You may want to read through the
[Quick Start](https://grpc.io/docs/languages/java/quickstart)
before trying out the examples.

## Basic examples

- [Hello world](src/main/java/io/grpc/examples/helloworld)

- [Route guide](src/main/java/io/grpc/examples/routeguide)

- [Metadata](src/main/java/io/grpc/examples/header)

- [Error handling](src/main/java/io/grpc/examples/errorhandling)

- [Compression](src/main/java/io/grpc/examples/experimental)

- [Flow control](src/main/java/io/grpc/examples/manualflowcontrol)

- [Wait For Ready](src/main/java/io/grpc/examples/waitforready)

- [Json serialization](src/main/java/io/grpc/examples/advanced)

- [Hedging example](src/main/java/io/grpc/examples/hedging)

- [Retrying example](src/main/java/io/grpc/examples/retrying)

- [Health Service example](src/main/java/io/grpc/examples/healthservice)

- [Keep Alive](src/main/java/io/grpc/examples/keepalive)

- [Cancellation](src/main/java/io/grpc/examples/cancellation)

- [Custom Load Balance](src/main/java/io/grpc/examples/customloadbalance)

- [Deadline](src/main/java/io/grpc/examples/deadline)

- [Error Details](src/main/java/io/grpc/examples/errordetails)

- [GRPC Proxy](src/main/java/io/grpc/examples/grpcproxy)

- [Load Balance](src/main/java/io/grpc/examples/loadbalance)

- [Multiplex](src/main/java/io/grpc/examples/multiplex)

- [Name Resolve](src/main/java/io/grpc/examples/nameresolve)

- [Pre-Serialized Messages](src/main/java/io/grpc/examples/preserialized)

### <a name="to-build-the-examples"></a> To build the examples

1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. From grpc-java/examples directory:
```
$ ./gradlew installDist
```

This creates the scripts `hello-world-server`, `hello-world-client`,
`route-guide-server`, `route-guide-client`, etc. in the
`build/install/examples/bin/` directory that run the examples. Each
example requires the server to be running before starting the client.

For example, to try the hello world example first run:

```
$ ./build/install/examples/bin/hello-world-server
```

And in a different terminal window run:

```
$ ./build/install/examples/bin/hello-world-client
```

That's it!

For more information, refer to gRPC Java's [README](../README.md) and
[tutorial](https://grpc.io/docs/languages/java/basics).

### Maven

If you prefer to use Maven:
1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. Run in this directory:
```
$ mvn verify
$ # Run the server
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworld.HelloWorldServer
$ # In another terminal run the client
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworld.HelloWorldClient
```

### Bazel

If you prefer to use Bazel:
```
$ bazel build :hello-world-server :hello-world-client
$ # Run the server
$ bazel-bin/hello-world-server
$ # In another terminal run the client
$ bazel-bin/hello-world-client
```

## Other examples

- [Android examples](android)

- Secure channel examples

  + [TLS examples](example-tls)

  + [ALTS examples](example-alts)

- [Google Authentication](example-gauth)

- [JWT-based Authentication](example-jwt-auth)

- [OAuth2-based Authentication](example-oauth)

- [Pre-serialized messages](src/main/java/io/grpc/examples/preserialized)

## Unit test examples

Examples for unit testing gRPC clients and servers are located in [examples/src/test](src/test).

In general, we DO NOT allow overriding the client stub and we DO NOT support mocking final methods
in gRPC-Java library. Users should be cautious that using tools like PowerMock or
[mockito-inline](https://search.maven.org/search?q=g:org.mockito%20a:mockito-inline) can easily
break this rule of thumb. We encourage users to leverage `InProcessTransport` as demonstrated in the
examples to write unit tests. `InProcessTransport` is light-weight and runs the server
and client in the same process without any socket/TCP connection.

Mocking the client stub provides a false sense of security when writing tests. Mocking stubs and responses
allows for tests that don't map to reality, causing the tests to pass, but the system-under-test to fail.
The gRPC client library is complicated, and accurately reproducing that complexity with mocks is very hard.
You will be better off and write less code by using `InProcessTransport` instead.

Example bugs not caught by mocked stub tests include:

* Calling the stub with a `null` message
* Not calling `close()`
* Sending invalid headers
* Ignoring deadlines
* Ignoring cancellation

For testing a gRPC client, create the client with a real stub
using an
[InProcessChannel](../core/src/main/java/io/grpc/inprocess/InProcessChannelBuilder.java),
and test it against an
[InProcessServer](../core/src/main/java/io/grpc/inprocess/InProcessServerBuilder.java)
with a mock/fake service implementation.

For testing a gRPC server, create the server as an InProcessServer,
and test it against a real client stub with an InProcessChannel.

The gRPC-java library also provides a JUnit rule,
[GrpcCleanupRule](../testing/src/main/java/io/grpc/testing/GrpcCleanupRule.java), to do the graceful
shutdown boilerplate for you.

## Even more examples

A wide variety of third-party examples can be found [here](https://github.com/saturnism/grpc-java-by-example).
