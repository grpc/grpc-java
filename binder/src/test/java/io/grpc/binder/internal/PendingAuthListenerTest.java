package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import java.io.IOException;
import java.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class PendingAuthListenerTest {

  private static final MethodDescriptor<Void, Void> TEST_METHOD =
      TestMethodDescriptors.voidMethod();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Mock ServerCallHandler<Object, Object> next;
  @Mock ServerCall<Object, Object> call;
  @Mock ServerCall.Listener<Object> delegate;

  private final Metadata headers = new Metadata();
  private final PendingAuthListener<Object, Object> listener = new PendingAuthListener<>();

  @Before
  public void setUp() {
    when(next.startCall(call, headers)).thenReturn(delegate);
  }

  @Test
  public void onCallbacks_noOpBeforeStartCall() {
    listener.onReady();
    listener.onMessage("foo");
    listener.onHalfClose();
    listener.onComplete();

    verifyNoInteractions(delegate);
  }

  @Test
  public void onCallbacks_runsPendingCallbacksAfterStartCall() {
    String message = "foo";

    // Act 1
    listener.onReady();
    listener.onMessage(message);
    listener.startCall(call, headers, next);

    // Assert 1
    InOrder order = Mockito.inOrder(delegate);
    order.verify(delegate).onReady();
    order.verify(delegate).onMessage(message);

    // Act 2
    listener.onHalfClose();
    listener.onComplete();

    // Assert 2
    order.verify(delegate).onHalfClose();
    order.verify(delegate).onComplete();
  }

  @Test
  public void onCallbacks_withCancellation_runsPendingCallbacksAfterStartCall() {
    listener.onReady();
    listener.onCancel();
    listener.startCall(call, headers, next);

    InOrder order = Mockito.inOrder(delegate);
    order.verify(delegate).onReady();
    order.verify(delegate).onCancel();
  }

  @Test
  public void whenStartCallFails_closesTheCallWithInternalStatus() throws Exception {
    // Arrange
    ServerCallHandler<Void, Void> callHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              throw new IllegalStateException("ooops");
            });
    ManagedChannel channel = startServer(callHandler);

    // Act
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                ClientCalls.blockingUnaryCall(
                    channel,
                    TEST_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(Duration.ofSeconds(5)),
                    /* request= */ null));

    // Assert
    assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
  }

  private ManagedChannel startServer(ServerCallHandler<Void, Void> callHandler) throws IOException {
    String name = TestMethodDescriptors.SERVICE_NAME;
    ServerServiceDefinition serviceDef =
        ServerServiceDefinition.builder(name).addMethod(TEST_METHOD, callHandler).build();
    Server server =
        InProcessServerBuilder.forName(name)
            .addService(serviceDef)
            .intercept(
                new ServerInterceptor() {
                  @SuppressWarnings("unchecked")
                  @Override
                  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                      ServerCall<ReqT, RespT> call,
                      Metadata headers,
                      ServerCallHandler<ReqT, RespT> next) {

                    listener.startCall(
                        (ServerCall<Object, Object>) call,
                        headers,
                        (ServerCallHandler<Object, Object>) next);
                    return (ServerCall.Listener<ReqT>) listener;
                  }
                })
            .build()
            .start();
    ManagedChannel channel =
        InProcessChannelBuilder.forName(name)
            .intercept(
                new ClientInterceptor() {
                  @Override
                  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                    ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
                    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        delegate) {
                      @Override
                      public void start(Listener<RespT> responseListener, Metadata headers) {
                        ClientCall.Listener<RespT> wrappedListener =
                            new ForwardingClientCallListener.SimpleForwardingClientCallListener<
                                RespT>(responseListener) {};
                        super.start(wrappedListener, headers);
                      }
                    };
                  }
                })
            .build();

    grpcCleanupRule.register(server);
    grpcCleanupRule.register(channel);

    return channel;
  }
}
