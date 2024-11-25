gRPC Deadline Example
=====================

A Deadline is used to specify a point in time past which a client is unwilling to wait for a response from a server.
This simple idea is very important in building robust distributed systems.
Clients that do not wait around unnecessarily and servers that know when to give up processing requests will improve the resource utilization and latency of your system.

Note that while some language APIs have the concept of a deadline, others use the idea of a timeout. 
When an API asks for a deadline, you provide a point in time which the call should not go past.
A timeout is the max duration of time that the call can take. 
A timeout can be converted to a deadline by adding the timeout to the current time when the application starts a call.

This Example gives usage and implementation of Deadline on Server, Client and Propagation.

Refer the gRPC documentation for more details on Deadlines https://grpc.io/docs/guides/deadlines/