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

package io.grpc.xds;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.LoadReportClient;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsTransportFactory;
import io.grpc.xds.internal.security.TlsContextManagerImpl;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public class GrpcXdsClientImpl extends XdsClientImpl {

  public GrpcXdsClientImpl(XdsTransportFactory xdsTransportFactory,
                           Bootstrapper.BootstrapInfo bootstrapInfo,
                           ScheduledExecutorService timeService,
                           BackoffPolicy.Provider backoffPolicyProvider,
                           Supplier<Stopwatch> stopwatchSupplier, TimeProvider timeProvider) {
    super(xdsTransportFactory, bootstrapInfo, timeService,
        backoffPolicyProvider, stopwatchSupplier, timeProvider, MessagePrinter.INSTANCE,
        new TlsContextManagerImpl(bootstrapInfo));
  }

  @VisibleForTesting
  @Override
  public Map<Bootstrapper.ServerInfo, LoadReportClient> getServerLrsClientMap() {
    return ImmutableMap.copyOf(serverLrsClientMap);
  }
}