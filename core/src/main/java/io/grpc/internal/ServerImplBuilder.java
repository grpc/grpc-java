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
import io.grpc.Deadline;
import io.grpc.InternalChannelz;
import io.grpc.ServerBuilder;
import io.grpc.ServerStreamTracer;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Default builder for {@link io.grpc.Server} instances, for usage in Transport implementations.
 */
public final class ServerImplBuilder extends AbstractServerImplBuilder<ServerImplBuilder> {
  private final ClientTransportServersBuilder clientTransportServersBuilder;

  /**
   * An interface to provide to provide transport specific information for the server. This method
   * is meant for Transport implementors and should not be used by normal users.
   */
  public interface ClientTransportServersBuilder {
    List<? extends InternalServer> buildClientTransportServers(
        List<? extends ServerStreamTracer.Factory> streamTracerFactories);
  }

  /**
   * Creates a new server builder with given transport servers provider.
   */
  public ServerImplBuilder(ClientTransportServersBuilder clientTransportServersBuilder) {
    this.clientTransportServersBuilder = Preconditions
        .checkNotNull(clientTransportServersBuilder, "clientTransportServersBuilder");
  }

  @Override
  protected List<? extends InternalServer> buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
    return clientTransportServersBuilder.buildClientTransportServers(streamTracerFactories);
  }

  @Override
  public void setDeadlineTicker(Deadline.Ticker ticker) {
    super.setDeadlineTicker(ticker);
  }

  @Override
  public void setTracingEnabled(boolean value) {
    super.setTracingEnabled(value);
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
  public InternalChannelz getChannelz() {
    return super.getChannelz();
  }

  @Override
  public ObjectPool<? extends Executor> getExecutorPool() {
    return super.getExecutorPool();
  }

  @Override
  public ServerImplBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS not supported in ServerImplBuilder");
  }

  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException("ClientTransportServersBuilder is required");
  }
}
