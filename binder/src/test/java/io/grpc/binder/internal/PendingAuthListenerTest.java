package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.annotation.Nullable;

import com.google.common.io.ByteStreams;

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
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.testing.GrpcCleanupRule;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(JUnit4.class)
public final class PendingAuthListenerTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Mock
  ServerCallHandler<Object, Object> next;
  @Mock
  ServerCall<Object, Object> call;
  @Mock
  ServerCall.Listener<Object> delegate;

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
    String name = "test_server";
    AtomicBoolean closed = new AtomicBoolean(false);
    MethodDescriptor<String, String> method =
        MethodDescriptor
            .newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
            .setFullMethodName("test_server/method")
            .setType(MethodDescriptor.MethodType.UNARY)
            .build();
    ServerCallHandler<String, String> callHandler =
        ServerCalls.asyncUnaryCall((req, respObserver) -> {
          throw new IllegalStateException("ooops");
        });
    ServerServiceDefinition serviceDef =
        ServerServiceDefinition.builder(name).addMethod(method, callHandler).build();
    Server server =
        InProcessServerBuilder.forName(name).addService(serviceDef).intercept(
            new ServerInterceptor() {
              @SuppressWarnings("unchecked")
              @Override
              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                  ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

                listener.startCall((ServerCall<Object, Object>) call, headers, (ServerCallHandler<Object,
                    Object>) next);
                return (ServerCall.Listener<ReqT>) listener;
              }
          }).build().start();
    ManagedChannel channel =
        InProcessChannelBuilder.forName(name).intercept(new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate){
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            ClientCall.Listener<RespT> wrappedListener =
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                    responseListener){
                      @Override
                      public void onClose(Status status, Metadata trailers) {
                        super.onClose(status, trailers);
                        closed.set(true);
                      }
                    };
            super.start(wrappedListener, headers);
          }
        };
      }
    }).build();
    grpcCleanupRule.register(server);
    grpcCleanupRule.register(channel);

    // Act
    assertThrows(StatusRuntimeException.class, () -> ClientCalls.blockingUnaryCall(channel,
        method, CallOptions.DEFAULT.withDeadlineAfter(Duration.ofSeconds(5)), "foo"));

    // Assert
    assertThat(closed.get()).isTrue();
  }


  private static class StringMarshaller implements MethodDescriptor.Marshaller<String> {
    public static final StringMarshaller INSTANCE = new StringMarshaller();

    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(ByteStreams.toByteArray(stream), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
