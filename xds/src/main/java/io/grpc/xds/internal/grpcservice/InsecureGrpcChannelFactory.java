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

import io.grpc.Grpc;
import io.grpc.ManagedChannel;

/**
 * An insecure implementation of {@link GrpcServiceConfigChannelFactory} that creates a plaintext
 * channel. This is a stub implementation for channel creation until the GrpcService trusted server
 * implementation is completely implemented.
 */
public final class InsecureGrpcChannelFactory implements GrpcServiceConfigChannelFactory {

  private static final InsecureGrpcChannelFactory INSTANCE = new InsecureGrpcChannelFactory();

  private InsecureGrpcChannelFactory() {}

  public static InsecureGrpcChannelFactory getInstance() {
    return INSTANCE;
  }

  @Override
  public ManagedChannel createChannel(GrpcServiceConfig config) {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc = config.googleGrpc();
    return Grpc.newChannelBuilder(googleGrpc.target(),
        googleGrpc.hashedChannelCredentials().channelCredentials()).build();
  }
}
