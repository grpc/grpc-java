/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.Internal;
import io.grpc.InternalChannelz;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.Version;
import javax.annotation.Nullable;

/**
 * gRPC wrapper for {@link Http2ConnectionHandler}.
 */
@Internal
public abstract class GrpcHttp2ConnectionHandler extends Http2ConnectionHandler {
  static final int ADAPTIVE_CUMULATOR_COMPOSE_MIN_SIZE_DEFAULT = 1024;
  static final Cumulator ADAPTIVE_CUMULATOR =
      new NettyAdaptiveCumulator(ADAPTIVE_CUMULATOR_COMPOSE_MIN_SIZE_DEFAULT);

  @Nullable
  protected final ChannelPromise channelUnused;
  private final ChannelLogger negotiationLogger;
  private static final boolean usingPre4_1_111_Netty;

  static {
    // Netty 4.1.111 introduced a change in the behavior of duplicate() method
    // that breaks the assumption of the cumulator. We need to detect this version
    // and adjust the behavior accordingly.

    boolean identifiedOldVersion = false;
    try {
      Version version = Version.identify().get("netty-buffer");
      if (version != null) {
        String[] split = version.artifactVersion().split("\\.");
        if (split.length >= 3
            && Integer.parseInt(split[0]) == 4
            && Integer.parseInt(split[1]) <= 1
            && Integer.parseInt(split[2]) < 111) {
          identifiedOldVersion = true;
        }
      }
    } catch (Exception e) {
      // Ignore, we'll assume it's a new version.
    }
    usingPre4_1_111_Netty = identifiedOldVersion;
  }

  @SuppressWarnings("this-escape")
  protected GrpcHttp2ConnectionHandler(
      ChannelPromise channelUnused,
      Http2ConnectionDecoder decoder,
      Http2ConnectionEncoder encoder,
      Http2Settings initialSettings,
      ChannelLogger negotiationLogger) {
    super(decoder, encoder, initialSettings);
    this.channelUnused = channelUnused;
    this.negotiationLogger = negotiationLogger;
    if (usingPre4_1_111_Netty()) {
      // We need to use the adaptive cumulator only if we're using a version of Netty that
      // doesn't have the behavior that breaks it.
      setCumulator(ADAPTIVE_CUMULATOR);
    }
  }

  @VisibleForTesting
  static boolean usingPre4_1_111_Netty() {
    return usingPre4_1_111_Netty;
  }

  /**
   * Same as {@link #handleProtocolNegotiationCompleted(
   *   Attributes, io.grpc.InternalChannelz.Security)}
   * but with no {@link io.grpc.InternalChannelz.Security}.
   *
   * @deprecated Use the two argument method instead.
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester") // the caller should consider providing securityInfo
  public void handleProtocolNegotiationCompleted(Attributes attrs) {
    handleProtocolNegotiationCompleted(attrs, /*securityInfo=*/ null);
  }

  /**
   * Triggered on protocol negotiation completion.
   *
   * <p>It must me called after negotiation is completed but before given handler is added to the
   * channel.
   *
   * @param attrs arbitrary attributes passed after protocol negotiation (eg. SSLSession).
   * @param securityInfo informs channelz about the security protocol.
   */
  public void handleProtocolNegotiationCompleted(
      Attributes attrs, InternalChannelz.Security securityInfo) {
  }

  /**
   * Returns the channel logger for the given channel context.
   */
  public ChannelLogger getNegotiationLogger() {
    checkState(negotiationLogger != null, "NegotiationLogger must not be null");
    return negotiationLogger;
  }

  /**
   * Calling this method indicates that the channel will no longer be used.  This method is roughly
   * the same as calling {@link #close} on the channel, but leaving the channel alive.  This is
   * useful if the channel will soon be deregistered from the executor and used in a non-Netty
   * context.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void notifyUnused() {
    channelUnused.setSuccess(null);
  }

  /** Get the attributes of the EquivalentAddressGroup used to create this transport. */
  public Attributes getEagAttributes() {
    return Attributes.EMPTY;
  }

  /**
   * Returns the authority of the server. Only available on the client-side.
   *
   * @throws UnsupportedOperationException if on server-side
   */
  public String getAuthority() {
    throw new UnsupportedOperationException();
  }
}
