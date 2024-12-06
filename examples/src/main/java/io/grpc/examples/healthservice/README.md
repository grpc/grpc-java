gRPC Health Service Example
=====================

The Health Service example provides a HelloWorld gRPC server that doesn't like short names along with a
health service. It also provides a client application which makes HelloWorld
calls and checks the health status.

The client application also shows how the round robin load balancer can
utilize the health status to avoid making calls to a service that is
not actively serving.
