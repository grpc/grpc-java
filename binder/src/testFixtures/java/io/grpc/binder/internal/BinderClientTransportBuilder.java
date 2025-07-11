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

package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ChannelLogger;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import java.net.SocketAddress;

/**
 * Helps unit tests create {@link BinderTransport.BinderClientTransport} instances without having to
 * mention irrelevant details (go/tott/719).
 */
public class BinderClientTransportBuilder {
  private BinderClientTransportFactory factory;
  private SocketAddress serverAddress;
  private ChannelLogger channelLogger = new NoopChannelLogger();
  private io.grpc.internal.ClientTransportFactory.ClientTransportOptions options =
      new ClientTransportOptions();

  public BinderClientTransportBuilder setServerAddress(SocketAddress serverAddress) {
    this.serverAddress = checkNotNull(serverAddress);
    return this;
  }

  public BinderClientTransportBuilder setChannelLogger(ChannelLogger channelLogger) {
    this.channelLogger = checkNotNull(channelLogger);
    return this;
  }

  public BinderClientTransportBuilder setOptions(ClientTransportOptions options) {
    this.options = checkNotNull(options);
    return this;
  }

  public BinderClientTransportBuilder setFactory(BinderClientTransportFactory factory) {
    this.factory = checkNotNull(factory);
    return this;
  }

  public BinderTransport.BinderClientTransport build() {
    return factory.newClientTransport(
        checkNotNull(serverAddress), checkNotNull(options), checkNotNull(channelLogger));
  }
}
