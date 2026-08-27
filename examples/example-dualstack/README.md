# gRPC Dualstack Example

The dualstack example uses a custom name resolver that provides both IPv4 and IPv6 localhost
endpoints for each of 3 server instances. The client will first use the default name resolver and
load balancers which will only connect to the first server.  It will then use the 
custom name resolver with round robin to connect to each of the servers in turn.  The 3 instances
of the server will bind respectively to: both IPv4 and IPv6, IPv4 only, and IPv6 only.

The example requires grpc-java to already be built. You are strongly encouraged
to check out a git release tag, since there will already be a build of grpc
available. Otherwise, you must follow [COMPILING](../../COMPILING.md).

### Build the example

To build the dualstack example server and client. From the
   `grpc-java/examples/example-dualstack` directory run:

```bash
$ ../gradlew installDist
```

This creates the scripts
`build/install/example-dualstack/bin/dual-stack-server`
 and `build/install/example-dualstack/bin/dual-stack-client`.

To run the dualstack example, run the server with:

```bash
$ ./build/install/example-dualstack/bin/dual-stack-server
```

And in a different terminal window run the client.

```bash
$ ./build/install/example-dualstack/bin/dual-stack-client
```

### Maven

If you prefer to use Maven:

Run in the example-debug directory:

```bash
$ mvn verify
$ # Run the server in one terminal
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.dualstack.DualStackServer
```

```bash
$ # In another terminal run the client
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.dualstack.DualStackClient
```

