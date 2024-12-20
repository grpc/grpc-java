gRPC Retrying Example
=====================

The Retrying example provides a HelloWorld gRPC client &
server which demos the effect of client retry policy configured on the [ManagedChannel](
../api/src/main/java/io/grpc/ManagedChannel.java) via [gRPC ServiceConfig](
https://github.com/grpc/grpc/blob/master/doc/service_config.md). Retry policy implementation &
configuration details are outlined in the [proposal](https://github.com/grpc/proposal/blob/master/A6-client-retries.md).

This retrying example is very similar to the [hedging example](src/main/java/io/grpc/examples/hedging) in its setup.
The [RetryingHelloWorldServer](src/main/java/io/grpc/examples/retrying/RetryingHelloWorldServer.java) responds with
a status UNAVAILABLE error response to a specified percentage of requests to simulate server resource exhaustion and
general flakiness. The [RetryingHelloWorldClient](src/main/java/io/grpc/examples/retrying/RetryingHelloWorldClient.java) makes
a number of sequential requests to the server, several of which will be retried depending on the configured policy in
[retrying_service_config.json](src/main/resources/io/grpc/examples/retrying/retrying_service_config.json). Although
the requests are blocking unary calls for simplicity, these could easily be changed to future unary calls in order to
test the result of request concurrency with retry policy enabled.

One can experiment with the [RetryingHelloWorldServer](src/main/java/io/grpc/examples/retrying/RetryingHelloWorldServer.java)
failure conditions to simulate server throttling, as well as alter policy values in the [retrying_service_config.json](
src/main/resources/io/grpc/examples/retrying/retrying_service_config.json) to see their effects. To disable retrying
entirely, set environment variable `DISABLE_RETRYING_IN_RETRYING_EXAMPLE=true` before running the client.
Disabling the retry policy should produce many more failed gRPC calls as seen in the output log.

See [the section below](#to-build-the-examples) for how to build and run the example. The
executables for the server and the client are `retrying-hello-world-server` and
`retrying-hello-world-client`.
