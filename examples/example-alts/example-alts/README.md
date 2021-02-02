gRPC ALTS Example
==================

This example suite shows secure communication between a Hello World 
client and a Hello World server authenticated by Google's Application Layer 

Transport Security (ALTS). For more information about ALTS, see 
[ALTS Whiltepaper](https://cloud.google.com/security/encryption-in-transit/application-layer-transport-security) or [grpc.io tutorial](https://grpc.io/docs/languages/java/alts/).

The application runs successfully in a GCP environment 
out-of-the-box, and can be further configured to run in any environments
with a pre-deployed handshaker service.


### Build the example

To build the example,

1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. Run in this directory:
```
$ ../gradlew installDist
```

This creates the scripts `hello-world-alts-server`, `hello-world-alts-client`,
in the
`build/install/example-atls/bin/` directory that run the example.

### Run the example in a GCP environment
ALTS handshake protocol negotiation requires a separate handshaker service. It is
available in the GCP environment, so we can run the application directly:

```bash
# Run the server:
./build/install/example-alts/bin/hello-world-alts-server

```

In another terminal run the client

```
./build/install/example-alts/bin/hello-world-alts-client
```

That's it!

### Test the example in a non-GCP environment

To run the example in a non-GCP environment, you should first deploy a 
[handshaker service](https://github.com/grpc/grpc/blob/7e367da22a137e2e7caeae8342c239a91434ba50/src/proto/grpc/gcp/handshaker.proto#L224-L234) 
and know its name. You have to configure both the [ALTS client](https://github.com/grpc/grpc-java/blob/master/alts/src/main/java/io/grpc/alts/AltsChannelBuilder.java#L63-L76) 
and [ALTS server](https://github.com/grpc/grpc-java/blob/master/alts/src/main/java/io/grpc/alts/AltsServerCredentials.java#L55-L72)
to use the known handshaker server for testing. See [example](placeholder).
