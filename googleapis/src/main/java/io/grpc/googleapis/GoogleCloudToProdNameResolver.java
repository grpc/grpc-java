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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.alts.InternalCheckGcpEnvironment;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;

/**
 * CloudToProd version of {@link NameResolver}.
 */
final class GoogleCloudToProdNameResolver extends NameResolver {

  @VisibleForTesting
  static final String METADATA_URL_ZONE =
      "http://metadata.google.internal/computeMetadata/v1/instance/zone";
  @VisibleForTesting
  static final String METADATA_URL_SUPPORT_IPV6 =
      "http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/ipv6s";
  static final String C2P_AUTHORITY = "traffic-director-c2p.xds.googleapis.com";
  @VisibleForTesting
  static boolean isOnGcp = InternalCheckGcpEnvironment.isOnGcp();
  @VisibleForTesting
  static boolean xdsBootstrapProvided =
      System.getenv("GRPC_XDS_BOOTSTRAP") != null
          || System.getProperty("io.grpc.xds.bootstrap") != null
          || System.getenv("GRPC_XDS_BOOTSTRAP_CONFIG") != null
          || System.getProperty("io.grpc.xds.bootstrapConfig") != null;
  @VisibleForTesting
  static boolean enableFederation =
      Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_XDS_FEDERATION"))
          || Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_XDS_FEDERATION"));

  private static final String serverUriOverride =
      System.getenv("GRPC_TEST_ONLY_GOOGLE_C2P_RESOLVER_TRAFFIC_DIRECTOR_URI");

  private HttpConnectionProvider httpConnectionProvider = HttpConnectionFactory.INSTANCE;
  private final String authority;
  private final SynchronizationContext syncContext;
  private final Resource<Executor> executorResource;
  private final BootstrapSetter bootstrapSetter;
  private final NameResolver delegate;
  private final Random rand;
  private final boolean usingExecutorResource;
  // It's not possible to use both PSM and DirectPath C2P in the same application.
  // Delegate to DNS if user-provided bootstrap is found.
  private final String schemeOverride =
      !isOnGcp
      || (xdsBootstrapProvided && !enableFederation)
      ? "dns" : "xds";
  private Executor executor;
  private Listener2 listener;
  private boolean succeeded;
  private boolean resolving;
  private boolean shutdown;

  GoogleCloudToProdNameResolver(URI targetUri, Args args, Resource<Executor> executorResource,
      BootstrapSetter bootstrapSetter) {
    this(targetUri, args, executorResource, new Random(), bootstrapSetter,
        NameResolverRegistry.getDefaultRegistry().asFactory());
  }

