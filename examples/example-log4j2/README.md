gRPC Log4j 2 Example
==============================================

This example illustrates how to set the Log4j 2 `ThreadContext` from a server interceptor so that
it is picked up by the logger in your server.

The server interceptor puts a `requestId` (a randomly generated UUID) and the `clientName` taken
from a request header into the `ThreadContext`. The `%X` conversion pattern in
[log4j2.xml](src/main/resources/log4j2.xml) then appends both values to every log statement made
while the call is being handled, so the service method itself does not have to pass them around.

These values are deliberately *not* stored in an `io.grpc.Context`: logging frameworks read from
thread-local storage, so the interceptor sets and clears the `ThreadContext` around each callback
using `CloseableThreadContext`.

### Build the example

The examples require `grpc-java` to already be built. You are strongly encouraged to check out a
git release tag, since there will already be a build of gRPC available. Otherwise you must follow
[COMPILING](../../COMPILING.md).

From the `grpc-java/examples/example-log4j2` directory:
```
$ ../gradlew installDist
```

This creates the scripts `build/install/example-log4j2/bin/custom-log-server` and
`build/install/example-log4j2/bin/custom-log-client`.

### Run the example

1. To start the server on its default port of 50051, run:
```
$ ./build/install/example-log4j2/bin/custom-log-server
```

2. In a different terminal window, run the client:
```
$ ./build/install/example-log4j2/bin/custom-log-client
```

The server logs a line for each request that includes the contextual values appended by `%X`:

```
2026/09/14 15:22:12:686 PDT INFO  CustomLogServer - Got a request {requestId=3e6c256d-6e87-411e-8bf3-fbf81e7ce0e6, clientName=my.domain.name}
```

Log statements made outside of an RPC, such as the server's startup message, are unaffected:

```
2026/09/14 15:22:04:132 PDT INFO  CustomLogServer - Server started, listening on 50051
```

### Caveat

The interceptor establishes the `ThreadContext` only for the `ServerCall.Listener` callbacks. It is
not set while interceptors further down the chain run their `interceptCall()` method, so a
downstream interceptor that logs from `interceptCall()` will not see these values.

For more information, refer to gRPC Java's [README](../../README.md) and
[tutorial](https://grpc.io/docs/languages/java/basics).
