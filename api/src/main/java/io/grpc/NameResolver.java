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
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;
import com.google.errorprone.annotations.InlineMe;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URI;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
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
 * {@link SynchronizationContext} as returned by {@link Args#getSynchronizationContext}. <strong>Do
 * not block</strong> within the synchronization context; blocking I/O and time-consuming tasks
 * should be offloaded to a separate thread, generally {@link Args#getOffloadExecutor}.
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
   * Starts the resolution. The method is not supposed to throw any exceptions. That might cause the
   * Channel that the name resolver is serving to crash. Errors should be propagated
   * through {@link Listener#onError}.
   * 
   * <p>An instance may not be started more than once, by any overload of this method, even after
   * an intervening call to {@link #shutdown}.
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
            listener.onAddresses(resolutionResult.getAddressesOrError().getValue(),
                resolutionResult.getAttributes());
          }
      });
    }
  }

  /**
   * Starts the resolution. The method is not supposed to throw any exceptions. That might cause the
   * Channel that the name resolver is serving to crash. Errors should be propagated
   * through {@link Listener2#onError}.
   * 
   * <p>An instance may not be started more than once, by any overload of this method, even after
   * an intervening call to {@link #shutdown}.
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
     * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
     * cannot be resolved by this factory. The decision should be solely based on the scheme of the
     * URI.
     *
     * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
     * @param args other information that may be useful
     *
     * @since 1.21.0
     */
    public abstract NameResolver newNameResolver(URI targetUri, final Args args);

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
     * Handles updates on resolved addresses and attributes.
     *
     * @deprecated This will be removed in 1.22.0
     */
    @Override
    @Deprecated
    @InlineMe(
        replacement = "this.onResult(ResolutionResult.newBuilder().setAddressesOrError("
            + "StatusOr.fromValue(servers)).setAttributes(attributes).build())",
        imports = {"io.grpc.NameResolver.ResolutionResult", "io.grpc.StatusOr"})
    public final void onAddresses(
        List<EquivalentAddressGroup> servers, @ResolutionResultAttr Attributes attributes) {
      // TODO(jihuncho) need to promote Listener2 if we want to use ConfigOrError
      // Calling onResult and not onResult2 because onResult2 can only be called from a
      // synchronization context.
      onResult(
          ResolutionResult.newBuilder().setAddressesOrError(
              StatusOr.fromValue(servers)).setAttributes(attributes).build());
    }

    /**
     * Handles updates on resolved addresses and attributes.  If
     * {@link ResolutionResult#getAddressesOrError()} is empty, {@link #onError(Status)} will be
     * called.
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

    /**
     * Handles updates on resolved addresses and attributes.
     *
     * @param resolutionResult the resolved server addresses, attributes, and Service Config.
     * @since 1.66
     */
    public Status onResult2(ResolutionResult resolutionResult) {
      onResult(resolutionResult);
      return Status.OK;
    }
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
   * Information that a {@link Factory} uses to create a {@link NameResolver}.
   *
   * <p>Args applicable to all {@link NameResolver}s are defined here using ordinary setters and
   * getters. This container can also hold externally-defined "extended" args that aren't so widely
   * useful or that would be inappropriate dependencies for this low level API. See {@link
   * Args#getExtension} for more.
   *
   * <p>Note this class overrides neither {@code equals()} nor {@code hashCode()}.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public static final class Args {
    private final int defaultPort;
    private final ProxyDetector proxyDetector;
    private final SynchronizationContext syncContext;
    private final ServiceConfigParser serviceConfigParser;
    private final Extensions extensions;
    @Nullable private final ScheduledExecutorService scheduledExecutorService;
    @Nullable private final ChannelLogger channelLogger;
    @Nullable private final Executor executor;
    @Nullable private final String overrideAuthority;
    @Nullable private final MetricRecorder metricRecorder;

    private Args(Builder builder) {
      this.defaultPort = checkNotNull(builder.defaultPort, "defaultPort not set");
      this.proxyDetector = checkNotNull(builder.proxyDetector, "proxyDetector not set");
      this.syncContext = checkNotNull(builder.syncContext, "syncContext not set");
      this.serviceConfigParser =
          checkNotNull(builder.serviceConfigParser, "serviceConfigParser not set");
      this.extensions =
          builder.extensionsBuilder != null ? builder.extensionsBuilder.build() : Extensions.EMPTY;
      this.scheduledExecutorService = builder.scheduledExecutorService;
      this.channelLogger = builder.channelLogger;
      this.executor = builder.executor;
      this.overrideAuthority = builder.overrideAuthority;
      this.metricRecorder = builder.metricRecorder;
    }

    /**
     * The port number used in case the target or the underlying naming system doesn't provide a
     * port number.
     *
     * @since 1.21.0
     */
    // <p>TODO: Only meaningful for InetSocketAddress producers. Move to {@link Extensions}?
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
     * Gets the value of an "extension" arg by key, or {@code null} if it's not set.
     *
     * <p>While ordinary {@link Args} should be universally useful and meaningful, extension
     * arguments can apply just to resolvers of a certain URI scheme, just to resolvers producing a
     * particular type of {@link java.net.SocketAddress}, or even an individual {@link NameResolver}
     * subclass. Extension args are identified by an instance of {@link Args.Key} which should be
     * defined in a java package and class appropriate to the argument's scope.
     *
     * <p>{@link Args} are normally reserved for information in *support* of name resolution, not
     * the name to be resolved itself. However, there are rare cases where all or part of the target
     * name can't be represented by any standard URI scheme or can't be encoded as a String at all.
     * Extension args, in contrast, can be an arbitrary Java type, making them a useful work around
     * in these cases.
     *
     * <p>Extension args can also be used simply to avoid adding inappropriate deps to the low level
     * io.grpc package.
     */
    @Nullable
    public <T> T getExtension(Key<T> key) {
      return extensions.get(key);
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
    public Executor getOffloadExecutor() {
      return executor;
    }

    /**
     * Returns the overrideAuthority from channel {@link ManagedChannelBuilder#overrideAuthority}.
     * Overrides the host name for L7 HTTP virtual host matching. Almost all name resolvers should
     * not use this.
     *
     * @since 1.49.0
     */
    @Nullable
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9406")
    public String getOverrideAuthority() {
      return overrideAuthority;
    }

    /**
     * Returns the {@link MetricRecorder} that the channel uses to record metrics.
     */
    @Nullable
    public MetricRecorder getMetricRecorder() {
      return metricRecorder;
    }


    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("defaultPort", defaultPort)
          .add("proxyDetector", proxyDetector)
          .add("syncContext", syncContext)
          .add("serviceConfigParser", serviceConfigParser)
          .add("extensions", extensions)
          .add("scheduledExecutorService", scheduledExecutorService)
          .add("channelLogger", channelLogger)
          .add("executor", executor)
          .add("overrideAuthority", overrideAuthority)
          .add("metricRecorder", metricRecorder)
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
      builder.extensionsBuilder = extensions.toBuilder();
      builder.setScheduledExecutorService(scheduledExecutorService);
      builder.setChannelLogger(channelLogger);
      builder.setOffloadExecutor(executor);
      builder.setOverrideAuthority(overrideAuthority);
      builder.setMetricRecorder(metricRecorder);
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
      private String overrideAuthority;
      private MetricRecorder metricRecorder;
      private Extensions.Builder extensionsBuilder = Extensions.newBuilder();

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
      public Builder setOffloadExecutor(Executor executor) {
        this.executor = executor;
        return this;
      }

      /**
       * See {@link Args#getOverrideAuthority()}. This is an optional field.
       *
       * @since 1.49.0
       */
      @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9406")
      public Builder setOverrideAuthority(String authority) {
        this.overrideAuthority = authority;
        return this;
      }

      /** See {@link Args#getExtension(Key)}. */
      public <T> Builder setExtension(Key<T> key, T value) {
        extensionsBuilder.set(key, value);
        return this;
      }

      /** Copies each extension argument from 'extensions' into this Builder. */
      @Internal
      public Builder setAllExtensions(Extensions extensions) {
        extensionsBuilder.setAll(extensions);
        return this;
      }

      /**
       * See {@link Args#getMetricRecorder()}. This is an optional field.
       */
      public Builder setMetricRecorder(MetricRecorder metricRecorder) {
        this.metricRecorder = metricRecorder;
        return this;
      }

      /**
       * Builds an {@link Args}.
       *
       * @since 1.21.0
       */
      public Args build() {
        return new Args(this);
      }
    }

    /**
     * Identifies an externally-defined extension argument that can be stored in {@link Args}.
     *
     * <p>Uses reference equality so keys should be defined as global constants.
     *
     * @param <T> type of values that can be stored under this key
     */
    @Immutable
    @SuppressWarnings("UnusedTypeParameter")
    public static final class Key<T> {
      private final String debugString;

      private Key(String debugString) {
        this.debugString = debugString;
      }

      @Override
      public String toString() {
        return debugString;
      }

      /**
       * Creates a new instance of {@link Key}.
       *
       * @param debugString a string used to describe the key, used for debugging.
       * @param <T> Key type
       * @return a new instance of Key
       */
      public static <T> Key<T> create(String debugString) {
        return new Key<>(debugString);
      }
    }

    /**
     * An immutable type-safe container of externally-defined {@link NameResolver} arguments.
     *
     * <p>NB: This class overrides neither {@code equals()} nor {@code hashCode()}.
     */
    @Immutable
    @Internal
    public static final class Extensions {
      private static final IdentityHashMap<Key<?>, Object> EMPTY_MAP = new IdentityHashMap<>();

      /** The canonical empty instance. */
      public static final Extensions EMPTY = new Extensions(EMPTY_MAP);

      private final IdentityHashMap<Key<?>, Object> data;

      private Extensions(IdentityHashMap<Key<?>, Object> data) {
        assert data != null;
        this.data = data;
      }

      /** Gets the value for the key, or {@code null} if it's not present. */
      @SuppressWarnings("unchecked")
      @Nullable
      public <T> T get(Key<T> key) {
        return (T) data.get(key);
      }

      Set<Key<?>> keysForTest() {
        return Collections.unmodifiableSet(data.keySet());
      }

      /** Creates a new builder. */
      public static Builder newBuilder() {
        return new Builder(EMPTY);
      }

      /**
       * Creates a new builder that is pre-populated with the content of this container.
       *
       * @return a new builder.
       */
      public Builder toBuilder() {
        return new Builder(this);
      }

      @Override
      public String toString() {
        return data.toString();
      }

      /** Fluently builds instances of {@link Extensions}. */
      public static final class Builder {
        private Extensions base;
        private IdentityHashMap<Key<?>, Object> newdata;

        private Builder(Extensions base) {
          assert base != null;
          this.base = base;
        }

        private IdentityHashMap<Key<?>, Object> data(int size) {
          if (newdata == null) {
            newdata = new IdentityHashMap<>(size);
          }
          return newdata;
        }

        /**
         * Associates 'value' with 'key', replacing any previously associated value.
         *
         * @return this
         */
        public <T> Builder set(Key<T> key, T value) {
          data(1).put(key, value);
          return this;
        }

        /** Copies all entries from 'other' into this Builder. */
        public Builder setAll(Extensions other) {
          data(other.data.size()).putAll(other.data);
          return this;
        }

        /** Builds the extensions. */
        public Extensions build() {
          if (newdata != null) {
            for (Map.Entry<Key<?>, Object> entry : base.data.entrySet()) {
              if (!newdata.containsKey(entry.getKey())) {
                newdata.put(entry.getKey(), entry.getValue());
              }
            }
            base = new Extensions(newdata);
            newdata = null;
          }
          return base;
        }
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
    private final StatusOr<List<EquivalentAddressGroup>> addressesOrError;
    @ResolutionResultAttr
    private final Attributes attributes;
    @Nullable
    private final ConfigOrError serviceConfig;

    ResolutionResult(
        StatusOr<List<EquivalentAddressGroup>> addressesOrError,
        @ResolutionResultAttr Attributes attributes,
        ConfigOrError serviceConfig) {
      this.addressesOrError = addressesOrError;
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
          .setAddressesOrError(addressesOrError)
          .setAttributes(attributes)
          .setServiceConfig(serviceConfig);
    }

    /**
     * Gets the addresses resolved by name resolution.
     *
     * @since 1.21.0
     * @deprecated Will be superseded by getAddressesOrError
     */
    @Deprecated
    public List<EquivalentAddressGroup> getAddresses() {
      return addressesOrError.getValue();
    }

    /**
     * Gets the addresses resolved by name resolution or the error in doing so.
     *
     * @since 1.65.0
     */
    public StatusOr<List<EquivalentAddressGroup>> getAddressesOrError() {
      return addressesOrError;
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
     * Gets the Service Config parsed by {@link Args#getServiceConfigParser}.
     *
     * @since 1.21.0
     */
    @Nullable
    public ConfigOrError getServiceConfig() {
      return serviceConfig;
    }

    @Override
    public String toString() {
      ToStringHelper stringHelper = MoreObjects.toStringHelper(this);
      stringHelper.add("addressesOrError", addressesOrError.toString());
      stringHelper.add("attributes", attributes);
      stringHelper.add("serviceConfigOrError", serviceConfig);
      return stringHelper.toString();
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
      return Objects.equal(this.addressesOrError, that.addressesOrError)
          && Objects.equal(this.attributes, that.attributes)
          && Objects.equal(this.serviceConfig, that.serviceConfig);
    }

    /**
     * Useful for testing.  May be slow to calculate.
     */
    @Override
    public int hashCode() {
      return Objects.hashCode(addressesOrError, attributes, serviceConfig);
    }

    /**
     * A builder for {@link ResolutionResult}.
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public static final class Builder {
      private StatusOr<List<EquivalentAddressGroup>> addresses =
          StatusOr.fromValue(Collections.emptyList());
      private Attributes attributes = Attributes.EMPTY;
      @Nullable
      private ConfigOrError serviceConfig;
      //  Make sure to update #toBuilder above!

      Builder() {}

      /**
       * Sets the addresses resolved by name resolution.  This field is required.
       *
       * @since 1.21.0
       * @deprecated Will be superseded by setAddressesOrError
       */
      @Deprecated
      public Builder setAddresses(List<EquivalentAddressGroup> addresses) {
        setAddressesOrError(StatusOr.fromValue(addresses));
        return this;
      }

      /**
       * Sets the addresses resolved by name resolution or the error in doing so. This field is
       * required.
       * @param addresses Resolved addresses or an error in resolving addresses
       */
      public Builder setAddressesOrError(StatusOr<List<EquivalentAddressGroup>> addresses) {
        this.addresses = checkNotNull(addresses, "StatusOr addresses cannot be null.");
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
       * Sets the Service Config parsed by {@link Args#getServiceConfigParser}.
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
   * Represents either a successfully parsed service config, containing all necessary parts to be
   * later applied by the channel, or a Status containing the error encountered while parsing.
   *
   * @since 1.20.0
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