  @VisibleForTesting
  GoogleCloudToProdNameResolver(URI targetUri, Args args, Resource<Executor> executorResource,
      Random rand, BootstrapSetter bootstrapSetter, NameResolver.Factory nameResolverFactory) {
    this.executorResource = checkNotNull(executorResource, "executorResource");
    this.bootstrapSetter = checkNotNull(bootstrapSetter, "bootstrapSetter");
    this.rand = checkNotNull(rand, "rand");
    String targetPath = checkNotNull(checkNotNull(targetUri, "targetUri").getPath(), "targetPath");
    Preconditions.checkArgument(
        targetPath.startsWith("/"),
        "the path component (%s) of the target (%s) must start with '/'",
        targetPath,
        targetUri);
    authority = GrpcUtil.checkAuthority(targetPath.substring(1));
    syncContext = checkNotNull(args, "args").getSynchronizationContext();
    targetUri = overrideUriScheme(targetUri, schemeOverride);
    if (schemeOverride.equals("xds") && enableFederation) {
      targetUri = overrideUriAuthority(targetUri, C2P_AUTHORITY);
    }
    delegate = checkNotNull(nameResolverFactory, "nameResolverFactory").newNameResolver(
        targetUri, args);
    executor = args.getOffloadExecutor();
    usingExecutorResource = executor == null;
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @Override
  public void start(final Listener2 listener) {
    if (delegate == null) {
      listener.onError(Status.INTERNAL.withDescription(
          "Delegate resolver not found, scheme: " + schemeOverride));
      return;
    }
    this.listener = checkNotNull(listener, "listener");
    resolve();
  }

  private void resolve() {
    if (resolving || shutdown || delegate == null) {
      return;
    }
    resolving = true;
    if (schemeOverride.equals("dns")) {
      delegate.start(listener);
      succeeded = true;
      resolving = false;
      return;
    }
    if (executor == null) {
      executor = SharedResourceHolder.get(executorResource);
    }

    class Resolve implements Runnable {
      @Override
      public void run() {
        ImmutableMap<String, ?> rawBootstrap = null;
        try {
          // User provided bootstrap configs are only supported with federation. If federation is
          // not enabled or there is no user provided config, we set a custom bootstrap override.
          // Otherwise, we don't set the override, which will allow a user provided bootstrap config
          // to take effect.
          if (!enableFederation || !xdsBootstrapProvided) {
            rawBootstrap = generateBootstrap(queryZoneMetadata(METADATA_URL_ZONE),
                queryIpv6SupportMetadata(METADATA_URL_SUPPORT_IPV6));
          }
        } catch (IOException e) {
          listener.onError(
              Status.INTERNAL.withDescription("Unable to get metadata").withCause(e));
        } finally {
          final ImmutableMap<String, ?> finalRawBootstrap = rawBootstrap;
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (!shutdown) {
                if (finalRawBootstrap != null) {
                  bootstrapSetter.setBootstrap(finalRawBootstrap);
                }
                delegate.start(listener);
                succeeded = true;
              }
              resolving = false;
            }
          });
        }
      }
    }

    executor.execute(new Resolve());
  }

  private ImmutableMap<String, ?> generateBootstrap(String zone, boolean supportIpv6) {
    ImmutableMap.Builder<String, Object> nodeBuilder = ImmutableMap.builder();
    nodeBuilder.put("id", "C2P-" + (rand.nextInt() & Integer.MAX_VALUE));
    if (!zone.isEmpty()) {
      nodeBuilder.put("locality", ImmutableMap.of("zone", zone));
    }
    if (supportIpv6) {
      nodeBuilder.put("metadata",
          ImmutableMap.of("TRAFFICDIRECTOR_DIRECTPATH_C2P_IPV6_CAPABLE", true));
    }
    ImmutableMap.Builder<String, Object> serverBuilder = ImmutableMap.builder();
    String serverUri = "directpath-pa.googleapis.com";
    if (serverUriOverride != null && serverUriOverride.length() > 0) {
      serverUri = serverUriOverride;
    }
    serverBuilder.put("server_uri", serverUri);
    serverBuilder.put("channel_creds",
        ImmutableList.of(ImmutableMap.of("type", "google_default")));
    serverBuilder.put("server_features", ImmutableList.of("xds_v3", "ignore_resource_deletion"));
    ImmutableMap.Builder<String, Object> authoritiesBuilder = ImmutableMap.builder();
    authoritiesBuilder.put(
        C2P_AUTHORITY,
        ImmutableMap.of("xds_servers", ImmutableList.of(serverBuilder.buildOrThrow())));
    return ImmutableMap.of(
        "node", nodeBuilder.buildOrThrow(),
        "xds_servers", ImmutableList.of(serverBuilder.buildOrThrow()),
        "authorities", authoritiesBuilder.buildOrThrow());
  }

  @Override
  public void refresh() {
    if (succeeded) {
      delegate.refresh();
    } else if (!resolving) {
      resolve();
    }
  }

  @Override
  public void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    if (delegate != null) {
      delegate.shutdown();
    }
    if (executor != null && usingExecutorResource) {
      executor = SharedResourceHolder.release(executorResource, executor);
    }
  }

  private String queryZoneMetadata(String url) throws IOException {
    HttpURLConnection con = null;
    String respBody;
    try {
      con = httpConnectionProvider.createConnection(url);
      if (con.getResponseCode() != 200) {
        return "";
      }
      try (Reader reader = new InputStreamReader(con.getInputStream(), Charsets.UTF_8)) {
        respBody = CharStreams.toString(reader);
      }
    } finally {
      if (con != null) {
        con.disconnect();
      }
    }
    int index = respBody.lastIndexOf('/');
    return index == -1 ? "" : respBody.substring(index + 1);
  }

  private boolean queryIpv6SupportMetadata(String url) throws IOException {
    HttpURLConnection con = null;
    try {
      con = httpConnectionProvider.createConnection(url);
      return con.getResponseCode() == 200;
    } finally {
      if (con != null) {
        con.disconnect();
      }
    }
  }

  @VisibleForTesting
  void setHttpConnectionProvider(HttpConnectionProvider httpConnectionProvider) {
    this.httpConnectionProvider = httpConnectionProvider;
  }

  private static URI overrideUriScheme(URI uri, String scheme) {
    URI res;
    try {
      res = new URI(scheme, uri.getAuthority(), uri.getPath(), uri.getQuery(), uri.getFragment());
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid scheme: " + scheme, ex);
    }
    return res;
  }

  private static URI overrideUriAuthority(URI uri, String authority) {
    URI res;
    try {
      res = new URI(uri.getScheme(), authority, uri.getPath(), uri.getQuery(), uri.getFragment());
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid authority: " + authority, ex);
    }
    return res;
  }

  private enum HttpConnectionFactory implements HttpConnectionProvider {
    INSTANCE;

    @Override
    public HttpURLConnection createConnection(String url) throws IOException {
      HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
      con.setRequestMethod("GET");
      con.setReadTimeout(10000);
      con.setRequestProperty("Metadata-Flavor", "Google");
      return con;
    }
  }

  @VisibleForTesting
  interface HttpConnectionProvider {
    HttpURLConnection createConnection(String url) throws IOException;
  }

  public interface BootstrapSetter {
    void setBootstrap(Map<String, ?> bootstrap);
  }
}
