Hello World Example with TLS
==============================================

The example require grpc-java to already be built. You are strongly encouraged
to check out a git release tag, since there will already be a build of grpc
available. Otherwise you must follow [COMPILING](../COMPILING.md).

To build the example,

1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. Run in this directory:
```
$ ../gradlew installDist
```

This creates the scripts `hello-world-tls-server`, `hello-world-tls-client`,
in the
`build/install/example-tls/bin/` directory that run the example. The
example requires the server to be running before starting the client.


## How to run

Running the hello world with TLS is the same as the normal hello world, but takes additional args:

**hello-world-tls-server**:

```text
USAGE: HelloWorldServerTls port certChainFilePath privateKeyFilePath [trustCertCollectionFilePath]
  Note: You only need to supply trustCertCollectionFilePath if you want to enable Mutual TLS.
```

**hello-world-tls-client**:

```text
USAGE: HelloWorldClientTls host port trustCertCollectionFilePath [clientCertChainFilePath clientPrivateKeyFilePath]
  Note: clientCertChainFilePath and clientPrivateKeyFilePath are only needed if mutual auth is desired.
```

We provide a set of self-signed client/server keys and certificates to run the hello world with TLS examples on 
`localhost`. They can be found in `src/main/resources/certs`. You will need to generate/supply your own 
client/server keys and certificates with Subject Alternative Names in other domains.


#### Hello world example with TLS (no mutual auth):

```bash
# Run the server:
./build/install/example-tls/bin/hello-world-tls-server 50440 ./src/main/resources/certs/server.pem ./src/main/resources/certs/server.key
# In another terminal run the client
./build/install/example-tls/bin/hello-world-tls-client localhost 50440 ./src/main/resources/certs/ca.pem
```

#### Hello world example with TLS with mutual auth:

```bash
# Run the server:
./build/install/example-tls/bin/hello-world-tls-server 50440 ./src/main/resources/certs/server.pem ./src/main/resources/certs/server.key ./src/main/resources/certs/ca.pem
# In another terminal run the client
./build/install/example-tls/bin/hello-world-tls-client localhost 50440 ./src/main/resources/certs/ca.pem ./src/main/resources/certs/client.pem ./src/main/resources/certs/client.key
```

That's it!

## Maven

If you prefer to use Maven:

1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. Run in this directory:
```
$ mvn verify
$ # Run the server
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworldtls.HelloWorldServerTls -Dexec.args="50440 /tmp/sslcert/server.crt /tmp/sslcert/server.pem"
$ # In another terminal run the client
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworldtls.HelloWorldClientTls -Dexec.args="localhost 50440 /tmp/sslcert/ca.crt"
```

## Bazel

If you prefer to use Bazel:
```
$ bazel build :hello-world-tls-server :hello-world-tls-client
$ # Run the server
$ ../bazel-bin/hello-world-tls-server 50440 /tmp/sslcert/server.crt /tmp/sslcert/server.pem
$ # In another terminal run the client
$ ../bazel-bin/hello-world-tls-client localhost 50440 /tmp/sslcert/ca.crt
```
