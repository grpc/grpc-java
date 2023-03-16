/*
 * Copyright 2023 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.StatusRuntimeException;
import io.grpc.binder.internal.BinderTransport;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
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
public class PeerUidsTest {
  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private static final int FAKE_UID = 12345;

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/method")
          .setType(MethodDescriptor.MethodType.UNARY)
          .build();

  private final AtomicReference<PeerUid> clientUidCapture = new AtomicReference<>();

  @Test
  public void keyPopulatedWithInterceptor() throws Exception {
    makeServiceCall(/* populateUid= */ true, /* includeInterceptor= */ true);
    assertThat(clientUidCapture.get()).isEqualTo(new PeerUid(FAKE_UID));
  }

  @Test
  public void keyNotPopulatedWithoutInterceptor() throws Exception {
    makeServiceCall(/* populateUid= */ true, /* includeInterceptor= */ false);
    assertThat(clientUidCapture.get()).isNull();
  }

  @Test
  public void exceptionThrownWithoutUid() throws Exception {
    assertThrows(
        StatusRuntimeException.class,
        () -> makeServiceCall(/* populateUid= */ false, /* includeInterceptor= */ true));
  }

  private void makeServiceCall(boolean populateUid, boolean includeInterceptor) throws Exception {
    ServerCallHandler<String, String> callHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              clientUidCapture.set(PeerUids.REMOTE_PEER.get());
              respObserver.onNext(req);
              respObserver.onCompleted();
            });
    ImmutableList<ServerInterceptor> interceptors;
    if (includeInterceptor) {
      interceptors = ImmutableList.of(PeerUids.newPeerIdentifyingServerInterceptor());
    } else {
      interceptors = ImmutableList.of();
    }
    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test").addMethod(method, callHandler).build(),
            interceptors);

    InProcessServerBuilder server =
        InProcessServerBuilder.forName("test").directExecutor().addService(serviceDef);
    if (populateUid) {
      server.addTransportFilter(
          new ServerTransportFilter() {
            @Override
            public Attributes transportReady(Attributes attributes) {
              return attributes.toBuilder().set(BinderTransport.REMOTE_UID, FAKE_UID).build();
            }
          });
    }
    grpcCleanup.register(server.build().start());
    ManagedChannel channel = InProcessChannelBuilder.forName("test").directExecutor().build();

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
        return new String(ByteStreams.toByteArray(stream), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}