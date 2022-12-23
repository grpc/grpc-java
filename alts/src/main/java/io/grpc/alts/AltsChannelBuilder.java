/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.alts;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import javax.annotation.Nullable;

/**
 * ALTS version of {@code ManagedChannelBuilder}. This class sets up a secure and authenticated
 * commmunication between two cloud VMs using ALTS.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4151")
public final class AltsChannelBuilder extends ForwardingChannelBuilder<AltsChannelBuilder> {
  private final NettyChannelBuilder delegate;
  private final AltsChannelCredentials.Builder credentialsBuilder =
      new AltsChannelCredentials.Builder();

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static final AltsChannelBuilder forTarget(String target) {
    return new AltsChannelBuilder(target);
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static AltsChannelBuilder forAddress(String name, int port) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port));
  }

  private AltsChannelBuilder(String target) {
    delegate = NettyChannelBuilder.forTarget(target);
  }

  /**
   * Adds an expected target service accounts. One of the added service accounts should match peer
   * service account in the handshaker result. Otherwise, the handshake fails.
   */
  public AltsChannelBuilder addTargetServiceAccount(String targetServiceAccount) {
    credentialsBuilder.addTargetServiceAccount(targetServiceAccount);
    return this;
  }

  /**
   * Enables untrusted ALTS for testing. If this function is called, we will not check whether ALTS
   * is running on Google Cloud Platform.
   */
  public AltsChannelBuilder enableUntrustedAltsForTesting() {
    credentialsBuilder.enableUntrustedAltsForTesting();
    return this;
  }

  /** Sets a new handshaker service address for testing. */
  public AltsChannelBuilder setHandshakerAddressForTesting(String handshakerAddress) {
    credentialsBuilder.setHandshakerAddressForTesting(handshakerAddress);
    return this;
  }

  @Override
  protected NettyChannelBuilder delegate() {
    return delegate;
  }

  @Override
  public ManagedChannel build() {
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(
        delegate(),
        credentialsBuilder.buildProtocolNegotiatorFactory());

    return delegate().build();
  }

  @VisibleForTesting
  @Nullable
  ProtocolNegotiator getProtocolNegotiatorForTest() {
    return credentialsBuilder.buildProtocolNegotiatorFactory().newNegotiator();
  }
}
