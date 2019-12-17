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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ServiceConfigParser;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
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
  private URI uri;
  private final NameResolver nameResolver = mock(NameResolver.class);

  @Before
  public void setUp() throws Exception {
    uri = new URI("fake://service");
  }

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

    NameResolver.Args args2 = args.toBuilder().build();
    assertThat(args2.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args2.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args2.getSynchronizationContext()).isSameInstanceAs(syncContext);
    assertThat(args2.getServiceConfigParser()).isSameInstanceAs(parser);
    assertThat(args2.getScheduledExecutorService()).isSameInstanceAs(scheduledExecutorService);
    assertThat(args2.getChannelLogger()).isSameInstanceAs(channelLogger);
    assertThat(args2.getOffloadExecutor()).isSameInstanceAs(executor);

    assertThat(args2).isNotSameInstanceAs(args);
    assertThat(args2).isNotEqualTo(args);
  }

  @Deprecated
  @Test
  public void newNameResolver_Api2DelegatesToApi1() {
    final AtomicReference<Attributes> paramsCapture = new AtomicReference<>();
    NameResolver.Factory factory = new NameResolver.Factory() {
        @Deprecated
        @Override
        public NameResolver newNameResolver(URI targetUri, Attributes params) {
          assertThat(targetUri).isSameInstanceAs(uri);
          paramsCapture.set(params);
          return nameResolver;
        }

        @Override
        public String getDefaultScheme() {
          throw new AssertionError();
        }
      };
    assertThat(factory.newNameResolver(uri, new NameResolver.Helper() {
        @Override
        public int getDefaultPort() {
          return defaultPort;
        }

        @Override
        public ProxyDetector getProxyDetector() {
          return proxyDetector;
        }

        @Override
        public SynchronizationContext getSynchronizationContext() {
          return syncContext;
        }
      })).isSameInstanceAs(nameResolver);
    Attributes params = paramsCapture.get();
    assertThat(params.get(NameResolver.Factory.PARAMS_DEFAULT_PORT)).isEqualTo(defaultPort);
    assertThat(params.get(NameResolver.Factory.PARAMS_PROXY_DETECTOR))
        .isSameInstanceAs(proxyDetector);
  }

  @Deprecated
  @SuppressWarnings("unchecked")
  @Test
  public void newNameResolver_Api3DelegatesToApi2() {
    final AtomicReference<NameResolver.Helper> helperCapture = new AtomicReference<>();
    NameResolver.Factory factory = new NameResolver.Factory() {
        @Deprecated
        @Override
        public NameResolver newNameResolver(URI targetUri, NameResolver.Helper helper) {
          assertThat(targetUri).isSameInstanceAs(uri);
          helperCapture.set(helper);
          return nameResolver;
        }

        @Override
        public String getDefaultScheme() {
          return "fake";
        }
      };
    assertThat(factory.newNameResolver(uri, createArgs())).isSameInstanceAs(nameResolver);
    NameResolver.Helper helper = helperCapture.get();
    assertThat(helper.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(helper.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(helper.getSynchronizationContext()).isSameInstanceAs(syncContext);

    // Test service config parsing
    ConfigOrError coe = ConfigOrError.fromConfig("A config");
    when(parser.parseServiceConfig(any(Map.class))).thenReturn(coe);
    Map<String, ?> rawConfig = Collections.singletonMap("Key", "value");
    assertThat(helper.parseServiceConfig(rawConfig)).isSameInstanceAs(coe);
    verify(parser).parseServiceConfig(same(rawConfig));
  }

  // Tests that a forwarding factory on API1 can correctly delegate to a factory already migrated
  // to API3 without losing information.
  @Deprecated
  @SuppressWarnings("unchecked")
  @Test
  public void newNameResolver_forwardingFactory1DelegatesToApi3() {
    final AtomicReference<NameResolver.Args> argsCapture = new AtomicReference<>();
    final NameResolver.Factory delegate = new NameResolver.Factory() {
        @Override
        public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
          assertThat(targetUri).isSameInstanceAs(uri);
          argsCapture.set(args);
          return nameResolver;
        }

        @Override
        public String getDefaultScheme() {
          throw new AssertionError();
        }
      };

    NameResolver.Factory forwarding = new NameResolver.Factory() {
        @Deprecated
        public NameResolver newNameResolver(URI targetUri, Attributes attrs) {
          return delegate.newNameResolver(targetUri, attrs);
        }

        @Override
        public String getDefaultScheme() {
          throw new AssertionError();
        }
      };

    // gRPC channel is calling forwarding with API3.
    assertThat(forwarding.newNameResolver(uri, createArgs())).isSameInstanceAs(nameResolver);

    NameResolver.Args args = argsCapture.get();
    assertThat(args.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args.getSynchronizationContext()).isSameInstanceAs(syncContext);

    ServiceConfigParser passedParser = args.getServiceConfigParser();
    ConfigOrError coe = ConfigOrError.fromConfig("A config");
    when(parser.parseServiceConfig(any(Map.class))).thenReturn(coe);
    Map<String, ?> rawConfig = Collections.singletonMap("Key", "value");
    assertThat(passedParser.parseServiceConfig(rawConfig)).isSameInstanceAs(coe);
    verify(parser).parseServiceConfig(same(rawConfig));
  }

  // Tests that a forwarding factory on API2 can correctly delegate to a factory already migrated
  // to API3 without losing information.
  @Deprecated
  @SuppressWarnings("unchecked")
  @Test
  public void newNameResolver_forwardingFactory2DelegatesToApi3() {
    final AtomicReference<NameResolver.Args> argsCapture = new AtomicReference<>();
    final NameResolver.Factory delegate = new NameResolver.Factory() {
        @Override
        public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
          assertThat(targetUri).isSameInstanceAs(uri);
          argsCapture.set(args);
          return nameResolver;
        }

        @Override
        public String getDefaultScheme() {
          throw new AssertionError();
        }
      };

    NameResolver.Factory forwarding = new NameResolver.Factory() {
        @Deprecated
        public NameResolver newNameResolver(URI targetUri, NameResolver.Helper helper) {
          return delegate.newNameResolver(targetUri, helper);
        }

        @Override
        public String getDefaultScheme() {
          throw new AssertionError();
        }
      };

    // gRPC channel is calling forwarding with API3.
    assertThat(forwarding.newNameResolver(uri, createArgs())).isSameInstanceAs(nameResolver);

    NameResolver.Args args = argsCapture.get();
    assertThat(args.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args.getSynchronizationContext()).isSameInstanceAs(syncContext);

    ServiceConfigParser passedParser = args.getServiceConfigParser();
    ConfigOrError coe = ConfigOrError.fromConfig("A config");
    when(parser.parseServiceConfig(any(Map.class))).thenReturn(coe);
    Map<String, ?> rawConfig = Collections.singletonMap("Key", "value");
    assertThat(passedParser.parseServiceConfig(rawConfig)).isSameInstanceAs(coe);
    verify(parser).parseServiceConfig(same(rawConfig));
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
        .build();
  }
}
