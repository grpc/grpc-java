gRPC XDS Example
================

The XDS example is a Hello World client capable of being configured with the
XDS management protocol. Out-of-the-box it behaves the same as hello world
client.

__XDS support is incomplete and experimental, with limited compatibility. It
will be very hard to produce a working enviornment just by this example. Please
refer to documentation specific for your XDS management server and
environment.__

The example requires grpc-xds, but grpc-xds is not currently being published.
You will thus need to build it yourself. This should guide you, but if you
encounter issues please consult [COMPILING.md](../../COMPILING.md).

### Build the example

1. The server does not use XDS, so recent releases work fine. Building using
recent releases is much easier, so check out the most recent release tag:
```
$ git checkout v1.28.1
```

2. Build the hello-world example server or the hostname example server. See
   [the examples README](../README.md) or the
   [hostname example README](../example-hostname/README.md).

3. Since XDS is still developing rapidly, XDS-using code should be built from
master:
```
$ git checkout master
```

4. Building protoc-gen-grpc-java (the protoc plugin) requires a bit of work and
   isn't necessary. So change the hello-world example to use the last released
   version of the plugin. In `grpc-java/examples/build.gradle`, change:
```
        grpc { artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}" }
```
To:
```
        grpc { artifact = "io.grpc:protoc-gen-grpc-java:1.28.1" }
```


5. Build this client. From the `grpc-java/examples/examples-xds` directory:
```
$ ../gradlew -PskipCodegen=true -PskipAndroid=true --include-build ../.. installDist
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
bootstrap format). Then use the `xds-experimental:` target scheme during
channel creation.

```
$ export GRPC_XDS_BOOTSTRAP=/path/to/bootstrap.json
$ ./build/install/example-xds/bin/xds-hello-world-client "XDS world" xds-experimental:///yourServersName
```
