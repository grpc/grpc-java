gRPC Custom Load Balance Example
=====================

One of the key features of gRPC is load balancing, which allows requests from clients to be distributed across multiple servers. 
This helps prevent any one server from becoming overloaded and allows the system to scale up by adding more servers.

A gRPC load balancing policy is given a list of server IP addresses by the name resolver. 
The policy is responsible for maintaining connections (subchannels) to the servers and picking a connection to use when an RPC is sent.

This example gives the details about how we can implement our own custom load balance policy, If the built-in policies does not meet your requirements
and follow below steps for the same.

 - Register your implementation in the load balancer registry so that it can be referred to from the service config
 - Parse the JSON configuration object of your implementation. This allows your load balancer to be configured in the service config with any arbitrary JSON you choose to support
 - Manage what backends to maintain a connection with
 - Implement a picker that will choose which backend to connect to when an RPC is made. Note that this needs to be a fast operation as it is on the RPC call path
 - To enable your load balancer, configure it in your service config

Refer the gRPC documentation for more details https://grpc.io/docs/guides/custom-load-balancing/
