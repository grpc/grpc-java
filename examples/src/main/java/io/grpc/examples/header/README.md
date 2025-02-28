gRPC Custom Header Example
=====================

This example gives the usage and implementation of how to create and process(send/receive) the custom headers between Client and Server 
using the interceptors (HeaderServerInterceptor, ClientServerInterceptor) along with Metadata.

Metadata is a side channel that allows clients and servers to provide information to each other that is associated with an RPC.
gRPC metadata is a key-value pair of data that is sent with initial or final gRPC requests or responses. 
It is used to provide additional information about the call, such as authentication credentials, 
tracing information, or custom headers.

gRPC metadata can be used to send custom headers to the server or from the server to the client. 
This can be used to implement application-specific features, such as load balancing, 
rate limiting or providing detailed error messages from the server to the client.

Refer the gRPC documentation for more on Metadata/Headers https://grpc.io/docs/guides/metadata/