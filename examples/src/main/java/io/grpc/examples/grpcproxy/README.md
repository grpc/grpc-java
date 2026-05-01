gRPC Proxy Example
=====================

A gRPC proxy is a component or tool that acts as an intermediary between gRPC clients and servers, 
facilitating communication while offering additional capabilities.
Proxies are used in scenarios where you need to handle tasks like load balancing, routing, monitoring, 
or providing a bridge between gRPC and other protocols.

GrpcProxy itself can be used unmodified to proxy any service for both unary and streaming.
It doesn't care what type of messages are being used.
The Registry class causes it to be called for any inbound RPC, and uses plain bytes for messages which avoids marshalling
messages and the need for Protobuf schema information.

We can run the Grpc Proxy with Route guide example to see how it works by running the below

Route guide has unary and streaming RPCs which makes it a nice showcase, and we can run each in a separate terminal window.

./build/install/examples/bin/route-guide-server 
./build/install/examples/bin/grpc-proxy 
./build/install/examples/bin/route-guide-client localhost:8981

you can verify the proxy is being used by shutting down the proxy and seeing the client fail.