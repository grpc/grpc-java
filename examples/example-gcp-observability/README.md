gRPC GCP Observability Example
================

The GCP Observability example consists of a Hello World client and a Hello World server instrumented for logs, metrics and tracing. 

__Please refer to GCP observability user guide for setting up authorization to acess Google Cloud Platform with Google user credentials.__

### Build the example

Build the Observability hello-world example client & server. From the `grpc-java/examples/examples-gcp-observability`
directory:
```
$ ../gradlew installDist
```

This creates the scripts `build/install/example-gcp-observability/bin/observability-hello-world-client` and
`build/install/example-gcp-observability/bin/observability-hello-world-server`.

### Run the example with configuration

To use Observability, you should first setup and configure authorization and credentials to use gCloud CLI. 

You need to set the `GRPC_GCP_OBSERVABILITY_CONFIG_FILE` environment variable to point to the gRPC GCP Observability configuration file (preferred) or if that
is not set then `GRPC_GCP_OBSERVABILITY_CONFIG` environment variable to gRPC GCP Observability configuration value. This is needed by both
`build/install/example-gcp-observability/bin/observability-hello-world-client` and
`build/install/example-gcp-observability/bin/observability-hello-world-server`.

1. To start the observability-enabled example server on its default port of 50051, run:
```
$ export GRPC_GCP_OBSERVABILITY_CONFIG_FILE=src/main/resources/io/grpc/examples/observabilityHelloWorld/gcp_observability_config.json
$ ./build/install/example-gcp-observability/bin/observability-hello-world-server
```

2. In a different terminal window, run the observability-enabled example client:
```
$ export GRPC_GCP_OBSERVABILITY_CONFIG_FILE=src/main/resources/io/grpc/examples/observabilityHelloWorld/gcp_observability_config.json
$ ./build/install/example-gcp-observability/bin/observability-hello-world-client
```

