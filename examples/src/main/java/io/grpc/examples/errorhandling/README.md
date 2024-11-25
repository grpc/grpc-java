gRPC Error Handling Example
=====================

Error handling in gRPC is a critical aspect of designing reliable and robust distributed systems. 
gRPC provides a standardized mechanism for handling errors using status codes, error details, and optional metadata.

This Example gives the usage and implementation of how to handle the Errors/Exceptions in gRPC,
shows how to extract error information from a failed RPC and setting and reading RPC error details.

If a gRPC call completes successfully the server returns an OK status to the client (depending on the language the OK status may or may not be directly used in your code).

If an error occurs gRPC returns one of its error status codes with error message that provides further error details about what happened.

Error Propagation:
- When an error occurs on the server, gRPC stops processing the RPC and sends the error (status code, description, and optional details) to the client.
- On the client side, the error can be handled based on the status code.

Client Side Error Handling: 
 - The gRPC client typically throws an exception or returns an error object when an RPC fails.

Server Side Error Handling:
- Servers use the gRPC API to return errors explicitly using the grpc library's status functions.

gRPC uses predefined status codes to represent the outcome of an RPC call. These status codes are part of the Status object that is sent from the server to the client.
Each status code is accompanied by a human-readable description(Please refer https://github.com/grpc/grpc-java/blob/master/api/src/main/java/io/grpc/Status.java)

Refer the gRPC documentation for more details on Error Handling https://grpc.io/docs/guides/error/