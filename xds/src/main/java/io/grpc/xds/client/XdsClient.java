/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.client;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.client.Bootstrapper.XDSTP_SCHEME;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.net.UrlEscapers;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * An {@link XdsClient} instance encapsulates all of the logic for communicating with the xDS
 * server. It may create multiple RPC streams (or a single ADS stream) for a series of xDS
 * protocols (e.g., LDS, RDS, VHDS, CDS and EDS) over a single channel. Watch-based interfaces
 * are provided for each set of data needed by gRPC.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10862")
public abstract class XdsClient {

  public static boolean isResourceNameValid(String resourceName, String typeUrl) {
    checkNotNull(resourceName, "resourceName");
    if (!resourceName.startsWith(XDSTP_SCHEME)) {
      return true;
    }
    URI uri;
    try {
      uri = new URI(resourceName);
    } catch (URISyntaxException e) {
      return false;
    }
    String path = uri.getPath();
    // path must be in the form of /{resource type}/{id/*}
    Splitter slashSplitter = Splitter.on('/').omitEmptyStrings();
    if (path == null) {
      return false;
    }
    List<String> pathSegs = slashSplitter.splitToList(path);
    if (pathSegs.size() < 2) {
      return false;
    }
    String type = pathSegs.get(0);
    if (!type.equals(slashSplitter.splitToList(typeUrl).get(1))) {
      return false;
    }
    return true;
  }

  /*
   * Convert the XDSTP resource name to its canonical version.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10862")
  public static String canonifyResourceName(String resourceName) {
    checkNotNull(resourceName, "resourceName");
    if (!resourceName.startsWith(XDSTP_SCHEME)) {
      return resourceName;
    }
    URI uri = URI.create(resourceName);
    String rawQuery = uri.getRawQuery();
    Splitter ampSplitter = Splitter.on('&').omitEmptyStrings();
    if (rawQuery == null) {
      return resourceName;
    }
    List<String> queries = ampSplitter.splitToList(rawQuery);
    if (queries.size() < 2) {
      return resourceName;
    }
    List<String> canonicalContextParams = new ArrayList<>(queries.size());
    for (String query : queries) {
      canonicalContextParams.add(query);
    }
    Collections.sort(canonicalContextParams);
    String canonifiedQuery = Joiner.on('&').join(canonicalContextParams);
    return resourceName.replace(rawQuery, canonifiedQuery);
  }

  /*
   * Percent encode the input using the url path segment escaper.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10862")
  public static String percentEncodePath(String input) {
    Iterable<String> pathSegs = Splitter.on('/').split(input);
    List<String> encodedSegs = new ArrayList<>();
    for (String pathSeg : pathSegs) {
      encodedSegs.add(UrlEscapers.urlPathSegmentEscaper().escape(pathSeg));
    }
    return Joiner.on('/').join(encodedSegs);
  }

  /**
   * Returns the authority from the resource name.
   */
  public static String getAuthorityFromResourceName(String resourceNames) {
    String authority;
    if (resourceNames.startsWith(XDSTP_SCHEME)) {
      URI uri = URI.create(resourceNames);
      authority = uri.getAuthority();
      if (authority == null) {
        authority = "";
      }
    } else {
      authority = null;
    }
    return authority;
  }

  public interface ResourceUpdate {}

  /**
   * Watcher interface for a single requested xDS resource.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10862")
  public interface ResourceWatcher<T extends ResourceUpdate> {

    /**
     * Called when the resource discovery RPC encounters some transient error.
     *
     * <p>Note that we expect that the implementer to:
     * - Comply with the guarantee to not generate certain statuses by the library:
     *   https://grpc.github.io/grpc/core/md_doc_statuscodes.html. If the code needs to be
     *   propagated to the channel, override it with {@link io.grpc.Status.Code#UNAVAILABLE}.
     * - Keep {@link Status} description in one form or another, as it contains valuable debugging
     *   information.
     */
    void onError(Status error);

    /**
     * Called when the requested resource is not available.
     *
     * @param resourceName name of the resource requested in discovery request.
     */
    void onResourceDoesNotExist(String resourceName);

