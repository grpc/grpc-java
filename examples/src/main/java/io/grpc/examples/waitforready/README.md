gRPC Wait-For-Ready Example
=====================

This example gives the usage and implementation of the Wait-For-Ready feature.

This is a feature which can be used on a stub which will cause the RPCs to wait for the server to become available before sending the request.

When an RPC is created when the channel has failed to connect to the server, without Wait-for-Ready it will immediately return a failure, 
with Wait-for-Ready it will simply be queued until the connection becomes ready. 
The default is without Wait-for-Ready.

Example gives the Simple client that requests a greeting from the HelloWorldServer and defines waitForReady on the stub.

To test this flow need to follow below steps:
- run this client without a server running(client rpc should hang)
- start the server (client rpc should complete)
- run this client again (client rpc should complete nearly immediately)

Refer the gRPC documentation for more on Wait-For-Ready https://grpc.io/docs/guides/wait-for-ready/