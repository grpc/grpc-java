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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.ChannelLogger;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.googleapis.GoogleCloudToProdNameResolver.HttpConnectionProvider;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class GoogleCloudToProdNameResolverTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private static final URI TARGET_URI = URI.create("google-c2p:///googleapis.com");
  private static final String ZONE = "us-central1-a";
  private static final int DEFAULT_PORT = 887;

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final NameResolver.Args args = NameResolver.Args.newBuilder()
      .setDefaultPort(DEFAULT_PORT)
      .setProxyDetector(GrpcUtil.DEFAULT_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(ServiceConfigParser.class))
      .setChannelLogger(mock(ChannelLogger.class))
      .build();
  private final FakeClock fakeExecutor = new FakeClock();
  private final FakeBootstrapSetter fakeBootstrapSetter = new FakeBootstrapSetter();
  private final Resource<Executor> fakeExecutorResource = new Resource<Executor>() {
    @Override
    public Executor create() {
      return fakeExecutor.getScheduledExecutorService();
    }

    @Override
    public void close(Executor instance) {}
  };

  private final NameResolverRegistry nsRegistry = new NameResolverRegistry();
  private final Map<String, NameResolver> delegatedResolver = new HashMap<>();

  @Mock
  private NameResolver.Listener2 mockListener;
  private Random random = new Random(1);
  @Captor
  private ArgumentCaptor<Status> errorCaptor;
  private boolean originalIsOnGcp;
  private boolean originalXdsBootstrapProvided;
  private GoogleCloudToProdNameResolver resolver;

  @Before
  public void setUp() {
    nsRegistry.register(new FakeNsProvider("dns"));
    nsRegistry.register(new FakeNsProvider("xds"));
    originalIsOnGcp = GoogleCloudToProdNameResolver.isOnGcp;
    originalXdsBootstrapProvided = GoogleCloudToProdNameResolver.xdsBootstrapProvided;
  }

  @After
  public void tearDown() {
    GoogleCloudToProdNameResolver.isOnGcp = originalIsOnGcp;
    GoogleCloudToProdNameResolver.xdsBootstrapProvided = originalXdsBootstrapProvided;
    resolver.shutdown();
    verify(Iterables.getOnlyElement(delegatedResolver.values())).shutdown();
  }

  private void createResolver() {
    HttpConnectionProvider httpConnections = new HttpConnectionProvider() {
      @Override
      public HttpURLConnection createConnection(String url) throws IOException {
        HttpURLConnection con = mock(HttpURLConnection.class);
        when(con.getResponseCode()).thenReturn(200);
        if (url.equals(GoogleCloudToProdNameResolver.METADATA_URL_ZONE)) {
          when(con.getInputStream()).thenReturn(
              new ByteArrayInputStream(("/" + ZONE).getBytes(StandardCharsets.UTF_8)));
          return con;
        } else if (url.equals(GoogleCloudToProdNameResolver.METADATA_URL_SUPPORT_IPV6)) {
          return con;
        }
        throw new AssertionError("Unknown http query");
      }
    };
    resolver = new GoogleCloudToProdNameResolver(
        TARGET_URI, args, fakeExecutorResource, random, fakeBootstrapSetter,
        nsRegistry.asFactory());
    resolver.setHttpConnectionProvider(httpConnections);
  }

  @Test
  public void notOnGcpDelegateToDns() {
    GoogleCloudToProdNameResolver.isOnGcp = false;
    createResolver();
    resolver.start(mockListener);
    assertThat(delegatedResolver.keySet()).containsExactly("dns");
    verify(Iterables.getOnlyElement(delegatedResolver.values())).start(mockListener);
  }

  @Test
  public void hasProvidedBootstrapDelegateToDns() {
    GoogleCloudToProdNameResolver.isOnGcp = true;
    GoogleCloudToProdNameResolver.xdsBootstrapProvided = true;
    GoogleCloudToProdNameResolver.enableFederation = false;
    createResolver();
    resolver.start(mockListener);
    assertThat(delegatedResolver.keySet()).containsExactly("dns");
    verify(Iterables.getOnlyElement(delegatedResolver.values())).start(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void onGcpAndNoProvidedBootstrapDelegateToXds() {
    GoogleCloudToProdNameResolver.isOnGcp = true;
    GoogleCloudToProdNameResolver.xdsBootstrapProvided = false;
    createResolver();
    resolver.start(mockListener);
    fakeExecutor.runDueTasks();
    assertThat(delegatedResolver.keySet()).containsExactly("xds");
    verify(Iterables.getOnlyElement(delegatedResolver.values())).start(mockListener);
    Map<String, ?> bootstrap = fakeBootstrapSetter.bootstrapRef.get();
    Map<String, ?> node = (Map<String, ?>) bootstrap.get("node");
    assertThat(node).containsExactly(
        "id", "C2P-991614323",
        "locality", ImmutableMap.of("zone", ZONE),
        "metadata", ImmutableMap.of("TRAFFICDIRECTOR_DIRECTPATH_C2P_IPV6_CAPABLE", true));
    Map<String, ?> server = Iterables.getOnlyElement(
        (List<Map<String, ?>>) bootstrap.get("xds_servers"));
    assertThat(server).containsExactly(
        "server_uri", "directpath-pa.googleapis.com",
        "channel_creds", ImmutableList.of(ImmutableMap.of("type", "google_default")),
        "server_features", ImmutableList.of("xds_v3", "ignore_resource_deletion"));
    Map<String, ?> authorities = (Map<String, ?>) bootstrap.get("authorities");
    assertThat(authorities).containsExactly(
        "traffic-director-c2p.xds.googleapis.com",
        ImmutableMap.of("xds_servers", ImmutableList.of(server)));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void onGcpAndNoProvidedBootstrapAndFederationEnabledDelegateToXds() {
    GoogleCloudToProdNameResolver.isOnGcp = true;
    GoogleCloudToProdNameResolver.xdsBootstrapProvided = false;
    GoogleCloudToProdNameResolver.enableFederation = true;
    createResolver();
    resolver.start(mockListener);
    fakeExecutor.runDueTasks();
    assertThat(delegatedResolver.keySet()).containsExactly("xds");
    verify(Iterables.getOnlyElement(delegatedResolver.values())).start(mockListener);
    // check bootstrap
    Map<String, ?> bootstrap = fakeBootstrapSetter.bootstrapRef.get();
    Map<String, ?> node = (Map<String, ?>) bootstrap.get("node");
    assertThat(node).containsExactly(
        "id", "C2P-991614323",
        "locality", ImmutableMap.of("zone", ZONE),
        "metadata", ImmutableMap.of("TRAFFICDIRECTOR_DIRECTPATH_C2P_IPV6_CAPABLE", true));
    Map<String, ?> server = Iterables.getOnlyElement(
        (List<Map<String, ?>>) bootstrap.get("xds_servers"));
    assertThat(server).containsExactly(
        "server_uri", "directpath-pa.googleapis.com",
        "channel_creds", ImmutableList.of(ImmutableMap.of("type", "google_default")),
        "server_features", ImmutableList.of("xds_v3", "ignore_resource_deletion"));
    Map<String, ?> authorities = (Map<String, ?>) bootstrap.get("authorities");
    assertThat(authorities).containsExactly(
        "traffic-director-c2p.xds.googleapis.com",
        ImmutableMap.of("xds_servers", ImmutableList.of(server)));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void onGcpAndProvidedBootstrapAndFederationEnabledDontDelegateToXds() {
    GoogleCloudToProdNameResolver.isOnGcp = true;
    GoogleCloudToProdNameResolver.xdsBootstrapProvided = true;
    GoogleCloudToProdNameResolver.enableFederation = true;
    createResolver();
    resolver.start(mockListener);
    fakeExecutor.runDueTasks();
    assertThat(delegatedResolver.keySet()).containsExactly("xds");
    verify(Iterables.getOnlyElement(delegatedResolver.values())).start(mockListener);
    // Bootstrapper should not have been set, since there was no user provided config.
    assertThat(fakeBootstrapSetter.bootstrapRef.get()).isNull();
  }

  @Test
  public void failToQueryMetadata() {
    GoogleCloudToProdNameResolver.isOnGcp = true;
    GoogleCloudToProdNameResolver.xdsBootstrapProvided = false;
    createResolver();
    HttpConnectionProvider httpConnections = new HttpConnectionProvider() {
      @Override
      public HttpURLConnection createConnection(String url) throws IOException {
        HttpURLConnection con = mock(HttpURLConnection.class);
        when(con.getResponseCode()).thenThrow(new IOException("unknown error"));
        return con;
      }
    };
    resolver.setHttpConnectionProvider(httpConnections);
    resolver.start(mockListener);
    fakeExecutor.runDueTasks();
    verify(mockListener).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.INTERNAL);
    assertThat(errorCaptor.getValue().getDescription()).isEqualTo("Unable to get metadata");
  }

  private final class FakeNsProvider extends NameResolverProvider {
    private final String scheme;

    private FakeNsProvider(String scheme) {
      this.scheme = scheme;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, Args args) {
      if (scheme.equals(targetUri.getScheme())) {
        NameResolver resolver = mock(NameResolver.class);
        delegatedResolver.put(scheme, resolver);
        return resolver;
      }
      return null;
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    @Override
    public String getDefaultScheme() {
      return scheme;
    }
  }

  private static final class FakeBootstrapSetter
      implements GoogleCloudToProdNameResolver.BootstrapSetter {
    private final AtomicReference<Map<String, ?>> bootstrapRef = new AtomicReference<>();

    @Override
    public void setBootstrap(Map<String, ?> bootstrap) {
      bootstrapRef.set(bootstrap);
    }
  }
}
