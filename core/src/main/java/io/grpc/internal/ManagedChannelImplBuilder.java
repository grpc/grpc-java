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

package io.grpc.internal;

import com.google.common.base.Preconditions;
import io.grpc.ManagedChannelBuilder;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * Default managed channel builder, for usage in Transport implementations.
 */
public final class ManagedChannelImplBuilder
    extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {

  private boolean authorityCheckerDisabled;

  /**
   * An interface for Transport implementors to provide the {@link ClientTransportFactory}
   * appropriate for the channel.
   */
  public interface ClientTransportFactoryBuilder {
    ClientTransportFactory buildClientTransportFactory();
  }

  /**
   * An interface for Transport implementors to provide a default port to {@link
   * io.grpc.NameResolver} for use in cases where the target string doesn't include a port. The
   * default implementation returns {@link GrpcUtil#DEFAULT_PORT_SSL}.
   */
  public interface ChannelBuilderDefaultPortProvider {
    int getDefaultPort();
  }

  /**
   * Default implementation of {@link ChannelBuilderDefaultPortProvider} that returns a fixed port.
   */
  public static final class FixedPortProvider implements ChannelBuilderDefaultPortProvider {
    private final int port;

    public FixedPortProvider(int port) {
      this.port = port;
    }

    @Override
    public int getDefaultPort() {
      return port;
    }
  }

  private final class ManagedChannelDefaultPortProvider implements
      ChannelBuilderDefaultPortProvider {
    @Override
    public int getDefaultPort() {
      return ManagedChannelImplBuilder.super.getDefaultPort();
    }
  }

  private final ClientTransportFactoryBuilder clientTransportFactoryBuilder;
  private final ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider;

  /**
   * Creates a new managed channel builder with a target string, which can be either a valid {@link
   * io.grpc.NameResolver}-compliant URI, or an authority string. Transport implementors must
   * provide client transport factory builder, and may set custom channel default port provider.
   */
  public ManagedChannelImplBuilder(String target,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(target);
    this.clientTransportFactoryBuilder = Preconditions
        .checkNotNull(clientTransportFactoryBuilder, "clientTransportFactoryBuilder");

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
  }

  /**
   * Creates a new managed channel builder with the given server address, authority string of the
   * channel. Transport implementors must provide client transport factory builder, and may set
   * custom channel default port provider.
   */
  public ManagedChannelImplBuilder(SocketAddress directServerAddress, String authority,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(directServerAddress, authority);
    this.clientTransportFactoryBuilder = Preconditions
        .checkNotNull(clientTransportFactoryBuilder, "clientTransportFactoryBuilder");

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
  }

  @Override
  protected ClientTransportFactory buildTransportFactory() {
    return clientTransportFactoryBuilder.buildClientTransportFactory();
  }

  @Override
  protected int getDefaultPort() {
    return channelBuilderDefaultPortProvider.getDefaultPort();
  }

  /** Disable the check whether the authority is valid. */
  public ManagedChannelImplBuilder disableCheckAuthority() {
    authorityCheckerDisabled = true;
    return this;
  }

  /** Enable previously disabled authority check. */
  public ManagedChannelImplBuilder enableCheckAuthority() {
    authorityCheckerDisabled = false;
    return this;
  }

  @Override
  protected String checkAuthority(String authority) {
    if (authorityCheckerDisabled) {
      return authority;
    }
    return super.checkAuthority(authority);
  }

  @Override
  public void setStatsEnabled(boolean value) {
    super.setStatsEnabled(value);
  }

  @Override
  public void setStatsRecordStartedRpcs(boolean value) {
    super.setStatsRecordStartedRpcs(value);
  }

  @Override
  public void setStatsRecordFinishedRpcs(boolean value) {
    super.setStatsRecordFinishedRpcs(value);
  }

  @Override
  public void setStatsRecordRealTimeMetrics(boolean value) {
    super.setStatsRecordRealTimeMetrics(value);
  }

  @Override
  public void setTracingEnabled(boolean value) {
    super.setTracingEnabled(value);
  }

  @Override
  public ObjectPool<? extends Executor> getOffloadExecutorPool() {
    return super.getOffloadExecutorPool();
  }

  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    throw new UnsupportedOperationException("ClientTransportFactoryBuilder is required");
  }

  public static ManagedChannelBuilder<?> forTarget(String target) {
    throw new UnsupportedOperationException("ClientTransportFactoryBuilder is required");
  }
}
