gRPC Multiplex Example
=====================

This example gives the implementation of a gRPC Channel can be shared by two stubs and two services
can share a gRPC Server, also this example illustrates how to perform both types of sharing.

gRPC server that serves both the Greeting and Echo services, gRPC client that shares a channel across
multiple stubs to a single service and across services being provided by one server process.