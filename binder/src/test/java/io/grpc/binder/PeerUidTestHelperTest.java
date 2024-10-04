package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.testing.GrpcCleanupRule;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PeerUidTestHelperTest {

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private static final int FAKE_UID = 12345;

  private final AtomicReference<PeerUid> clientUidCapture = new AtomicReference<>();

  @Test
  public void keyPopulatedWithInterceptorAndHeader() throws Exception {
    makeServiceCall(/* includeInterceptor= */ true, /* includeUidInHeader= */ true, FAKE_UID);
    assertThat(clientUidCapture.get()).isEqualTo(new PeerUid(FAKE_UID));
  }

  @Test
  public void keyNotPopulatedWithInterceptorAndNoHeader() throws Exception {
    makeServiceCall(/* includeInterceptor= */ true, /* includeUidInHeader= */ false, /* uid= */ -1);
    assertThat(clientUidCapture.get()).isNull();
  }

  @Test
  public void keyNotPopulatedWithoutInterceptorAndWithHeader() throws Exception {
    makeServiceCall(
        /* includeInterceptor= */ false, /* includeUidInHeader= */ true, /* uid= */ FAKE_UID);
    assertThat(clientUidCapture.get()).isNull();
  }

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/method")
          .setType(MethodDescriptor.MethodType.UNARY)
          .build();

  private void makeServiceCall(boolean includeInterceptor, boolean includeUidInHeader, int uid)
      throws Exception {
    ServerCallHandler<String, String> callHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              clientUidCapture.set(PeerUids.REMOTE_PEER.get());
              respObserver.onNext(req);
              respObserver.onCompleted();
            });
    ImmutableList<ServerInterceptor> interceptors;
    if (includeInterceptor) {
      interceptors = ImmutableList.of(PeerUidTestHelper.newTestPeerIdentifyingServerInterceptor());
    } else {
      interceptors = ImmutableList.of();
    }
    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test").addMethod(method, callHandler).build(),
            interceptors);

    InProcessServerBuilder server =
        InProcessServerBuilder.forName("test").directExecutor().addService(serviceDef);

    grpcCleanup.register(server.build().start());

    Channel channel = InProcessChannelBuilder.forName("test").directExecutor().build();
    grpcCleanup.register((ManagedChannel) channel);

    if (includeUidInHeader) {
      Metadata header = new Metadata();
      header.put(PeerUidTestHelper.UID_KEY, uid);
      channel =
          ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(header));
    }

    ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, "hello");
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
        return new String(stream.readAllBytes(), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
