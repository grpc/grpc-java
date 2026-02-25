/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.grpcservice;

import static org.junit.Assert.assertNotNull;

import io.grpc.CallCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig.GoogleGrpcConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig.HashedChannelCredentials;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InsecureGrpcChannelFactory}. */
@RunWith(JUnit4.class)
public class InsecureGrpcChannelFactoryTest {

  private static final class NoOpCallCredentials extends CallCredentials {
    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
        MetadataApplier applier) {
      applier.apply(new Metadata());
    }
  }

  @Test
  public void testCreateChannel() {
    InsecureGrpcChannelFactory factory = InsecureGrpcChannelFactory.getInstance();
    GrpcServiceConfig config = GrpcServiceConfig.builder()
        .googleGrpc(GoogleGrpcConfig.builder().target("localhost:8080")
            .hashedChannelCredentials(
                HashedChannelCredentials.of(InsecureChannelCredentials.create(), 0))
            .callCredentials(new NoOpCallCredentials()).build())
        .build();
    ManagedChannel channel = factory.createChannel(config);
    assertNotNull(channel);
    channel.shutdownNow();
  }
}
