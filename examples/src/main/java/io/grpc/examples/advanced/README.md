gRPC JSON Serialization Example
=====================

gRPC is a modern high-performance framework for building Remote Procedure Call (RPC) systems.
It commonly uses Protocol Buffers (Protobuf) as its serialization format, which is compact and efficient.
However, gRPC can also support JSON serialization when needed, typically for interoperability with 
systems or clients that do not use Protobuf.
This is an advanced example of how to swap out the serialization logic, Normal users do not need to do this.
This code is not intended to be a production-ready implementation, since JSON encoding is slow.
Additionally, JSON serialization as implemented may be not resilient to malicious input.

This advanced example uses Marshaller for JSON which marshals in the Protobuf 3 format described here
https://developers.google.com/protocol-buffers/docs/proto3#json

If you are considering implementing your own serialization logic, contact the grpc team at
https://groups.google.com/forum/#!forum/grpc-io
