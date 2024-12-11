gRPC Compression Example
=====================

This example shows how clients can specify compression options when performing RPCs, 
and how to enable compressed(i,e gzip) requests/responses for only particular method and in case of all methods by using the interceptors.

Compression is used to reduce the amount of bandwidth used when communicating between client/server or peers and 
can be enabled or disabled based on call or message level for all languages.

gRPC allows asymmetrically compressed communication, whereby a response may be compressed differently with the request, 
or not compressed at all.

Refer the gRPC documentation for more details on Compression https://grpc.io/docs/guides/compression/