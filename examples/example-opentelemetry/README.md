gRPC OpenTelemetry Example
================

The example extends the gRPC "hello world" example by modifying the client and server to
showcase a sample configuration for gRPC OpenTelemetry with a Prometheus exporter.

The example requires grpc-java to be pre-built. Using a release tag will download the relevant binaries
from a maven repository. But if you need the latest SNAPSHOT binaries you will need to follow
[COMPILING](../../COMPILING.md) to build these.

### Build the example

The source code is [here](src/main/java/io/grpc/examples/opentelemetry).
To build the example, run in this directory:
```
$ ../gradlew installDist
```
The build creates scripts `opentelemetry-server` and `opentelemetry-client` in the `build/install/example-opentelemetry/bin/` directory
which can be used to run this example. The example requires the server to be running before starting the
client.

### Run the example

**opentelemetry-server**:

The opentelemetry-server accepts optional arguments for server-port and prometheus-port:

```text
USAGE: opentelemetry-server [server-port [prometheus-port]]
```

**opentelemetry-client**:

The opentelemetry-client accepts optional arguments for user-name, target and prometheus-port:

```text
USAGE: opentelemetry-client-client [user-name [target [prometheus-port]]]
```

The opentelemetry-client continuously sends an RPC to the server every second.

To make sure that the server and client metrics are being exported properly, in
a separate terminal, run the following:

```
$ curl localhost:9464/metrics
```

```
$ curl localhost:9465/metrics
```

> ***NOTE:*** If the prometheus endpoint configured is overridden, please update the target in the
> above curl command.
