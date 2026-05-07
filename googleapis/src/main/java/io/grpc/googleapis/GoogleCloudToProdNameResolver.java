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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.MetricRecorder;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.QueryParams;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.Uri;
import io.grpc.alts.InternalCheckGcpEnvironment;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.xds.InternalGrpcBootstrapperImpl;
import io.grpc.xds.InternalSharedXdsClientPoolProvider;
import io.grpc.xds.InternalSharedXdsClientPoolProvider.XdsClientResult;
import io.grpc.xds.XdsNameResolverProvider;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsInitializationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CloudToProd version of {@link NameResolver}.
 */
final class GoogleCloudToProdNameResolver extends NameResolver {
  private static final Logger logger =
      Logger.getLogger(GoogleCloudToProdNameResolver.class.getName());

  @VisibleForTesting
  static final String METADATA_URL_ZONE =
      "http://metadata.google.internal/computeMetadata/v1/instance/zone";
  @VisibleForTesting
  static final String METADATA_URL_SUPPORT_IPV6 =
      "http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/ipv6s";
  static final String C2P_AUTHORITY = "traffic-director-c2p.xds.googleapis.com";
  @VisibleForTesting
  static boolean isOnGcp = InternalCheckGcpEnvironment.isOnGcp();

  private static final String serverUriOverride =
      System.getenv("GRPC_TEST_ONLY_GOOGLE_C2P_RESOLVER_TRAFFIC_DIRECTOR_URI");

  private static final Object BOOTSTRAP_LOCK = new Object();

  @GuardedBy("BOOTSTRAP_LOCK")
  static BootstrapInfo bootstrapInfo;
  private static HttpConnectionProvider httpConnectionProvider = HttpConnectionFactory.INSTANCE;
  private static int c2pId = new Random().nextInt();

  private static BootstrapInfo getBootstrapInfo(boolean isForcedXds)
      throws XdsInitializationException, IOException {
    synchronized (BOOTSTRAP_LOCK) {
      if (bootstrapInfo != null) {
        return bootstrapInfo;
      }
      BootstrapInfo newInfo;
      if (isForcedXds) {
        newInfo = InternalGrpcBootstrapperImpl.parseBootstrap(
            generateBootstrap("", true, true));
      } else {
        newInfo = InternalGrpcBootstrapperImpl.parseBootstrap(
            generateBootstrap());
      }
      // Avoid setting global when testing
      if (httpConnectionProvider == HttpConnectionFactory.INSTANCE) {
        bootstrapInfo = newInfo;
      }
      return newInfo;
    }
  }

  private final String authority;
  private final SynchronizationContext syncContext;
  private final Resource<Executor> executorResource;
  private final String target;
  private final MetricRecorder metricRecorder;
  private final NameResolver delegate;
  private final boolean usingExecutorResource;
  private final boolean forceXds;
  private final String schemeOverride;
  private XdsClientResult xdsClientPool;
  private XdsClient xdsClient;
  private Executor executor;
  private Listener2 listener;
  private boolean succeeded;
  private boolean resolving;
  private boolean shutdown;

  GoogleCloudToProdNameResolver(URI targetUri, Args args, Resource<Executor> executorResource) {
    this(targetUri, args, executorResource,
        NameResolverRegistry.getDefaultRegistry().asFactory());
  }

