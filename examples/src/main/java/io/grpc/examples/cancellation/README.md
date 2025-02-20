gRPC Cancellation Example
=====================

When a gRPC client is no longer interested in the result of an RPC call,
it may cancel to signal this discontinuation of interest to the server.

Any abort of an ongoing RPC is considered "cancellation" of that RPC.
The common causes of cancellation are the client explicitly cancelling, the deadline expires, and I/O failures.
The service is not informed the reason for the cancellation.

There are two APIs for services to be notified of RPC cancellation: io.grpc.Context and ServerCallStreamObserver

Context listeners are called on a different thread, so need to be thread-safe.
The ServerCallStreamObserver cancellation callback is called like other StreamObserver callbacks, 
so the application may not need thread-safe handling.
Both APIs have thread-safe isCancelled() polling methods.

Refer the gRPC documentation for details on Cancellation of RPCs https://grpc.io/docs/guides/cancellation/
