/*
 * Copyright 2017 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.NameResolver.ServiceConfigParser;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the inner classes in {@link NameResolver}. */
@RunWith(JUnit4.class)
public class NameResolverTest {
  private final int defaultPort = 293;
  private final ProxyDetector proxyDetector = mock(ProxyDetector.class);
  private final SynchronizationContext syncContext =
      new SynchronizationContext(mock(UncaughtExceptionHandler.class));
  private final ServiceConfigParser parser = mock(ServiceConfigParser.class);
  private final ScheduledExecutorService scheduledExecutorService =
      mock(ScheduledExecutorService.class);
  private final ChannelLogger channelLogger = mock(ChannelLogger.class);
  private final Executor executor = Executors.newSingleThreadExecutor();
  private final String overrideAuthority = "grpc.io";

  @Test
  public void args() {
    NameResolver.Args args = createArgs();
    assertThat(args.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args.getSynchronizationContext()).isSameInstanceAs(syncContext);
    assertThat(args.getServiceConfigParser()).isSameInstanceAs(parser);
    assertThat(args.getScheduledExecutorService()).isSameInstanceAs(scheduledExecutorService);
    assertThat(args.getChannelLogger()).isSameInstanceAs(channelLogger);
    assertThat(args.getOffloadExecutor()).isSameInstanceAs(executor);
    assertThat(args.getOverrideAuthority()).isSameInstanceAs(overrideAuthority);

    NameResolver.Args args2 = args.toBuilder().build();
    assertThat(args2.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args2.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args2.getSynchronizationContext()).isSameInstanceAs(syncContext);
    assertThat(args2.getServiceConfigParser()).isSameInstanceAs(parser);
    assertThat(args2.getScheduledExecutorService()).isSameInstanceAs(scheduledExecutorService);
    assertThat(args2.getChannelLogger()).isSameInstanceAs(channelLogger);
    assertThat(args2.getOffloadExecutor()).isSameInstanceAs(executor);
    assertThat(args2.getOverrideAuthority()).isSameInstanceAs(overrideAuthority);

    assertThat(args2).isNotSameInstanceAs(args);
    assertThat(args2).isNotEqualTo(args);
  }

  private NameResolver.Args createArgs() {
    return NameResolver.Args.newBuilder()
        .setDefaultPort(defaultPort)
        .setProxyDetector(proxyDetector)
        .setSynchronizationContext(syncContext)
        .setServiceConfigParser(parser)
        .setScheduledExecutorService(scheduledExecutorService)
        .setChannelLogger(channelLogger)
        .setOffloadExecutor(executor)
        .setOverrideAuthority(overrideAuthority)
        .build();
  }
}
