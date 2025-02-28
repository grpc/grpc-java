gRPC Name Resolve Example
=====================

This example explains standard name resolution process and how to implement it using the Name Resolver component.

Name Resolution is fundamentally about Service Discovery.
Name Resolution refers to the process of converting a name into an address and 
Name Resolver is the component that implements the Name Resolution process.

When sending gRPC Request, Client must determine the IP address of the Service Name,
By Default DNS Name Resolution will be used when request received from the gRPC client.

The Name Resolver in gRPC is necessary because clients often don’t know the exact IP address or port of the server 
they need to connect to.

The client registers an implementation of a **name resolver provider** to a process-global **registry** close to the start of the process. 
The name resolver provider will be called by the **gRPC library** with a **target strings** intended for the custom name resolver. 
Given that target string, the name resolver provider will return an instance of a **name resolver**, 
which will interact with the client connection to direct the request according to the target string.

Refer the gRPC documentation for more on Name Resolution and Custom Name Resolution 
https://grpc.io/docs/guides/custom-name-resolution/