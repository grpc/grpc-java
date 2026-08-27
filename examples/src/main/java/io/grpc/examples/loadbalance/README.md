gRPC Load Balance Example
=====================

One of the key features of gRPC is load balancing, which allows requests from clients to be distributed across multiple servers. 
This helps prevent any one server from becoming overloaded and allows the system to scale up by adding more servers.

A gRPC load balancing policy is given a list of server IP addresses by the name resolver. 
The policy is responsible for maintaining connections (subchannels) to the servers and picking a connection to use when an RPC is sent.

By default, the pick_first policy will be used. 
This policy actually does no load balancing but just tries each address it gets from the name resolver and uses the first one it can connect to.
By updating the gRPC service config you can also switch to using round_robin that connects to every address it gets and rotates through the connected backends for each RPC.
There are also some other load balancing policies available, but the exact set varies by language.

This example gives the details about how to implement Load Balance in gRPC, If the built-in policies does not meet your requirements
you can implement your own custom load balance [Custom Load Balance](src/main/java/io/grpc/examples/customloadbalance)

gRPC supports both client side and server side load balancing but by default gRPC uses client side load balancing.

Refer the gRPC documentation for more details on Load Balancing https://grpc.io/blog/grpc-load-balancing/