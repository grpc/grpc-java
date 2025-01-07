/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.InternalNettyChannelCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.s2a.internal.channel.S2AHandshakerServiceChannel;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.grpc.s2a.internal.handshaker.S2AProtocolNegotiatorFactory;
import io.grpc.s2a.internal.handshaker.S2AStub;
import javax.annotation.concurrent.NotThreadSafe;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Configures gRPC to use S2A for transport security when establishing a secure channel. Only for
 * use on the client side of a gRPC connection.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/11533")
public final class S2AChannelCredentials {
  /**
   * Creates a channel credentials builder for establishing an S2A-secured connection.
   *
   * @param s2aAddress the address of the S2A server used to secure the connection.
   * @param s2aChannelCredentials the credentials to be used when connecting to the S2A.
   * @return a {@code S2AChannelCredentials.Builder} instance.
   */
  public static Builder newBuilder(String s2aAddress, ChannelCredentials s2aChannelCredentials) {
    checkArgument(!isNullOrEmpty(s2aAddress), "S2A address must not be null or empty.");
    checkNotNull(s2aChannelCredentials, "S2A channel credentials must not be null");
    return new Builder(s2aAddress, s2aChannelCredentials);
  }

  /** Builds an {@code S2AChannelCredentials} instance. */
  @NotThreadSafe
  public static final class Builder {
    private final String s2aAddress;
    private final ChannelCredentials s2aChannelCredentials;
    private @Nullable S2AIdentity localIdentity = null;
    private @Nullable S2AStub stub = null;

    Builder(String s2aAddress, ChannelCredentials s2aChannelCredentials) {
      this.s2aAddress = s2aAddress;
      this.s2aChannelCredentials = s2aChannelCredentials;
    }

    /**
     * Sets the local identity of the client in the form of a SPIFFE ID. The client may set at most
     * 1 local identity. If no local identity is specified, then the S2A chooses a default local
     * identity, if one exists.
     */
    @CanIgnoreReturnValue
    public Builder setLocalSpiffeId(String localSpiffeId) {
      checkNotNull(localSpiffeId);
      checkArgument(localIdentity == null, "localIdentity is already set.");
      localIdentity = S2AIdentity.fromSpiffeId(localSpiffeId);
      return this;
    }

    /**
     * Sets the local identity of the client in the form of a hostname. The client may set at most 1
     * local identity. If no local identity is specified, then the S2A chooses a default local
     * identity, if one exists.
     */
    @CanIgnoreReturnValue
    public Builder setLocalHostname(String localHostname) {
      checkNotNull(localHostname);
      checkArgument(localIdentity == null, "localIdentity is already set.");
      localIdentity = S2AIdentity.fromHostname(localHostname);
      return this;
    }

    /**
     * Sets the local identity of the client in the form of a UID. The client may set at most 1
     * local identity. If no local identity is specified, then the S2A chooses a default local
     * identity, if one exists.
     */
    @CanIgnoreReturnValue
    public Builder setLocalUid(String localUid) {
      checkNotNull(localUid);
      checkArgument(localIdentity == null, "localIdentity is already set.");
      localIdentity = S2AIdentity.fromUid(localUid);
      return this;
    }

    /**
     * Sets the stub to use to communicate with S2A. This is only used for testing that the
     * stream to S2A gets closed.
     */
    public Builder setStub(S2AStub stub) {
      checkNotNull(stub);
      this.stub = stub;
      return this;
    }

    public ChannelCredentials build() {
      return InternalNettyChannelCredentials.create(buildProtocolNegotiatorFactory());
    }

    InternalProtocolNegotiator.ClientFactory buildProtocolNegotiatorFactory() {
      ObjectPool<Channel> s2aChannelPool =
          SharedResourcePool.forResource(
              S2AHandshakerServiceChannel.getChannelResource(s2aAddress, s2aChannelCredentials));
      checkNotNull(s2aChannelPool, "s2aChannelPool");
      return S2AProtocolNegotiatorFactory.createClientFactory(localIdentity, s2aChannelPool, stub);
    }
  }

  private S2AChannelCredentials() {}
}