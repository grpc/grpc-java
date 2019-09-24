package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls.CallType;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.testing.protobuf.SimpleServiceGrpc.SimpleServiceBlockingStub;
import io.grpc.testing.protobuf.SimpleServiceGrpc.SimpleServiceFutureStub;
import io.grpc.testing.protobuf.SimpleServiceGrpc.SimpleServiceImplBase;
import io.grpc.testing.protobuf.SimpleServiceGrpc.SimpleServiceStub;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AbstractStubCallTypeTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final Server server =
      grpcCleanup.register(
          InProcessServerBuilder
              .forName("test")
              .addService(new TestSimpleServiceServerImpl())
              .build());

  @Before
  public void setUp() throws Exception {
    server.start();
  }

  @Test
  public void blockingStub_shouldAddCallTypeBlocking() {
    ManagedChannel chan = InProcessChannelBuilder.forName("test")
        .intercept(new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            CallType callType = callOptions.getOption(ClientCalls.CALL_TYPE_OPTION);

            assertThat(callType).isNotNull();
            assertThat(callType).isEqualTo(CallType.BLOCKING);

            return next.newCall(method, callOptions);
          }
        }).build();
    SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(chan);
    SimpleResponse response = stub
        .unaryRpc(SimpleRequest.newBuilder().setRequestMessage("hello").build());

    assertThat(response.getResponseMessage()).isEqualTo("response of hello");
  }

  @Test
  public void futureStub_shouldNotSetCallType()
      throws ExecutionException, InterruptedException {
    ManagedChannel chan = InProcessChannelBuilder.forName("test")
        .intercept(new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            assertThat(callOptions.getOption(ClientCalls.CALL_TYPE_OPTION)).isNull();

            return next.newCall(method, callOptions);
          }
        }).build();
    SimpleServiceFutureStub stub = SimpleServiceGrpc.newFutureStub(chan);
    ListenableFuture<SimpleResponse> responseFuture = stub
        .unaryRpc(SimpleRequest.newBuilder().setRequestMessage("hello").build());

    assertThat(responseFuture.get().getResponseMessage()).isEqualTo("response of hello");
  }

  @Test
  public void asyncStub_shouldNotSetCallType() throws InterruptedException, ExecutionException {
    ManagedChannel chan = InProcessChannelBuilder.forName("test")
        .intercept(new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            assertThat(callOptions.getOption(ClientCalls.CALL_TYPE_OPTION)).isNull();

            return next.newCall(method, callOptions);
          }
        }).build();
    SimpleServiceStub stub = SimpleServiceGrpc.newStub(chan);
    final SettableFuture<Throwable> exceptionFuture = SettableFuture.create();

    stub.unaryRpc(SimpleRequest.newBuilder().setRequestMessage("hello").build(),
        new StreamObserver<SimpleResponse>() {
          @Override
          public void onNext(SimpleResponse value) {
            assertThat(value.getResponseMessage()).isEqualTo("response of hello");
          }

          @Override
          public void onError(Throwable t) {
            exceptionFuture.set(t);
          }

          @Override
          public void onCompleted() {
            exceptionFuture.set(null);
          }
        });

    assertThat(exceptionFuture.get()).isNull();
  }

  private static final class TestSimpleServiceServerImpl extends SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(
          SimpleResponse.newBuilder()
              .setResponseMessage("response of " + request.getRequestMessage())
              .build());
      responseObserver.onCompleted();
    }
  }
}
