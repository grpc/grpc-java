# Hello World Example using Servlets

This example uses Java Servlets instead of Netty for the gRPC server. This example requires `grpc-java`
and `protoc-gen-grpc-java` to already be built. You are strongly encouraged to check out a git release
tag, since these builds will already be available.

```bash
git checkout v<major>.<minor>.<patch>
```
Otherwise, you must follow [COMPILING](../../COMPILING.md).

To build the example,

1. **[Install gRPC Java library SNAPSHOT locally, including code generation plugin](../../COMPILING.md) (Only need this step for non-released versions, e.g. master HEAD).**

2. In this directory, build the war file
```bash
$ ../gradlew war
```

To run this, deploy the war, now found in `build/libs/example-servlet.war` to your choice of servlet 
container. Note that this container must support the Servlet 4.0 spec, for this particular example must
use `javax.servlet` packages instead of the more modern `jakarta.servlet`, though there is a `grpc-servlet-jakarta`
artifact that can be used for Jakarta support. Be sure to enable http/2 support in the servlet container,
or clients will not be able to connect.

To test that this is working properly, build the HelloWorldClient example and direct it to connect to your
http/2 server. From the parent directory:

1. Build the executables:
```bash
$ ../gradlew installDist
```
2. Run the client app, specifying the name to say hello to and the server's address:
```bash
$ ./build/install/examples/bin/hello-world-client World localhost:8080
```