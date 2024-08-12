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

package io.grpc.inprocess;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.ServerStreamTracer;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.testing.TestStreamTracer;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SizesReportedInProcessTransportTest extends InProcessTransportTest {
  private AnonymousInProcessSocketAddress address = new AnonymousInProcessSocketAddress();

  @After
  @Override
  public void tearDown() throws InterruptedException {
    super.tearDown();
    assertThat(address.getServer()).isNull();
  }

  @Override
  protected InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    InProcessServerBuilder builder = InProcessServerBuilder.forAddress(address)
              .maxInboundMetadataSize(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE)
            .assumedMessageSize(100);
    return new InProcessServer(builder, streamTracerFactories);
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    return new InProcessTransport(
            address, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
            testAuthority(server), USER_AGENT, eagAttrs(), false, 100);
  }

  @Override
  public void additionalInProcessTests(
          TestStreamTracer streamTracerSender, TestStreamTracer streamTracerReceiver) {
    Assert.assertEquals(100, streamTracerSender.getOutboundWireSize());
    Assert.assertEquals(100, streamTracerSender.getOutboundUncompressedSize());

    Assert.assertEquals(100, streamTracerReceiver.getInboundWireSize());
    Assert.assertEquals(100, streamTracerReceiver.getInboundUncompressedSize());
  }
}
