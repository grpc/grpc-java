gRPC Manual Flow Control Example
=====================
Flow control is relevant for streaming RPC calls.

By default, gRPC will handle dealing with flow control. However, for specific
use cases, you may wish to take explicit control.

The default, if you do not disable auto requests, is for the gRPC framework to
automatically request 1 message at startup and after each onNext call,
request 1 more. With manual flow control, you explicitly do the request. Note
that acknowledgements (which is what lets the server know that the receiver can
handle more data) are sent after an onNext call. The onNext method is called
when there is both an undelivered message and an outstanding request.

The most common use case for manual flow control is to avoid requiring your
asynchronous onNext method to block when processing of the read message is being
done somewhere else.

Another, minor use case for manual flow control, is when there are lots of small
messages and you are using Netty. To avoid switching back and forth between the
application and network threads, you can specify a larger initial value (such
as 5) so that the application thread can have values waiting for it rather than
constantly having to block and wait for the network thread to provide the next
value.

### Outgoing Flow Control

The underlying layer (such as Netty) will make the write wait when there is no
space to write the next message. This causes the request stream to go into
a not ready state and the outgoing onNext method invocation waits. You can
explicitly check that the stream is ready for writing before calling onNext to
avoid blocking. This is done with `CallStreamObserver.isReady()`. You can
utilize this to start doing reads, which may allow
the other side of the channel to complete a write and then to do its own reads,
thereby avoiding deadlock.

### Incoming Manual Flow Control

An example use case is, you have a buffer where your onNext places values from
the stream. Manual flow control can be used to avoid buffer overflows. You could
use a blocking buffer, but you may not want to have the thread being used by
onNext block.

By default, gRPC will configure a stream to request one value at startup and
then at the completion of each "onNext" invocation requests one more message.
You can take control of this by disabling AutoRequest on the
request stream. If you do so, then you are responsible for asynchronously
telling the stream each time that you would like a new message to be
asynchronously sent to onNext when one is available. This is done by calling a
method on the request stream to request messages (while this has a count,
generally you request 1). Putting this request at the end of your onNext method
essentially duplicates the default behavior.

#### Client side (server or bidi streaming)

In the `ClientResponseObserver.beforeStart` method, call
`requestStream.disableAutoRequestWithInitial(1)`

When you are ready to begin processing the next value from the stream call
`requestStream.request(1)`

#### Server side (client or bidi streaming)

In your stub methods supporting streaming, add the following at the top

1. cast `StreamObserver<> responseObserver`
   to `ServerCallStreamObserver<> serverCallStreamObserver`
1. call `serverCallStreamObserver.disableAutoRequest()`

When you are ready to begin processing the next value from the stream call
`serverCallStreamObserver.request(1)`

### Related documents
Also see [gRPC Flow Control Users Guide][user guide]

 [user guide]: https://grpc.io/docs/guides/flow-control