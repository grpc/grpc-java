package io.grpc.binder;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/** Class which helps set up {@link PeerUids} to be used in tests. */
public final class PeerUidTestHelper {

  /** The UID of the calling package is set with the value of this key. */
  public static final Metadata.Key<Integer> UID_KEY =
      Metadata.Key.of("binder-remote-uid-for-unit-testing", PeerUidTestMarshaller.INSTANCE);

  /**
   * Creates an interceptor that associates the {@link PeerUids#REMOTE_PEER} key in the request
   * {@link Context} with a UID provided by the client in the {@link #UID_KEY} request header, if
   * present.
   *
   * <p>The returned interceptor works with any gRPC transport but is meant for in-process unit
   * testing of gRPC/binder services that depend on {@link PeerUids}.
   */
  public static ServerInterceptor newTestPeerIdentifyingServerInterceptor() {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (headers.containsKey(UID_KEY)) {
          Context context =
              Context.current().withValue(PeerUids.REMOTE_PEER, new PeerUid(headers.get(UID_KEY)));
          return Contexts.interceptCall(context, call, headers, next);
        }
        return next.startCall(call, headers);
      }
    };
  }

  private PeerUidTestHelper() {}

  private static class PeerUidTestMarshaller implements Metadata.AsciiMarshaller<Integer> {

    public static final PeerUidTestMarshaller INSTANCE = new PeerUidTestMarshaller();

    @Override
    public String toAsciiString(Integer value) {
      return value.toString();
    }

    @Override
    public Integer parseAsciiString(String serialized) {
      return Integer.parseInt(serialized);
    }
  }
  ;
}
