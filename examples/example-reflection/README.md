gRPC Reflection Example
================

The reflection example has a Hello World server with `ProtoReflectionService` registered. 

### Build the example

To build the example server, from the `grpc-java/examples/examples-reflection`
directory:
```
$ ../gradlew installDist
```

This creates the scripts `build/install/example-reflection/bin/reflection-server`.

### Run the example

gRPC Server Reflection provides information about publicly-accessible gRPC services on a server,
and assists clients at runtime to construct RPC requests and responses without precompiled
service information. It is used by gRPCurl, which can be used to introspect server protos and
send/receive test RPCs.

1. To start the reflection example server on its default port of 50051, run:
```
$ ./build/install/example-reflection/bin/reflection-server
```

2. After enabling Server Reflection in a server application, you can use gRPCurl to check its
services. Instructions on how to install and use gRPCurl can be found at [gRPCurl Installation](https://github.com/fullstorydev/grpcurl#installation)

After installing gRPCurl, open a new terminal and run the commands from the new terminal.

### List all the services exposed at a given port

  ```
  $ grpcurl -plaintext localhost:50051 list
  ```

Output

  ```
  grpc.reflection.v1alpha.ServerReflection
  helloworld.Greeter
  ```

### List all the methods of a service
  ```
  $ grpcurl -plaintext localhost:50051 helloworld.Greeter
  ```
Output
  ```
  helloworld.Greeter.SayHello
  ```

### Describe services and methods

The describe command inspects a method given its full name(in the format of 
`<package>.<service>.<method>`).

  ```
$ grpcurl -plaintext localhost:50051 describe helloworld.Greeter.SayHello
  ```

Output

  ```
  helloworld.Greeter.SayHello is a method:
  rpc SayHello ( .helloworld.HelloRequest ) returns ( .helloworld.HelloReply );
  ```

### Inspect message types

We can use the describe command to inspect request/response types given the full name of the type 
(in the format of `<package>.<type>`).

Get information about the request type:

  ```
$ grpcurl -plaintext localhost:50051 describe helloworld.HelloRequest
  ```

Output

  ```
  helloworld.HelloRequest is a message:
  message HelloRequest {
    string name = 1;
  }
  ```

### Call a remote method

We can send RPCs to a server and get responses using the full method name
(in the format of `<package>.<service>.<method>`). The `-d <string>` flag represents the request data
and the -format text flag indicates that the request data is in text format.

  ```
  $ grpcurl -plaintext -format text -d 'name: "gRPCurl"' \
    localhost:50051 helloworld.Greeter.SayHello
  ```

Output

  ```
  message: "Hello gRPCurl"
  ```
