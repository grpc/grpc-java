gRPC Wait-For-Ready Example
=====================

This example gives the usage and implementation of the Wait-For-Ready feature.

This feature can be activated on a client stub, ensuring that Remote Procedure Calls (RPCs) are held until the server is ready to receive them. 
By waiting for the server to become available before sending requests, this mechanism enhances reliability, 
particularly in situations where server availability may be delayed or unpredictable.

When an RPC is initiated and the channel fails to connect to the server, its behavior depends on the Wait-for-Ready option:

- Without Wait-for-Ready (Default Behavior):

  - The RPC will immediately fail if the channel cannot establish a connection, providing prompt feedback about the connectivity issue.

- With Wait-for-Ready:
  
  - The RPC will not fail immediately. Instead, it will be queued and will wait until the connection is successfully established.
    This approach is beneficial for handling temporary network disruptions more gracefully, ensuring the RPC is eventually executed once the connection is ready.


Example gives the Simple client that requests a greeting from the HelloWorldServer and defines waitForReady on the stub.

To test this flow need to follow below steps:
- run this client without a server running(client rpc should hang)
- start the server (client rpc should complete)
- run this client again (client rpc should complete nearly immediately)

Refer the gRPC documentation for more on Wait-For-Ready https://grpc.io/docs/guides/wait-for-ready/