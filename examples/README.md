grpc Examples
==============================================

The examples require grpc-java to already be built. You are strongly encouraged
to check out a git release tag, since there will already be a build of grpc
available. Otherwise you must follow [COMPILING](../COMPILING.md).

You may want to read through the
[Quick Start Guide](https://grpc.io/docs/quickstart/java.html)
before trying out the examples.

## Basic examples

- [Hello world](src/main/java/io/grpc/examples/helloworld)

- [Route guide](src/main/java/io/grpc/examples/routeguide)

- [Metadata](src/main/java/io/grpc/examples/header)

- [Error handling](src/main/java/io/grpc/examples/errorhandling)

- [Compression](src/main/java/io/grpc/examples/experimental)

- [Flow control](src/main/java/io/grpc/examples/manualflowcontrol)

- [Json serialization](src/main/java/io/grpc/examples/advanced)

- <details>
  <summary>Hedging</summary>

  The [hedging example](src/main/java/io/grpc/examples/hedging) demonstrates that enabling hedging
  can reduce tail latency. (Users should note that enabling hedging may introduce other overhead;
  and in some scenarios, such as when some server resource gets exhausted for a period of time and
  almost every RPC during that time has high latency or fails, hedging may make things worse.
  Setting a throttle in the service config is recommended to protect the server from too many
  inappropriate retry or hedging requests.)

  The server and the client in the example are basically the same as those in the
  [hello world](src/main/java/io/grpc/examples/helloworld) example, except that the server mimics a
  long tail of latency, and the client sends 2000 requests and can turn on and off hedging.

  To mimic the latency, the server randomly delays the RPC handling by 2 seconds at 10% chance, 5
  seconds at 5% chance, and 10 seconds at 1% chance.

  When running the client enabling the following hedging policy

  ```json
        "hedgingPolicy": {
          "maxAttempts": 3,
          "hedgingDelay": "1s"
        }
  ```
  Then the latency summary in the client log is like the following

  ```text
  Total RPCs sent: 2,000. Total RPCs failed: 0
  [Hedging enabled]
  ========================
  50% latency: 0ms
  90% latency: 6ms
  95% latency: 1,003ms
  99% latency: 2,002ms
  99.9% latency: 2,011ms
  Max latency: 5,272ms
  ========================
  ```

  See [the section below](#to-build-the-examples) for how to build and run the example. The
  executables for the server and the client are `hedging-hello-world-server` and
  `hedging-hello-world-client`.

  To disable hedging, set environment variable `DISABLE_HEDGING_IN_HEDGING_EXAMPLE=true` before
  running the client. That produces a latency summary in the client log like the following

  ```text
  Total RPCs sent: 2,000. Total RPCs failed: 0
  [Hedging disabled]
  ========================
  50% latency: 0ms
  90% latency: 2,002ms
  95% latency: 5,002ms
  99% latency: 10,004ms
  99.9% latency: 10,007ms
  Max latency: 10,007ms
  ========================
  ```

</details>

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

Please refer to gRPC Java's [README](../README.md) and
[tutorial](https://grpc.io/docs/tutorials/basic/java.html) for more
information.

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

- [Kotlin examples](example-kotlin)

- [Kotlin Android examples](example-kotlin/android)

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
