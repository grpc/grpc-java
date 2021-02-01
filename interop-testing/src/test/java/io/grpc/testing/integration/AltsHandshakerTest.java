/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import io.grpc.ChannelCredentials;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.alts.AltsChannelCredentials;
import io.grpc.alts.AltsServerCredentials;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AltsHandshakerTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
  private Server handshakerServer;
  private Server testServer;
  private ManagedChannel channel;

  private void startAltsServer() throws Exception {
    ServerCredentials serverCredentials = AltsServerCredentials.newBuilder()
        .enableUntrustedAltsForTesting()
        .setHandshakerAddressForTesting("localhost:" + handshakerServer.getPort())
        .build();
    testServer = grpcCleanup.register(
        Grpc.newServerBuilderForPort(0, serverCredentials)
            .addService(new TestServiceGrpc.TestServiceImplBase() {
              @Override
              public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> so) {
                so.onNext(SimpleResponse.getDefaultInstance());
                so.onCompleted();
              }
            })
            .build())
        .start();
  }

  @Before
  public void setup() throws Exception {
    handshakerServer = grpcCleanup.register(ServerBuilder.forPort(0)
        .addService(new AltsHandshakerTestService()).build()).start();
    startAltsServer();

    ChannelCredentials channelCredentials = AltsChannelCredentials.newBuilder()
        .enableUntrustedAltsForTesting()
        .setHandshakerAddressForTesting("localhost:" + handshakerServer.getPort()).build();
    channel = grpcCleanup.register(
        Grpc.newChannelBuilderForAddress("localhost", testServer.getPort(), channelCredentials)
            .build());
  }

  @Test
  @Ignore // flaky. Latency high and handshake often exceeds deadline.
  public void testAlts() {
    TestServiceGrpc.TestServiceBlockingStub blockingStub = TestServiceGrpc.newBlockingStub(channel)
        .withDeadline(Deadline.after(200, TimeUnit.SECONDS));
    final SimpleRequest request = SimpleRequest.newBuilder()
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[10])))
            .build();
    assertEquals(SimpleResponse.getDefaultInstance(), blockingStub.unaryCall(request));
  }
}
