gRPC XDS Example
================

The XDS example is a Hello World client capable of being configured with the
XDS management protocol. Out-of-the-box it behaves the same as hello world
client.

__XDS support is incomplete and experimental, with limited compatibility. It
will be very hard to produce a working enviornment just by this example. Please
refer to documentation specific for your XDS management server and
environment.__

### Build the example

1. Build the hello-world example server or the hostname example server. See
   [the examples README](../README.md) or the
   [hostname example README](../example-hostname/README.md).

2. Build the xds hello-world example client. From the `grpc-java/examples/examples-xds` directory:
```
$ ../gradlew installDist
```

This creates the script `build/install/example-xds/bin/xds-hello-world-client`
that runs the example.

To start the server, run:

```
$ ../build/install/hostname/bin/hello-world-server
$ # or
$ ../example-hostname/build/install/hostname/bin/hostname-server
```

And in a different terminal window run this client:

```
$ ./build/install/example-xds/bin/xds-hello-world-client
```

However, that didn't use XDS! To use XDS we assume you have deployed the server
in your deployment environment and know its name. You need to set the
`GRPC_XDS_BOOTSTRAP` environment variable to point to a gRPC XDS bootstrap
file (see [gRFC A27](https://github.com/grpc/proposal/pull/170) for the
bootstrap format). Then use the `xds:` target scheme during
channel creation.

```
$ export GRPC_XDS_BOOTSTRAP=/path/to/bootstrap.json
$ ./build/install/example-xds/bin/xds-hello-world-client "XDS world" xds:///yourServersName
```