  // TODO(jdcormie): Remove after io.grpc.Uri migration.
  @VisibleForTesting
  GoogleCloudToProdNameResolver(URI targetUri, Args args, Resource<Executor> executorResource,
      NameResolver.Factory nameResolverFactory) {
    this.executorResource = checkNotNull(executorResource, "executorResource");
    String targetPath = checkNotNull(checkNotNull(targetUri, "targetUri").getPath(), "targetPath");
    Uri grpcUri = Uri.create(targetUri.toString());
    String query = grpcUri.getRawQuery();
    this.forceXds = checkForceXds(query);
    this.schemeOverride = (forceXds || isOnGcp) ? "xds" : "dns";
    String newQuery = stripForceXds(query);

    Preconditions.checkArgument(
        targetPath.startsWith("/"),
        "the path component (%s) of the target (%s) must start with '/'",
        targetPath,
        targetUri);
    authority = GrpcUtil.checkAuthority(targetPath.substring(1));
    syncContext = checkNotNull(args, "args").getSynchronizationContext();

    Uri.Builder modifiedTargetBuilder = grpcUri.toBuilder().setScheme(schemeOverride);
    if (newQuery != null) {
      modifiedTargetBuilder.setRawQuery(newQuery);
    } else {
      modifiedTargetBuilder.setRawQuery(null);
    }
    if (schemeOverride.equals("xds")) {
      modifiedTargetBuilder.setRawAuthority(C2P_AUTHORITY);
    }
    targetUri = URI.create(modifiedTargetBuilder.build().toString());

    if (schemeOverride.equals("xds")) {
      args = args.toBuilder()
          .setArg(XdsNameResolverProvider.XDS_CLIENT_SUPPLIER, () -> xdsClient)
          .build();
    }
    target = targetUri.toString();
    metricRecorder = args.getMetricRecorder();
    delegate = checkNotNull(nameResolverFactory, "nameResolverFactory").newNameResolver(
        targetUri, args);
    executor = args.getOffloadExecutor();
    usingExecutorResource = executor == null;
  }

  GoogleCloudToProdNameResolver(Uri targetUri, Args args, Resource<Executor> executorResource) {
    this(targetUri, args, executorResource, NameResolverRegistry.getDefaultRegistry().asFactory());
  }

