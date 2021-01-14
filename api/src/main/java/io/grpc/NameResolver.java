/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A pluggable component that resolves a target {@link URI} and return addresses to the caller.
 *
 * <p>A {@code NameResolver} uses the URI's scheme to determine whether it can resolve it, and uses
 * the components after the scheme for actual resolution.
 *
 * <p>The addresses and attributes of a target may be changed over time, thus the caller registers a
 * {@link Listener} to receive continuous updates.
 *
 * <p>A {@code NameResolver} does not need to automatically re-resolve on failure. Instead, the
 * {@link Listener} is responsible for eventually (after an appropriate backoff period) invoking
 * {@link #refresh()}.
 *
 * <p>Implementations <strong>don't need to be thread-safe</strong>.  All methods are guaranteed to
 * be called sequentially.  Additionally, all methods that have side-effects, i.e.,
 * {@link #start(Listener2)}, {@link #shutdown} and {@link #refresh} are called from the same
 * {@link SynchronizationContext} as returned by {@link Helper#getSynchronizationContext}.
 *
 * @since 1.0.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
public abstract class NameResolver {
  /**
   * Returns the authority used to authenticate connections to servers.  It <strong>must</strong> be
   * from a trusted source, because if the authority is tampered with, RPCs may be sent to the
   * attackers which may leak sensitive user data.
   *
   * <p>An implementation must generate it without blocking, typically in line, and
   * <strong>must</strong> keep it unchanged. {@code NameResolver}s created from the same factory
   * with the same argument must return the same authority.
   *
   * @since 1.0.0
   */
  public abstract String getServiceAuthority();

  /**
   * Starts the resolution.
   *
   * @param listener used to receive updates on the target
   * @since 1.0.0
   */
  public void start(final Listener listener) {
    if (listener instanceof Listener2) {
      start((Listener2) listener);
    } else {
      start(new Listener2() {
          @Override
          public void onError(Status error) {
            listener.onError(error);
          }

          @Override
          public void onResult(ResolutionResult resolutionResult) {
            listener.onAddresses(resolutionResult.getAddresses(), resolutionResult.getAttributes());
          }
      });
    }
  }

  /**
   * Starts the resolution.
   *
   * @param listener used to receive updates on the target
   * @since 1.21.0
   */
  public void start(Listener2 listener) {
    start((Listener) listener);
  }

  /**
   * Stops the resolution. Updates to the Listener will stop.
   *
   * @since 1.0.0
   */
  public abstract void shutdown();

  /**
   * Re-resolve the name.
   *
   * <p>Can only be called after {@link #start} has been called.
   *
   * <p>This is only a hint. Implementation takes it as a signal but may not start resolution
   * immediately. It should never throw.
   *
   * <p>The default implementation is no-op.
   *
   * @since 1.0.0
   */
  public void refresh() {}

  /**
   * Factory that creates {@link NameResolver} instances.
   *
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public abstract static class Factory {
    /**
     * The port number used in case the target or the underlying naming system doesn't provide a
     * port number.
     *
     * @deprecated this will be deleted along with {@link #newNameResolver(URI, Attributes)} in
     *             a future release.
     *
     * @since 1.0.0
     */
    @Deprecated
    public static final Attributes.Key<Integer> PARAMS_DEFAULT_PORT =
        Attributes.Key.create("params-default-port");

    /**
     * If the NameResolver wants to support proxy, it should inquire this {@link ProxyDetector}.
     * See documentation on {@link ProxyDetector} about how proxies work in gRPC.
     *
     * @deprecated this will be deleted along with {@link #newNameResolver(URI, Attributes)} in
     *             a future release
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5113")
    @Deprecated
    public static final Attributes.Key<ProxyDetector> PARAMS_PROXY_DETECTOR =
        Attributes.Key.create("params-proxy-detector");

    @Deprecated
    private static final Attributes.Key<SynchronizationContext> PARAMS_SYNC_CONTEXT =
        Attributes.Key.create("params-sync-context");

    @Deprecated
    private static final Attributes.Key<ServiceConfigParser> PARAMS_PARSER =
        Attributes.Key.create("params-parser");

    /**
     * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
     * cannot be resolved by this factory. The decision should be solely based on the scheme of the
     * URI.
     *
     * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
     * @param params optional parameters. Canonical keys are defined as {@code PARAMS_*} fields in
     *               {@link Factory}.
     *
     * @deprecated Implement {@link #newNameResolver(URI, NameResolver.Helper)} instead.  This is
     *             going to be deleted in a future release.
     *
     * @since 1.0.0
     */
    @Nullable
    @Deprecated
    public NameResolver newNameResolver(URI targetUri, final Attributes params) {
      Args args = Args.newBuilder()
          .setDefaultPort(params.get(PARAMS_DEFAULT_PORT))
          .setProxyDetector(params.get(PARAMS_PROXY_DETECTOR))
          .setSynchronizationContext(params.get(PARAMS_SYNC_CONTEXT))
          .setServiceConfigParser(params.get(PARAMS_PARSER))
          .build();
      return newNameResolver(targetUri, args);
    }

    /**
     * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
     * cannot be resolved by this factory. The decision should be solely based on the scheme of the
     * URI.
     *
     * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
     * @param helper utility that may be used by the NameResolver implementation
     *
     * @since 1.19.0
     * @deprecated implement {@link #newNameResolver(URI, NameResolver.Args)} instead
     */
    @Deprecated
    @Nullable
    public NameResolver newNameResolver(URI targetUri, final Helper helper) {
      return newNameResolver(
          targetUri,
          Attributes.newBuilder()
              .set(PARAMS_DEFAULT_PORT, helper.getDefaultPort())
              .set(PARAMS_PROXY_DETECTOR, helper.getProxyDetector())
              .set(PARAMS_SYNC_CONTEXT, helper.getSynchronizationContext())
              .set(PARAMS_PARSER, new ServiceConfigParser() {
                  @Override
                  public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
                    return helper.parseServiceConfig(rawServiceConfig);
                  }
                })
              .build());
    }

    /**
     * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
     * cannot be resolved by this factory. The decision should be solely based on the scheme of the
     * URI.
     *
     * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
     * @param args other information that may be useful
     *
     * @since 1.21.0
     */
    @SuppressWarnings("deprecation")
    // TODO(zhangkun83): make it abstract method after all other overrides have been deleted
    public NameResolver newNameResolver(URI targetUri, final Args args) {
      return newNameResolver(targetUri, new Helper() {
          @Override
          public int getDefaultPort() {
            return args.getDefaultPort();
          }

          @Override
          public ProxyDetector getProxyDetector() {
            return args.getProxyDetector();
          }

          @Override
          public SynchronizationContext getSynchronizationContext() {
            return args.getSynchronizationContext();
          }

          @Override
          public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
            return args.getServiceConfigParser().parseServiceConfig(rawServiceConfig);
          }
        });
    }

    /**
     * Returns the default scheme, which will be used to construct a URI when {@link
     * ManagedChannelBuilder#forTarget(String)} is given an authority string instead of a compliant
     * URI.
     *
     * @since 1.0.0
     */
    public abstract String getDefaultScheme();
  }

  /**
   * Receives address updates.
   *
   * <p>All methods are expected to return quickly.
   *
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  @ThreadSafe
  public interface Listener {
    /**
     * Handles updates on resolved addresses and attributes.
     *
     * <p>Implementations will not modify the given {@code servers}.
     *
     * @param servers the resolved server addresses. An empty list will trigger {@link #onError}
     * @param attributes extra information from naming system.
     * @since 1.3.0
     */
    void onAddresses(
        List<EquivalentAddressGroup> servers, @ResolutionResultAttr Attributes attributes);

    /**
     * Handles an error from the resolver. The listener is responsible for eventually invoking
     * {@link #refresh()} to re-attempt resolution.
     *
     * @param error a non-OK status
     * @since 1.0.0
     */
    void onError(Status error);
  }

  /**
   * Receives address updates.
   *
   * <p>All methods are expected to return quickly.
   *
   * <p>This is a replacement API of {@code Listener}. However, we think this new API may change
   * again, so we aren't yet encouraging mass-migration to it. It is fine to use and works.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public abstract static class Listener2 implements Listener {
    /**
     * @deprecated This will be removed in 1.22.0
     */
    @Override
    @Deprecated
    public final void onAddresses(
        List<EquivalentAddressGroup> servers, @ResolutionResultAttr Attributes attributes) {
      // TODO(jihuncho) need to promote Listener2 if we want to use ConfigOrError
      onResult(
          ResolutionResult.newBuilder().setAddresses(servers).setAttributes(attributes).build());
    }

    /**
     * Handles updates on resolved addresses and attributes.  If
     * {@link ResolutionResult#getAddresses()} is empty, {@link #onError(Status)} will be called.
     *
     * @param resolutionResult the resolved server addresses, attributes, and Service Config.
     * @since 1.21.0
     */
    public abstract void onResult(ResolutionResult resolutionResult);

    /**
     * Handles a name resolving error from the resolver. The listener is responsible for eventually
     * invoking {@link NameResolver#refresh()} to re-attempt resolution.
     *
     * @param error a non-OK status
     * @since 1.21.0
     */
    @Override
    public abstract void onError(Status error);
  }

  /**
   * Annotation for name resolution result attributes. It follows the annotation semantics defined
   * by {@link Attributes}.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4972")
  @Retention(RetentionPolicy.SOURCE)
  @Documented
  public @interface ResolutionResultAttr {}

  /**
   * A utility object passed to {@link Factory#newNameResolver(URI, NameResolver.Helper)}.
   *
   * @since 1.19.0
   * @deprecated use {@link Args} instead.
   */
  @Deprecated
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public abstract static class Helper {
    /**
     * The port number used in case the target or the underlying naming system doesn't provide a
     * port number.
     *
     * @since 1.19.0
     */
    public abstract int getDefaultPort();

    /**
     * If the NameResolver wants to support proxy, it should inquire this {@link ProxyDetector}.
     * See documentation on {@link ProxyDetector} about how proxies work in gRPC.
     *
     * @since 1.19.0
     */
    public abstract ProxyDetector getProxyDetector();

    /**
     * Returns the {@link SynchronizationContext} where {@link #start(Listener2)}, {@link #shutdown}
     * and {@link #refresh} are run from.
     *
     * @since 1.20.0
     */
    public SynchronizationContext getSynchronizationContext() {
      throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Parses and validates the service configuration chosen by the name resolver.  This will
     * return a {@link ConfigOrError} which contains either the successfully parsed config, or the
     * {@link Status} representing the failure to parse.  Implementations are expected to not throw
     * exceptions but return a Status representing the failure.  The value inside the
     * {@link ConfigOrError} should implement {@code equals()} and {@code hashCode()}.
     *
     * @param rawServiceConfig The {@link Map} representation of the service config
     * @return a tuple of the fully parsed and validated channel configuration, else the Status.
     * @since 1.20.0
     */
    public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
      throw new UnsupportedOperationException("should have been implemented");
    }
  }

  /**
   * Information that a {@link Factory} uses to create a {@link NameResolver}.
   *
   * <p>Note this class doesn't override neither {@code equals()} nor {@code hashCode()}.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public static final class Args {
    private final int defaultPort;
    private final ProxyDetector proxyDetector;
    private final SynchronizationContext syncContext;
    private final ServiceConfigParser serviceConfigParser;
    @Nullable private final ScheduledExecutorService scheduledExecutorService;
    @Nullable private final ChannelLogger channelLogger;
    @Nullable private final Executor executor;

    private Args(
        Integer defaultPort,
        ProxyDetector proxyDetector,
        SynchronizationContext syncContext,
        ServiceConfigParser serviceConfigParser,
        @Nullable ScheduledExecutorService scheduledExecutorService,
        @Nullable ChannelLogger channelLogger,
        @Nullable Executor executor) {
      this.defaultPort = checkNotNull(defaultPort, "defaultPort not set");
      this.proxyDetector = checkNotNull(proxyDetector, "proxyDetector not set");
      this.syncContext = checkNotNull(syncContext, "syncContext not set");
      this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser not set");
      this.scheduledExecutorService = scheduledExecutorService;
      this.channelLogger = channelLogger;
      this.executor = executor;
    }

    /**
     * The port number used in case the target or the underlying naming system doesn't provide a
     * port number.
     *
     * @since 1.21.0
     */
    public int getDefaultPort() {
      return defaultPort;
    }

    /**
     * If the NameResolver wants to support proxy, it should inquire this {@link ProxyDetector}.
     * See documentation on {@link ProxyDetector} about how proxies work in gRPC.
     *
     * @since 1.21.0
     */
    public ProxyDetector getProxyDetector() {
      return proxyDetector;
    }

    /**
     * Returns the {@link SynchronizationContext} where {@link #start(Listener2)}, {@link #shutdown}
     * and {@link #refresh} are run from.
     *
     * @since 1.21.0
     */
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    /**
     * Returns a {@link ScheduledExecutorService} for scheduling delayed tasks.
     *
     * <p>This service is a shared resource and is only meant for quick tasks. DO NOT block or run
     * time-consuming tasks.
     *
     * <p>The returned service doesn't support {@link ScheduledExecutorService#shutdown shutdown()}
     *  and {@link ScheduledExecutorService#shutdownNow shutdownNow()}. They will throw if called.
     *
     * @since 1.26.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6454")
    public ScheduledExecutorService getScheduledExecutorService() {
      if (scheduledExecutorService == null) {
        throw new IllegalStateException("ScheduledExecutorService not set in Builder");
      }
      return scheduledExecutorService;
    }

    /**
     * Returns the {@link ServiceConfigParser}.
     *
     * @since 1.21.0
     */
    public ServiceConfigParser getServiceConfigParser() {
      return serviceConfigParser;
    }

    /**
     * Returns the {@link ChannelLogger} for the Channel served by this NameResolver.
     *
     * @since 1.26.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6438")
    public ChannelLogger getChannelLogger() {
      if (channelLogger == null) {
        throw new IllegalStateException("ChannelLogger is not set in Builder");
      }
      return channelLogger;
    }

    /**
     * Returns the Executor on which this resolver should execute long-running or I/O bound work.
     * Null if no Executor was set.
     *
     * @since 1.25.0
     */
    @Nullable
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6279")
    public Executor getOffloadExecutor() {
      return executor;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("defaultPort", defaultPort)
          .add("proxyDetector", proxyDetector)
          .add("syncContext", syncContext)
          .add("serviceConfigParser", serviceConfigParser)
          .add("scheduledExecutorService", scheduledExecutorService)
          .add("channelLogger", channelLogger)
          .add("executor", executor)
          .toString();
    }

    /**
     * Returns a builder with the same initial values as this object.
     *
     * @since 1.21.0
     */
    public Builder toBuilder() {
      Builder builder = new Builder();
      builder.setDefaultPort(defaultPort);
      builder.setProxyDetector(proxyDetector);
      builder.setSynchronizationContext(syncContext);
      builder.setServiceConfigParser(serviceConfigParser);
      builder.setScheduledExecutorService(scheduledExecutorService);
      builder.setChannelLogger(channelLogger);
      builder.setOffloadExecutor(executor);
      return builder;
    }

    /**
     * Creates a new builder.
     *
     * @since 1.21.0
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * Builder for {@link Args}.
     *
     * @since 1.21.0
     */
    public static final class Builder {
      private Integer defaultPort;
      private ProxyDetector proxyDetector;
      private SynchronizationContext syncContext;
      private ServiceConfigParser serviceConfigParser;
      private ScheduledExecutorService scheduledExecutorService;
      private ChannelLogger channelLogger;
      private Executor executor;

      Builder() {
      }

      /**
       * See {@link Args#getDefaultPort}.  This is a required field.
       *
       * @since 1.21.0
       */
      public Builder setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
        return this;
      }

      /**
       * See {@link Args#getProxyDetector}.  This is required field.
       *
       * @since 1.21.0
       */
      public Builder setProxyDetector(ProxyDetector proxyDetector) {
        this.proxyDetector = checkNotNull(proxyDetector);
        return this;
      }

      /**
       * See {@link Args#getSynchronizationContext}.  This is a required field.
       *
       * @since 1.21.0
       */
      public Builder setSynchronizationContext(SynchronizationContext syncContext) {
        this.syncContext = checkNotNull(syncContext);
        return this;
      }

      /**
       * See {@link Args#getScheduledExecutorService}.
       */
      @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6454")
      public Builder setScheduledExecutorService(
          ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = checkNotNull(scheduledExecutorService);
        return this;
      }

      /**
       * See {@link Args#getServiceConfigParser}.  This is a required field.
       *
       * @since 1.21.0
       */
      public Builder setServiceConfigParser(ServiceConfigParser parser) {
        this.serviceConfigParser = checkNotNull(parser);
        return this;
      }

      /**
       * See {@link Args#getChannelLogger}.
       *
       * @since 1.26.0
       */
      @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6438")
      public Builder setChannelLogger(ChannelLogger channelLogger) {
        this.channelLogger = checkNotNull(channelLogger);
        return this;
      }

      /**
       * See {@link Args#getOffloadExecutor}. This is an optional field.
       *
       * @since 1.25.0
       */
      @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6279")
      public Builder setOffloadExecutor(Executor executor) {
        this.executor = executor;
        return this;
      }

      /**
       * Builds an {@link Args}.
       *
       * @since 1.21.0
       */
      public Args build() {
        return
            new Args(
                defaultPort, proxyDetector, syncContext, serviceConfigParser,
                scheduledExecutorService, channelLogger, executor);
      }
    }
  }

  /**
   * Parses and validates service configuration.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public abstract static class ServiceConfigParser {
    /**
     * Parses and validates the service configuration chosen by the name resolver.  This will
     * return a {@link ConfigOrError} which contains either the successfully parsed config, or the
     * {@link Status} representing the failure to parse.  Implementations are expected to not throw
     * exceptions but return a Status representing the failure.  The value inside the
     * {@link ConfigOrError} should implement {@code equals()} and {@code hashCode()}.
     *
     * @param rawServiceConfig The {@link Map} representation of the service config
     * @return a tuple of the fully parsed and validated channel configuration, else the Status.
     * @since 1.21.0
     */
    public abstract ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig);
  }

  /**
   * Represents the results from a Name Resolver.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public static final class ResolutionResult {
    private final List<EquivalentAddressGroup> addresses;
    @ResolutionResultAttr
    private final Attributes attributes;
    @Nullable
    private final ConfigOrError serviceConfig;

    ResolutionResult(
        List<EquivalentAddressGroup> addresses,
        @ResolutionResultAttr Attributes attributes,
        ConfigOrError serviceConfig) {
      this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
      this.attributes = checkNotNull(attributes, "attributes");
      this.serviceConfig = serviceConfig;
    }

    /**
     * Constructs a new builder of a name resolution result.
     *
     * @since 1.21.0
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * Converts these results back to a builder.
     *
     * @since 1.21.0
     */
    public Builder toBuilder() {
      return newBuilder()
          .setAddresses(addresses)
          .setAttributes(attributes)
          .setServiceConfig(serviceConfig);
    }

    /**
     * Gets the addresses resolved by name resolution.
     *
     * @since 1.21.0
     */
    public List<EquivalentAddressGroup> getAddresses() {
      return addresses;
    }

    /**
     * Gets the attributes associated with the addresses resolved by name resolution.  If there are
     * no attributes, {@link Attributes#EMPTY} will be returned.
     *
     * @since 1.21.0
     */
    @ResolutionResultAttr
    public Attributes getAttributes() {
      return attributes;
    }

    /**
     * Gets the Service Config parsed by {@link NameResolver.Helper#parseServiceConfig(Map)}.
     *
     * @since 1.21.0
     */
    @Nullable
    public ConfigOrError getServiceConfig() {
      return serviceConfig;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("addresses", addresses)
          .add("attributes", attributes)
          .add("serviceConfig", serviceConfig)
          .toString();
    }

    /**
     * Useful for testing.  May be slow to calculate.
     */
    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ResolutionResult)) {
        return false;
      }
      ResolutionResult that = (ResolutionResult) obj;
      return Objects.equal(this.addresses, that.addresses)
          && Objects.equal(this.attributes, that.attributes)
          && Objects.equal(this.serviceConfig, that.serviceConfig);
    }

    /**
     * Useful for testing.  May be slow to calculate.
     */
    @Override
    public int hashCode() {
      return Objects.hashCode(addresses, attributes, serviceConfig);
    }

    /**
     * A builder for {@link ResolutionResult}.
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public static final class Builder {
      private List<EquivalentAddressGroup> addresses = Collections.emptyList();
      private Attributes attributes = Attributes.EMPTY;
      @Nullable
      private ConfigOrError serviceConfig;
      //  Make sure to update #toBuilder above!

      Builder() {}

      /**
       * Sets the addresses resolved by name resolution.  This field is required.
       *
       * @since 1.21.0
       */
      public Builder setAddresses(List<EquivalentAddressGroup> addresses) {
        this.addresses = addresses;
        return this;
      }

      /**
       * Sets the attributes for the addresses resolved by name resolution.  If unset,
       * {@link Attributes#EMPTY} will be used as a default.
       *
       * @since 1.21.0
       */
      public Builder setAttributes(Attributes attributes) {
        this.attributes = attributes;
        return this;
      }

      /**
       * Sets the Service Config parsed by {@link NameResolver.Helper#parseServiceConfig(Map)}.
       * This field is optional.
       *
       * @since 1.21.0
       */
      public Builder setServiceConfig(@Nullable ConfigOrError serviceConfig) {
        this.serviceConfig = serviceConfig;
        return this;
      }

      /**
       * Constructs a new {@link ResolutionResult} from this builder.
       *
       * @since 1.21.0
       */
      public ResolutionResult build() {
        return new ResolutionResult(addresses, attributes, serviceConfig);
      }
    }
  }
  
  /**
   * Gets the attributes associated with the addresses resolved by name resolution.  If there are
   * no attributes, {@link Attributes#EMPTY} will be returned.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public static final class ConfigOrError {

    /**
     * Returns a {@link ConfigOrError} for the successfully parsed config.
     */
    public static ConfigOrError fromConfig(Object config) {
      return new ConfigOrError(config);
    }

    /**
     * Returns a {@link ConfigOrError} for the failure to parse the config.
     *
     * @param status a non-OK status
     */
    public static ConfigOrError fromError(Status status) {
      return new ConfigOrError(status);
    }

    private final Status status;
    private final Object config;

    private ConfigOrError(Object config) {
      this.config = checkNotNull(config, "config");
      this.status = null;
    }

    private ConfigOrError(Status status) {
      this.config = null;
      this.status = checkNotNull(status, "status");
      checkArgument(!status.isOk(), "cannot use OK status: %s", status);
    }

    /**
     * Returns config if exists, otherwise null.
     */
    @Nullable
    public Object getConfig() {
      return config;
    }

    /**
     * Returns error status if exists, otherwise null.
     */
    @Nullable
    public Status getError() {
      return status;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ConfigOrError that = (ConfigOrError) o;
      return Objects.equal(status, that.status) && Objects.equal(config, that.config);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(status, config);
    }

    @Override
    public String toString() {
      if (config != null) {
        return MoreObjects.toStringHelper(this)
            .add("config", config)
            .toString();
      } else {
        assert status != null;
        return MoreObjects.toStringHelper(this)
            .add("error", status)
            .toString();
      }
    }
  }
}
