gRPC Route Guide Example
=====================

This example gives the usage and implementation of route guide server and client to demonstrate 
how to use grpc libraries to perform all 4 types (unary, client streaming, server streaming and bidirectional) of RPC services and methods.

This also gets the default features file (https://github.com/grpc/grpc-java/blob/master/examples/src/main/resources/io/grpc/examples/routeguide/route_guide_db.json) from common utility class 
which internally loads from classpath along with getting the latitude and longitude for given point.

Refer the router_guid.proto definition/specification for all 4 types of RPCs
https://github.com/grpc/grpc-java/blob/master/examples/src/main/proto/route_guide.proto

Refer the gRPC documentation for more details on creation, build and execution of route guide example with explanation
https://grpc.io/docs/languages/java/basics/