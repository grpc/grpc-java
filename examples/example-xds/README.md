gRPC XDS Example
================

The XDS example consists of a Hello World client and a Hello World server capable of
being configured with the XDS management protocol. Out-of-the-box the client
behaves the same the hello-world version and the server behaves similar to the
example-hostname but with a required dependency on xDS.

__XDS support is incomplete and experimental, with limited compatibility. It
will be very hard to produce a working environment just by this example. Please
refer to documentation specific for your XDS management server and
environment.__

### Build the example

Build the XDS hello-world example client & server. From the `grpc-java/examples/examples-xds`
directory:
```
$ ../gradlew installDist
```

This creates the scripts `build/install/example-xds/bin/xds-hello-world-client` and
`build/install/example-xds/bin/xds-hello-world-server`.

### Run the example without using XDS Credentials

To use XDS, you should first deploy the XDS management server in your deployment environment
and know its name. You need to set the `GRPC_XDS_BOOTSTRAP` environment variable (preferred) or if that is not set then
the `io.grpc.xds.bootstrap` java system property to point to the gRPC XDS bootstrap file (see
[gRFC A27](https://github.com/grpc/proposal/blob/master/A27-xds-global-load-balancing.md#xdsclient-and-bootstrap-file) for the
bootstrap format). This is needed by both `build/install/example-xds/bin/xds-hello-world-client`
and `build/install/example-xds/bin/xds-hello-world-server`.

1. To start the XDS-enabled example server on its default port of 50051, run:
```
$ export GRPC_XDS_BOOTSTRAP=/path/to/bootstrap.json
$ ./build/install/example-xds/bin/xds-hello-world-server
```

2. In a different terminal window, run the XDS-enabled example client:
```
$ export GRPC_XDS_BOOTSTRAP=/path/to/bootstrap.json
$ ./build/install/example-xds/bin/xds-hello-world-client "xds world" xds:///yourServersName
```
The first command line argument (`xds world`) is the name you wish to include in
the greeting request to the server and the second argument
(`xds:///yourServersName`) is the target to connect to using the `xds:` target
scheme.

### Run the example with xDS Credentials

The above example used plaintext (insecure) credentials as explicitly provided by the client and server
code. We will now demonstrate how the code can authorize use of xDS provided credentials by using
`XdsChannelCredentials` on the client side and using `XdsServerCredentials` on the server side.
This code is enabled by providing an additional command line argument.

1. On the server side, add `--xds-creds` on the command line to authorize use of xDS security:
```
$ export GRPC_XDS_BOOTSTRAP=/path/to/bootstrap.json
$ ./build/install/example-xds/bin/xds-hello-world-server --xds-creds
```

2. Similarly, add `--xds-creds` on the command line when you run the xDS client:
```
$ export GRPC_XDS_BOOTSTRAP=/path/to/bootstrap.json
$ ./build/install/example-xds/bin/xds-hello-world-client --xds-creds "xds world" xds:///yourServersName
```

In this case, if the xDS management server is configured to provide mTLS credentials (for example) to the client and
server, then they will use these credentials to create an mTLS channel to authenticate and encrypt.