  @VisibleForTesting
  GoogleCloudToProdNameResolver(
      Uri targetUri,
      Args args,
      Resource<Executor> executorResource,
      NameResolver.Factory nameResolverFactory) {
    this.executorResource = checkNotNull(executorResource, "executorResource");
    String query = targetUri.getRawQuery();
    this.forceXds = checkForceXds(query);
    this.schemeOverride = (forceXds || isOnGcp) ? "xds" : "dns";
    String newQuery = stripForceXds(query);

    Preconditions.checkArgument(
        targetUri.isPathAbsolute(),
        "the path component of the target (%s) must start with '/'",
        targetUri);
    List<String> pathSegments = targetUri.getPathSegments();
    Preconditions.checkArgument(
        pathSegments.size() == 1,
        "the path component of the target (%s) must have exactly one segment",
        targetUri);
    authority = GrpcUtil.checkAuthority(pathSegments.get(0));
    syncContext = checkNotNull(args, "args").getSynchronizationContext();
    Uri.Builder modifiedTargetBuilder = targetUri.toBuilder().setScheme(schemeOverride);
    if (newQuery != null) {
      modifiedTargetBuilder.setRawQuery(newQuery);
    } else {
      modifiedTargetBuilder.setRawQuery(null);
    }

    if (schemeOverride.equals("xds")) {
      modifiedTargetBuilder.setRawAuthority(C2P_AUTHORITY);
      args =
          args.toBuilder()
              .setArg(XdsNameResolverProvider.XDS_CLIENT_SUPPLIER, () -> xdsClient)
              .build();
    }
    targetUri = modifiedTargetBuilder.build();
    target = targetUri.toString();
    metricRecorder = args.getMetricRecorder();
    delegate =
        checkNotNull(nameResolverFactory, "nameResolverFactory").newNameResolver(targetUri, args);
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
    if (logger.isLoggable(Level.FINE)) {
      logger.log(Level.FINE, "start with schemaOverride = {0}", schemeOverride);
    }

    if (schemeOverride.equals("dns")) {
      delegate.start(listener);
      succeeded = true;
      resolving = false;
      return;
    }

    // Since not dns, we must be using xds
    if (executor == null) {
      executor = SharedResourceHolder.get(executorResource);
    }

    class Resolve implements Runnable {
      @Override
      public void run() {
        BootstrapInfo bootstrapInfo = null;
        try {
          bootstrapInfo = getBootstrapInfo(forceXds);
        } catch (IOException e) {
          listener.onError(
              Status.INTERNAL.withDescription("Unable to get metadata").withCause(e));
        } catch (XdsInitializationException e) {
          listener.onError(
              Status.INTERNAL.withDescription("Unable to create c2p bootstrap").withCause(e));
        } catch (Throwable t) {
          listener.onError(
              Status.INTERNAL.withDescription("Unexpected error creating c2p bootstrap")
              .withCause(t));
        } finally {
          final BootstrapInfo finalBootstrapInfo = bootstrapInfo;
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (!shutdown && finalBootstrapInfo != null) {
                xdsClientPool = InternalSharedXdsClientPoolProvider.getOrCreate(
                    target, finalBootstrapInfo, metricRecorder, null);
                xdsClient = xdsClientPool.getObject();
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

  @VisibleForTesting
  static ImmutableMap<String, ?> generateBootstrap() throws IOException {
    return generateBootstrap(
        queryZoneMetadata(METADATA_URL_ZONE),
        queryIpv6SupportMetadata(METADATA_URL_SUPPORT_IPV6), false);
  }

  static ImmutableMap<String, ?> generateBootstrap(
      String zone, boolean supportIpv6, boolean isForcedXds) {
    ImmutableMap.Builder<String, Object> nodeBuilder = ImmutableMap.builder();
    String nodeIdPrefix = isOnGcp ? "C2P-" : "C2P-non-gcp-";
    nodeBuilder.put("id", nodeIdPrefix + (c2pId & Integer.MAX_VALUE));
    if (!isForcedXds && !zone.isEmpty()) {
      nodeBuilder.put("locality", ImmutableMap.of("zone", zone));
    }
    if (isForcedXds || supportIpv6) {
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
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
    if (executor != null && usingExecutorResource) {
      executor = SharedResourceHolder.release(executorResource, executor);
    }
  }

  private static String queryZoneMetadata(String url) throws IOException {
    HttpURLConnection con = null;
    String respBody;
    try {
      con = httpConnectionProvider.createConnection(url);
      if (con.getResponseCode() != 200) {
        return "";
      }
      try (Reader reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
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

  private static boolean queryIpv6SupportMetadata(String url) throws IOException {
    HttpURLConnection con = null;
    try {
      con = httpConnectionProvider.createConnection(url);
      if (con.getResponseCode() != 200 ) {
        return false;
      }
      InputStream inputStream = con.getInputStream();
      int c;
      return (inputStream != null
          && (c = inputStream.read()) != -1 && !Character.isWhitespace(c));
    } finally {
      if (con != null) {
        con.disconnect();
      }
    }
  }

  @VisibleForTesting
  static void setHttpConnectionProvider(HttpConnectionProvider httpConnectionProvider) {
    if (httpConnectionProvider == null) {
      GoogleCloudToProdNameResolver.httpConnectionProvider = HttpConnectionFactory.INSTANCE;
    } else {
      GoogleCloudToProdNameResolver.httpConnectionProvider = httpConnectionProvider;
    }
  }

  @VisibleForTesting
  static void setC2pId(int c2pId) {
    GoogleCloudToProdNameResolver.c2pId = c2pId;
  }

  @VisibleForTesting
  static void resetBootstrapInfo() {
    synchronized (BOOTSTRAP_LOCK) {
      bootstrapInfo = null;
    }
  }

  private static boolean checkForceXds(String query) {
    if (query == null) {
      return false;
    }
    QueryParams params = QueryParams.fromRawQuery(query);
    for (QueryParams.Entry entry : params.asList()) {
      if ("force-xds".equals(entry.getKey())) {
        return true;
      }
    }
    return false;
  }

  private static String stripForceXds(String query) {
    if (query == null) {
      return null;
    }
    QueryParams params = QueryParams.fromRawQuery(query);
    params.asList().removeIf(entry -> "force-xds".equals(entry.getKey()));
    params.asList().removeIf(entry -> entry.getKey().isEmpty() && !entry.hasValue());
    return params.toRawQuery();
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
}
