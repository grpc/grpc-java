gRPC Pre-Serialized Messages Example
=====================

This example gives the usage and implementation of pre-serialized request and response messages 
communication/exchange between grpc client and server by using ByteArrayMarshaller which produces 
a byte[] instead of decoding into typical POJOs.

This is a performance optimization that can be useful if you read the request/response from on-disk or a database
where it is already serialized, or if you need to send the same complicated message to many clients and servers.
The same approach can avoid deserializing requests/responses, to be stored in a database.

It shows how to modify MethodDescriptor to use bytes as the response instead of HelloReply. By
adjusting toBuilder() you can choose which of the request and response are bytes.
The generated bindService() uses ServerCalls to make RPC handlers, Since the generated
bindService() won't expect byte[] in the AsyncService, this uses ServerCalls directly.

Stubs use ClientCalls to send RPCs, Since the generated stub won't have byte[] in its
method signature, this uses ClientCalls directly.