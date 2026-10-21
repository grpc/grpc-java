gRPC Error Details Example
=====================

If a gRPC call completes successfully the server returns an OK status to the client (depending on the language the OK status may or may not be directly used in your code). 
But what happens if the call isn’t successful?

This Example gives the usage and implementation of how return the error details if gRPC call not successful or fails
and how to set and read com.google.rpc.Status objects as google.rpc.Status error details.

gRPC allows detailed error information to be encapsulated in protobuf messages, which are sent alongside the status codes.

If an error occurs, gRPC returns one of its error status codes with error message that provides further error details about what happened.

Refer the below links for more details on error details and status codes  
- https://grpc.io/docs/guides/error/
- https://github.com/grpc/grpc-java/blob/master/api/src/main/java/io/grpc/Status.java