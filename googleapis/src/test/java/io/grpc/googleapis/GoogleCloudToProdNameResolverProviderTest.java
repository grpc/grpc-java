/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.googleapis;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.ChannelLogger;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverProvider;
import io.grpc.SynchronizationContext;
import io.grpc.Uri;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import java.net.URI;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Unit tests for {@link GoogleCloudToProdNameResolverProvider}. */
@RunWith(Parameterized.class)
public class GoogleCloudToProdNameResolverProviderTest {
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private final NameResolver.Args args = NameResolver.Args.newBuilder()
      .setDefaultPort(8080)
      .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(ServiceConfigParser.class))
      .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
      .setChannelLogger(mock(ChannelLogger.class))
      .build();

  private GoogleCloudToProdNameResolverProvider provider =
      new GoogleCloudToProdNameResolverProvider();

  @Parameters(name = "enableRfc3986UrisParam={0}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {{true}, {false}});
  }

  @Parameter public boolean enableRfc3986UrisParam;

  @Test
  public void provided() {
    for (NameResolverProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
        NameResolverProvider.class, getClass().getClassLoader())) {
      if (current instanceof GoogleCloudToProdNameResolverProvider) {
        return;
      }
    }
    fail("GoogleCloudToProdNameResolverProvider not registered");
  }

  @Test
  public void experimentalProvided() {
    for (NameResolverProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
        NameResolverProvider.class, getClass().getClassLoader())) {
      if (current instanceof GoogleCloudToProdExperimentalNameResolverProvider) {
        return;
      }
    }
    fail("GoogleCloudToProdExperimentalNameResolverProvider not registered");
  }

  @Test
  public void shouldProvideNameResolverOfExpectedType() {
    assertThat(newNameResolver(provider, "google-c2p:///foo.googleapis.com", args))
        .isInstanceOf(GoogleCloudToProdNameResolver.class);
  }

  @Test
  public void shouldProvideExperimentalNameResolverOfExpectedType() {
    assertThat(
            newNameResolver(
                new GoogleCloudToProdExperimentalNameResolverProvider(),
                "google-c2p-experimental:///foo.googleapis.com",
                args))
        .isInstanceOf(GoogleCloudToProdNameResolver.class);
  }

  private NameResolver newNameResolver(NameResolverProvider provider, String uri, Args args) {
    return enableRfc3986UrisParam
        ? provider.newNameResolver(Uri.create(uri), args)
        : provider.newNameResolver(URI.create(uri), args);
  }
}
