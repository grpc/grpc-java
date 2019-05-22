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

Running the hello world with TLS is the same as the normal hello world, but takes additional args:

**hello-world-tls-server**:

```text
USAGE: HelloWorldServerTls port certChainFilePath privateKeyFilePath [trustCertCollectionFilePath]
  Note: You only need to supply trustCertCollectionFilePath if you want to enable Mutual TLS.
```

**hello-world-tls-client**:

```text
USAGE: HelloWorldClientTls host port [trustCertCollectionFilePath [clientCertChainFilePath clientPrivateKeyFilePath]]
  Note: clientCertChainFilePath and clientPrivateKeyFilePath are only needed if mutual auth is desired.
```
- Note `trustCertCollectionFilePath` is not needed if you are using system default certificate authority.

You can run this example with our [test credentials](../../testing/src/main/resources/certs) with 
`.overrideAuthority("foo.test.google.fr")` for `ManagedChannelBuilder` to match the Subject Alternative Names
in the test certificates. You can generate your own self-signed certificates with commands in the test certs
[README](../../testing/src/main/resources/certs/README).

- Note you can use system default certificate authority if you are using a real server certificate.

#### Hello world example with TLS (no mutual auth):

```bash
# Run the server:
./build/install/example-tls/bin/hello-world-tls-server 50440 ../../testing/src/main/resources/certs/server1.pem ../../testing/src/main/resources/certs/server1.key
# In another terminal run the client
./build/install/example-tls/bin/hello-world-tls-client localhost 50440 ../../testing/src/main/resources/certs/ca.pem
```

#### Hello world example with TLS with mutual auth:

```bash
# Run the server:
./build/install/example-tls/bin/hello-world-tls-server 50440 ../../testing/src/main/resources/certs/server1.pem ../../testing/src/main/resources/certs/server1.key ../../testing/src/main/resources/certs/ca.pem
# In another terminal run the client
./build/install/example-tls/bin/hello-world-tls-client localhost 50440 ../../testing/src/main/resources/certs/ca.pem ../../testing/src/main/resources/certs/client.pem ../../testing/src/main/resources/certs/client.key
```

That's it!

## Maven

If you prefer to use Maven:

1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. Run in this directory:
```
$ mvn verify
$ # Run the server
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworldtls.HelloWorldServerTls -Dexec.args="50440 ../../testing/src/main/resources/certs/server1.pem ../../testing/src/main/resources/certs/server1.key"
$ # In another terminal run the client
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworldtls.HelloWorldClientTls -Dexec.args="localhost 50440 ../../testing/src/main/resources/certs/ca.pem"
```

## Bazel

If you prefer to use Bazel:
```
$ bazel build :hello-world-tls-server :hello-world-tls-client
$ # Run the server
$ ../bazel-bin/hello-world-tls-server 50440 ../../testing/src/main/resources/certs/server1.pem ../../testing/src/main/resources/certs/server1.key
$ # In another terminal run the client
$ ../bazel-bin/hello-world-tls-client localhost 50440 ../../testing/src/main/resources/certs/ca.pem
```