    void onChanged(T update);
  }

  /**
   * The metadata of the xDS resource; used by the xDS config dump.
   */
  public static final class ResourceMetadata {
    private final String version;
    private final ResourceMetadataStatus status;
    private final long updateTimeNanos;
    private final boolean cached;
    @Nullable private final Any rawResource;
    @Nullable private final UpdateFailureState errorState;

    private ResourceMetadata(
        ResourceMetadataStatus status, String version, long updateTimeNanos, boolean cached,
        @Nullable Any rawResource, @Nullable UpdateFailureState errorState) {
      this.status = checkNotNull(status, "status");
      this.version = checkNotNull(version, "version");
      this.updateTimeNanos = updateTimeNanos;
      this.cached = cached;
      this.rawResource = rawResource;
      this.errorState = errorState;
    }

    public static ResourceMetadata newResourceMetadataUnknown() {
      return new ResourceMetadata(ResourceMetadataStatus.UNKNOWN, "", 0, false,null, null);
    }

    public static ResourceMetadata newResourceMetadataRequested() {
      return new ResourceMetadata(ResourceMetadataStatus.REQUESTED, "", 0, false, null, null);
    }

    public static ResourceMetadata newResourceMetadataDoesNotExist() {
      return new ResourceMetadata(ResourceMetadataStatus.DOES_NOT_EXIST, "", 0, false, null, null);
    }

    public static ResourceMetadata newResourceMetadataAcked(
        Any rawResource, String version, long updateTimeNanos) {
      checkNotNull(rawResource, "rawResource");
      return new ResourceMetadata(
          ResourceMetadataStatus.ACKED, version, updateTimeNanos, true, rawResource, null);
    }

    public static ResourceMetadata newResourceMetadataNacked(
        ResourceMetadata metadata, String failedVersion, long failedUpdateTime,
        String failedDetails, boolean cached) {
      checkNotNull(metadata, "metadata");
      return new ResourceMetadata(ResourceMetadataStatus.NACKED,
          metadata.getVersion(), metadata.getUpdateTimeNanos(), cached, metadata.getRawResource(),
          new UpdateFailureState(failedVersion, failedUpdateTime, failedDetails));
    }

    /** The last successfully updated version of the resource. */
    public String getVersion() {
      return version;
    }

    /** The client status of this resource. */
    public ResourceMetadataStatus getStatus() {
      return status;
    }

    /** The timestamp when the resource was last successfully updated. */
    public long getUpdateTimeNanos() {
      return updateTimeNanos;
    }

    /** Returns whether the resource was cached. */
    public boolean isCached() {
      return cached;
    }

    /** The last successfully updated xDS resource as it was returned by the server. */
    @Nullable
    public Any getRawResource() {
      return rawResource;
    }

    /** The metadata capturing the error details of the last rejected update of the resource. */
    @Nullable
    public UpdateFailureState getErrorState() {
      return errorState;
    }

    /**
     * Resource status from the view of a xDS client, which tells the synchronization
     * status between the xDS client and the xDS server.
     *
     * <p>This is a native representation of xDS ConfigDump ClientResourceStatus, see
     * <a href="https://github.com/envoyproxy/envoy/blob/main/api/envoy/admin/v3/config_dump.proto">
     * config_dump.proto</a>
     */
    public enum ResourceMetadataStatus {
      UNKNOWN, REQUESTED, DOES_NOT_EXIST, ACKED, NACKED
    }

    /**
     * Captures error metadata of failed resource updates.
     *
     * <p>This is a native representation of xDS ConfigDump UpdateFailureState, see
     * <a href="https://github.com/envoyproxy/envoy/blob/main/api/envoy/admin/v3/config_dump.proto">
     * config_dump.proto</a>
     */
    public static final class UpdateFailureState {
      private final String failedVersion;
      private final long failedUpdateTimeNanos;
      private final String failedDetails;

      private UpdateFailureState(
          String failedVersion, long failedUpdateTimeNanos, String failedDetails) {
        this.failedVersion = checkNotNull(failedVersion, "failedVersion");
        this.failedUpdateTimeNanos = failedUpdateTimeNanos;
        this.failedDetails = checkNotNull(failedDetails, "failedDetails");
      }

      /** The rejected version string of the last failed update attempt. */
      public String getFailedVersion() {
        return failedVersion;
      }

      /** Details about the last failed update attempt. */
      public long getFailedUpdateTimeNanos() {
        return failedUpdateTimeNanos;
      }

      /** Timestamp of the last failed update attempt. */
      public String getFailedDetails() {
        return failedDetails;
      }
    }
  }

  /**
   * Shutdown this {@link XdsClient} and release resources.
   */
  public void shutdown() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns {@code true} if {@link #shutdown()} has been called.
   */
  public boolean isShutDown() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the config used to bootstrap this XdsClient {@link Bootstrapper.BootstrapInfo}.
   */
  public Bootstrapper.BootstrapInfo getBootstrapInfo() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the implementation specific security configuration used in this XdsClient.
   */
  public Object getSecurityConfig() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns a {@link ListenableFuture} to the snapshot of the subscribed resources as
   * they are at the moment of the call.
   *
   * <p>The snapshot is a map from the "resource type" to
   * a map ("resource name": "resource metadata").
   */
  // Must be synchronized.
  public ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
      getSubscribedResourcesMetadataSnapshot() {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a data watcher for the given Xds resource.
   */
  public <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type,
      String resourceName,
      ResourceWatcher<T> watcher,
      Executor executor) {
    throw new UnsupportedOperationException();
  }

  public <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type,
      String resourceName,
      ResourceWatcher<T> watcher) {
    watchXdsResource(type, resourceName, watcher, MoreExecutors.directExecutor());
  }

  /**
   * Unregisters the given resource watcher.
   */
  public <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
      String resourceName,
      ResourceWatcher<T> watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds drop stats for the specified cluster with edsServiceName by using the returned object
   * to record dropped requests. Drop stats recorded with the returned object will be reported
   * to the load reporting server. The returned object is reference counted and the caller should
   * use {@link LoadStatsManager2.ClusterDropStats#release} to release its <i>hard</i> reference
   * when it is safe to stop reporting dropped RPCs for the specified cluster in the future.
   */
  public LoadStatsManager2.ClusterDropStats addClusterDropStats(
      Bootstrapper.ServerInfo serverInfo, String clusterName, @Nullable String edsServiceName) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds load stats for the specified locality (in the specified cluster with edsServiceName) by
   * using the returned object to record RPCs. Load stats recorded with the returned object will
   * be reported to the load reporting server. The returned object is reference counted and the
   * caller should use {@link LoadStatsManager2.ClusterLocalityStats#release} to release its
   * <i>hard</i> reference when it is safe to stop reporting RPC loads for the specified locality
   * in the future.
   */
  public LoadStatsManager2.ClusterLocalityStats addClusterLocalityStats(
      Bootstrapper.ServerInfo serverInfo, String clusterName, @Nullable String edsServiceName,
      Locality locality) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns a map of control plane server info objects to the LoadReportClients that are
   * responsible for sending load reports to the control plane servers.
   */
  public Map<Bootstrapper.ServerInfo, LoadReportClient> getServerLrsClientMap() {
    throw new UnsupportedOperationException();
  }

  /** Callback used to report a gauge metric value for server connections. */
  public interface ServerConnectionCallback {
    void reportServerConnectionGauge(boolean isConnected, String xdsServer);
  }

  /**
   * Reports whether xDS client has a "working" ADS stream to xDS server. The definition of a
   * working stream is defined in gRFC A78.
   *
   * @see <a
   *     href="https://github.com/grpc/proposal/blob/master/A78-grpc-metrics-wrr-pf-xds.md#xdsclient">
   *     A78-grpc-metrics-wrr-pf-xds.md</a>
   */
  public Future<Void> reportServerConnections(ServerConnectionCallback callback) {
    throw new UnsupportedOperationException();
  }

  static final class ProcessingTracker {
    private final AtomicInteger pendingTask = new AtomicInteger(1);
    private final Executor executor;
    private final Runnable completionListener;

    ProcessingTracker(Runnable completionListener, Executor executor) {
      this.executor = executor;
      this.completionListener = completionListener;
    }

    void startTask() {
      pendingTask.incrementAndGet();
    }

    void onComplete() {
      if (pendingTask.decrementAndGet() == 0) {
        executor.execute(completionListener);
      }
    }
  }

  interface XdsResponseHandler {
    /** Called when a xds response is received. */
    void handleResourceResponse(
        XdsResourceType<?> resourceType, ServerInfo serverInfo, String versionInfo,
        List<Any> resources, String nonce, boolean isFirstResponse,
        ProcessingTracker processingTracker);

    /** Called when the ADS stream is closed passively. */
    // Must be synchronized.
    void handleStreamClosed(Status error, boolean shouldTryFallback);
  }

  interface ResourceStore {

    /**
     * Returns the collection of resources currently subscribed to which have an authority matching
     * one of those for which the ControlPlaneClient associated with the specified ServerInfo is
     * the active one, or {@code null} if no such resources are currently subscribed to.
     *
     * <p>Note an empty collection indicates subscribing to resources of the given type with
     * wildcard mode.
     *
     * @param serverInfo the xds server to get the resources from
     * @param type       the type of the resources that should be retrieved
     */
    // Must be synchronized.
    @Nullable
    Collection<String> getSubscribedResources(
        ServerInfo serverInfo, XdsResourceType<? extends ResourceUpdate> type);

    Map<String, XdsResourceType<?>> getSubscribedResourceTypesWithTypeUrl();

    /**
     * For any of the subscribers to one of the specified resources, if there isn't a result or
     * an existing timer for the resource, start a timer for the resource.
     */
    void startMissingResourceTimers(Collection<String> resourceNames,
                                    XdsResourceType<?> resourceType);
  }
}
