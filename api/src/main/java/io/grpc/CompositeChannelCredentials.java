/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc;

import com.google.common.base.Preconditions;

/**
 * {@code ChannelCredentials} which use per-RPC {@link CallCredentials}. If the {@code
 * ChannelCredentials} has multiple {@code CallCredentials} (e.g., a composite credential inside a
 * composite credential), then all of the {@code CallCredentials} should be used; one {@code
 * CallCredentials} does not override another.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
public final class CompositeChannelCredentials extends ChannelCredentials {
  public static ChannelCredentials create(
      ChannelCredentials channelCreds, CallCredentials callCreds) {
    return new CompositeChannelCredentials(channelCreds, callCreds);
  }

  private final ChannelCredentials channelCredentials;
  private final CallCredentials callCredentials;

  private CompositeChannelCredentials(ChannelCredentials channelCreds, CallCredentials callCreds) {
    this.channelCredentials = Preconditions.checkNotNull(channelCreds, "channelCreds");
    this.callCredentials = Preconditions.checkNotNull(callCreds, "callCreds");
  }

  public ChannelCredentials getChannelCredentials() {
    return channelCredentials;
  }

  public CallCredentials getCallCredentials() {
    return callCredentials;
  }

  @Override
  public ChannelCredentials withoutBearerTokens() {
    return channelCredentials.withoutBearerTokens();
  }
}
