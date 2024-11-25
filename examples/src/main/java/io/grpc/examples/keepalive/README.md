gRPC Keepalive Example
=====================

This example gives the usage and implementation of the Keepalives methods, configurations in gRPC Client and 
Server and how the communication happens between them.

HTTP/2 PING-based keepalives are a way to keep an HTTP/2 connection alive even when there is no data being transferred.
This is done by periodically sending a PING Frames to the other end of the connection.
HTTP/2 keepalives can improve performance and reliability of HTTP/2 connections, 
but it is important to configure the keepalive interval carefully.

gRPC sends http2 pings on the transport to detect if the connection is down.
If the ping is not acknowledged by the other side within a certain period, the connection will be closed.
Note that pings are only necessary when there's no activity on the connection.

Refer the gRPC documentation for more on Keepalive details and configurations https://grpc.io/docs/guides/keepalive/