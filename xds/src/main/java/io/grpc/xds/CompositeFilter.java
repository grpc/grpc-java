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

package io.grpc.xds;

import com.github.xds.type.matcher.v3.Matcher;
import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.extensions.common.matching.v3.ExtensionWithMatcher;
import io.envoyproxy.envoy.extensions.common.matching.v3.ExtensionWithMatcherPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.composite.v3.Composite;
import io.envoyproxy.envoy.extensions.filters.http.composite.v3.ExecuteFilterAction;
import io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.internal.UnifiedMatcher;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;

public final class CompositeFilter implements Filter {

  static final String TYPE_URL_EXTENSION_WITH_MATCHER =
      "type.googleapis.com/envoy.extensions.common.matching.v3.ExtensionWithMatcher";
  static final String TYPE_URL_COMPOSITE =
      "type.googleapis.com/envoy.extensions.filters.http.composite.v3.Composite";
  static final String TYPE_URL_EXTENSION_WITH_MATCHER_PER_ROUTE =
      "type.googleapis.com/envoy.extensions.common.matching.v3.ExtensionWithMatcherPerRoute";
  static final String TYPE_URL_HTTP_REQUEST_HEADER_MATCH_INPUT =
      "type.googleapis.com/envoy.type.matcher.v3.HttpRequestHeaderMatchInput";
  static final String TYPE_URL_SOURCE_IP_INPUT =
      "type.googleapis.com/envoy.extensions.matching.common_inputs.network.v3.SourceIPInput";
  static final String TYPE_URL_SOURCE_PORT_INPUT =
      "type.googleapis.com/envoy.extensions.matching.common_inputs.network.v3.SourcePortInput";
  static final String TYPE_URL_DIRECT_SOURCE_IP_INPUT =
      "type.googleapis.com/envoy.extensions.matching.common_inputs.network.v3.DirectSourceIPInput";
  static final String TYPE_URL_SERVER_NAME_INPUT =
      "type.googleapis.com/envoy.extensions.matching.common_inputs.network.v3.ServerNameInput";

  private static final CompositeFilter INSTANCE = new CompositeFilter();

  private CompositeFilter() {
  }

  static final class Provider implements Filter.Provider {
    @Override
    public String[] typeUrls() {
      return new String[] {
          TYPE_URL_EXTENSION_WITH_MATCHER,
          TYPE_URL_COMPOSITE,
          TYPE_URL_EXTENSION_WITH_MATCHER_PER_ROUTE
      };
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public boolean isServerFilter() {
      return true;
    }

    @Override
    public Filter newInstance(String name) {
      return INSTANCE;
    }

    @Override
    public ConfigOrError<CompositeFilterConfig> parseFilterConfig(Message rawProtoMessage) {
      if (!isSupported()) {
        return ConfigOrError.fromError("Composite Filter is experimental "
            + "and disabled by default.");
      }
      return parseConfig(rawProtoMessage);
    }

    @Override
    public ConfigOrError<CompositeFilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
      if (!isSupported()) {
        return ConfigOrError.fromError("Composite Filter is experimental and disabled by default.");
      }
      return parseConfig(rawProtoMessage);
    }

    private boolean isSupported() {
      return GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER",
          false);
    }

    private ConfigOrError<CompositeFilterConfig> parseConfig(Message rawProtoMessage) {
      Matcher matcherProto = null;
      if (rawProtoMessage instanceof Any) {
        try {
          Any any = (Any) rawProtoMessage;
          if (any.is(ExtensionWithMatcher.class)) {
            ExtensionWithMatcher proto = any.unpack(ExtensionWithMatcher.class);
            matcherProto = proto.getXdsMatcher();
          } else if (any.is(ExtensionWithMatcherPerRoute.class)) {
            ExtensionWithMatcherPerRoute proto = any.unpack(ExtensionWithMatcherPerRoute.class);
            matcherProto = proto.getXdsMatcher();
          } else if (any.is(Composite.class)) {
            return ConfigOrError.fromConfig(new CompositeFilterConfig(null));
          }
        } catch (InvalidProtocolBufferException e) {
          return ConfigOrError.fromError("Invalid proto: " + e);
        }
      }

      if (matcherProto == null) {
        return ConfigOrError.fromConfig(new CompositeFilterConfig(null));
      }

      try {
        UnifiedMatcher<FilterDelegate> matcher = UnifiedMatcher.create(matcherProto,
            (com.github.xds.core.v3.TypedExtensionConfig config) -> {
              try {
                Any actionAny = config.getTypedConfig();
                if (actionAny.is(ExecuteFilterAction.class)) {
                  ExecuteFilterAction executeAction = actionAny.unpack(ExecuteFilterAction.class);
                  FractionalPercent samplePercent = executeAction.hasSamplePercent()
                      ? executeAction.getSamplePercent().getDefaultValue()
                      : null;
                  List<TypedExtensionConfig> childConfigs = new ArrayList<>();

                  if (executeAction.hasFilterChain()) {
                    childConfigs.addAll(executeAction.getFilterChain().getTypedConfigList());
                  } else if (executeAction.hasTypedConfig()) {
                    childConfigs.add(executeAction.getTypedConfig());
                  }

                  if (!childConfigs.isEmpty()) {
                    List<DelegateEntry> delegates = new ArrayList<>();
                    for (TypedExtensionConfig childFilterConfig : childConfigs) {
                      String typeUrl = childFilterConfig.getTypedConfig().getTypeUrl();
                      Message rawConfig = childFilterConfig.getTypedConfig();

                      try {
                        if (typeUrl.equals("type.googleapis.com/udpa.type.v1.TypedStruct")) {
                          com.github.udpa.udpa.type.v1.TypedStruct typedStruct = childFilterConfig
                              .getTypedConfig()
                              .unpack(com.github.udpa.udpa.type.v1.TypedStruct.class);
                          typeUrl = typedStruct.getTypeUrl();
                          rawConfig = typedStruct.getValue();
                        } else if (typeUrl.equals("type.googleapis.com/xds.type.v3.TypedStruct")) {
                          com.github.xds.type.v3.TypedStruct typedStruct = childFilterConfig
                              .getTypedConfig()
                              .unpack(com.github.xds.type.v3.TypedStruct.class);
                          typeUrl = typedStruct.getTypeUrl();
                          rawConfig = typedStruct.getValue();
                        }
                      } catch (InvalidProtocolBufferException e) {
                        throw new IllegalArgumentException("Failed to unpack TypedStruct", e);
                      }

                      Filter.Provider provider = FilterRegistry.getDefaultRegistry().get(typeUrl);
                      if (provider == null) {
                        throw new IllegalArgumentException("Action filter not found: " + typeUrl);
                      }
                      ConfigOrError<? extends FilterConfig> parsed = provider
                          .parseFilterConfig(rawConfig);
                      if (parsed.errorDetail != null) {
                        throw new IllegalArgumentException(
                            "Failed to parse child filter: " + parsed.errorDetail);
                      }
                      delegates.add(new DelegateEntry(provider, parsed.config));
                    }
                    return new FilterDelegate(delegates, samplePercent);
                  }
                }
              } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
              }
              return null;
            });
        return ConfigOrError.fromConfig(new CompositeFilterConfig(matcher));
      } catch (Exception e) {
        return ConfigOrError.fromError("Failed to create matcher: " + e.getMessage());
      }
    }
  }

  static final class CompositeFilterConfig implements FilterConfig {
    @Nullable
    final UnifiedMatcher<FilterDelegate> matcher;

    CompositeFilterConfig(@Nullable UnifiedMatcher<FilterDelegate> matcher) {
      this.matcher = matcher;
    }

    @Override
    public String typeUrl() {
      return TYPE_URL_COMPOSITE;
    }
  }

  static final class FilterDelegate {
    final List<DelegateEntry> delegates;
    @Nullable
    final FractionalPercent samplePercent;

    FilterDelegate(List<DelegateEntry> delegates, @Nullable FractionalPercent samplePercent) {
      this.delegates = Collections.unmodifiableList(delegates);
      this.samplePercent = samplePercent;
    }

    boolean shouldExecute() {
      if (samplePercent == null) {
        return true;
      }
      int numerator = samplePercent.getNumerator();
      int denominator;
      switch (samplePercent.getDenominator()) {
        case HUNDRED:
          denominator = 100;
          break;
        case TEN_THOUSAND:
          denominator = 10000;
          break;
        case MILLION:
          denominator = 1000000;
          break;
        default:
          denominator = 100;
      }
      return ThreadLocalRandom.current().nextInt(denominator) < numerator;
    }
  }

  static final class DelegateEntry {
    final Filter.Provider provider;
    final FilterConfig config;

    DelegateEntry(Filter.Provider provider, FilterConfig config) {
      this.provider = provider;
      this.config = config;
    }
  }

  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig config, FilterConfig overrideConfig,
      ScheduledExecutorService scheduler) {
    Preconditions.checkNotNull(config, "config");
    UnifiedMatcher<FilterDelegate> matcher = getMatcher(config, overrideConfig);
    if (matcher == null) {
      return null;
    }

    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions callOptions,
          io.grpc.Channel next) {
        return new CompositeClientCall<>(method, callOptions, next, matcher, scheduler);
      }
    };
  }

  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config, FilterConfig overrideConfig) {
    UnifiedMatcher<FilterDelegate> matcher = getMatcher(config, overrideConfig);
    if (matcher == null) {
      return null;
    }

    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // Populate MatchingData with headers and server call attributes
        UnifiedMatcher.MatchingData data = new MatchingDataImpl(headers, null,
            call.getAttributes());

        List<FilterDelegate> delegates = matcher.match(data);
        if (delegates != null && !delegates.isEmpty()) {
          List<ServerInterceptor> interceptors = new ArrayList<>();
          final List<Filter> filters = new ArrayList<>();
          try {
            for (FilterDelegate delegate : delegates) {
              if (!delegate.shouldExecute()) {
                continue;
              }
              for (DelegateEntry entry : delegate.delegates) {
                Filter filter = entry.provider.newInstance("composite_child");
                filters.add(filter);
                ServerInterceptor interceptor = filter.buildServerInterceptor(entry.config, null);
                if (interceptor != null) {
                  interceptors.add(interceptor);
                }
              }
            }
          } catch (Throwable t) {
            for (Filter f : filters) {
              f.close();
            }
            throw t;
          }

          if (!interceptors.isEmpty()) {
            ServerCallHandler<ReqT, RespT> wrapped = next;
            for (int i = interceptors.size() - 1; i >= 0; i--) {
              final ServerInterceptor interceptor = interceptors.get(i);
              final ServerCallHandler<ReqT, RespT> current = wrapped;
              wrapped = new ServerCallHandler<ReqT, RespT>() {
                @Override
                public ServerCall.Listener<ReqT> startCall(
                    ServerCall<ReqT, RespT> call, Metadata headers) {
                  return interceptor.interceptCall(call, headers, current);
                }
              };
            }
            ServerCall.Listener<ReqT> listener = wrapped.startCall(call, headers);
            // Wrap listener to close filters
            return new SimpleForwardingServerCallListener<ReqT>(listener) {
              @Override
              public void onCancel() {
                try {
                  super.onCancel();
                } finally {
                  closeFilters();
                }
              }

              @Override
              public void onComplete() {
                try {
                  super.onComplete();
                } finally {
                  closeFilters();
                }
              }

              private void closeFilters() {
                for (Filter f : filters) {
                  f.close();
                }
              }
            };
          } else {
            for (Filter f : filters) {
              f.close();
            }
          }
        }
        return next.startCall(call, headers);
      }
    };
  }

  private UnifiedMatcher<FilterDelegate> getMatcher(
      FilterConfig config, FilterConfig overrideConfig) {
    CompositeFilterConfig effective = (CompositeFilterConfig) config;
    if (overrideConfig != null) {
      CompositeFilterConfig override = (CompositeFilterConfig) overrideConfig;
      if (override.matcher != null) {
        return override.matcher;
      }
    }
    return effective.matcher;
  }

  private static class MatchingDataImpl implements UnifiedMatcher.MatchingData {
    private final Metadata headers;
    private final io.grpc.CallOptions callOptions;
    private final io.grpc.Attributes attributes;

    MatchingDataImpl(Metadata headers, @Nullable io.grpc.CallOptions callOptions,
        io.grpc.Attributes attributes) {
      this.headers = headers;
      this.callOptions = callOptions;
      this.attributes = attributes;
    }

    MatchingDataImpl(Metadata headers, @Nullable io.grpc.CallOptions callOptions) {
      this(headers, callOptions, io.grpc.Attributes.EMPTY);
    }

    @Override
    public String getRelayedInput(com.github.xds.core.v3.TypedExtensionConfig inputConfig) {
      String typeUrl = inputConfig.getTypedConfig().getTypeUrl();
      if (TYPE_URL_HTTP_REQUEST_HEADER_MATCH_INPUT.equals(typeUrl)) {
        try {
          HttpRequestHeaderMatchInput input = inputConfig.getTypedConfig()
              .unpack(HttpRequestHeaderMatchInput.class);
          String headerName = input.getHeaderName();
          return headers.get(Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
        } catch (InvalidProtocolBufferException e) {
          // log/ignore
        }
      } else if (TYPE_URL_SOURCE_IP_INPUT.equals(typeUrl)) {
        SocketAddress addr = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (addr instanceof InetSocketAddress) {
          return ((InetSocketAddress) addr).getAddress().getHostAddress();
        }
      } else if (TYPE_URL_SOURCE_PORT_INPUT.equals(typeUrl)) {
        SocketAddress addr = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (addr instanceof InetSocketAddress) {
          return String.valueOf(((InetSocketAddress) addr).getPort());
        }
      } else if (TYPE_URL_DIRECT_SOURCE_IP_INPUT.equals(typeUrl)) {
        // For standard gRPC, direct source IP is usually the same as remote addr,
        // unless there is some proxying info that gRPC doesn't standardly expose as
        // separate attribute.
        // We fallback to remote addr.
        SocketAddress addr = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (addr instanceof InetSocketAddress) {
          return ((InetSocketAddress) addr).getAddress().getHostAddress();
        }
      } else if (TYPE_URL_SERVER_NAME_INPUT.equals(typeUrl)) {
        if (callOptions != null && callOptions.getAuthority() != null) {
          return callOptions.getAuthority();
        }
        SocketAddress remoteAddr = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (remoteAddr instanceof InetSocketAddress) {
          // Fallback to simpler check if needed, but SNI is usually in SSLSession
        }
        SSLSession session = attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session instanceof javax.net.ssl.ExtendedSSLSession) {
          javax.net.ssl.ExtendedSSLSession extSession = (javax.net.ssl.ExtendedSSLSession) session;
          List<SNIServerName> names = extSession.getRequestedServerNames();
          for (SNIServerName name : names) {
            if (name.getType() == StandardConstants.SNI_HOST_NAME && name instanceof SNIHostName) {
              return ((SNIHostName) name).getAsciiName();
            }
          }
        }
      }
      return null;
    }
  }

  private static class CompositeClientCall<ReqT, RespT> extends io.grpc.ClientCall<ReqT, RespT> {
    private final MethodDescriptor<ReqT, RespT> method;
    private final io.grpc.CallOptions callOptions;
    private final io.grpc.Channel next;
    private final UnifiedMatcher<FilterDelegate> matcher;
    private final ScheduledExecutorService scheduler;

    private io.grpc.ClientCall<ReqT, RespT> delegate;
    private final java.util.List<Runnable> pendingEvents = new java.util.ArrayList<>();
    private boolean started;
    private boolean cancelled;
    private String cancelMessage;
    private Throwable cancelCause;

    CompositeClientCall(MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions callOptions,
        io.grpc.Channel next, UnifiedMatcher<FilterDelegate> matcher,
        ScheduledExecutorService scheduler) {
      this.method = method;
      this.callOptions = callOptions;
      this.next = next;
      this.matcher = matcher;
      this.scheduler = scheduler;
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      if (started) {
        delegate.start(responseListener, headers);
        return;
      }
      started = true;

      UnifiedMatcher.MatchingData data = new MatchingDataImpl(headers, callOptions);
      List<FilterDelegate> filterDelegates = matcher.match(data);

      if (filterDelegates != null && !filterDelegates.isEmpty()) {
        List<ClientInterceptor> interceptors = new ArrayList<>();
        List<Filter> filters = new ArrayList<>();
        try {
          for (FilterDelegate filterDelegate : filterDelegates) {
            if (!filterDelegate.shouldExecute()) {
              continue;
            }
            for (DelegateEntry entry : filterDelegate.delegates) {
              Filter filter = entry.provider.newInstance("composite_child");
              filters.add(filter);
              ClientInterceptor interceptor = filter.buildClientInterceptor(entry.config, null,
                  scheduler);
              if (interceptor != null) {
                interceptors.add(interceptor);
              }
            }
          }
        } catch (Throwable t) {
          for (Filter f : filters) {
            f.close();
          }
          throw t;
        }

        if (!interceptors.isEmpty()) {
          delegate = ClientInterceptors.intercept(next, interceptors).newCall(method, callOptions);
          // Wrap responseListener to close filters
          responseListener = new SimpleForwardingClientCallListener<RespT>(responseListener) {
            @Override
            public void onClose(Status status, Metadata trailers) {
              try {
                super.onClose(status, trailers);
              } finally {
                for (Filter f : filters) {
                  f.close();
                }
              }
            }
          };
        } else {
          for (Filter f : filters) {
            f.close();
          }
        }
      }

      if (delegate == null) {
        delegate = next.newCall(method, callOptions);
      }

      delegate.start(responseListener, headers);

      for (Runnable r : pendingEvents) {
        r.run();
      }
      pendingEvents.clear();

      if (cancelled) {
        delegate.cancel(cancelMessage, cancelCause);
      }
    }

    @Override
    public void request(int numMessages) {
      if (delegate != null) {
        delegate.request(numMessages);
      } else {
        pendingEvents.add(() -> delegate.request(numMessages));
      }
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
      if (delegate != null) {
        delegate.cancel(message, cause);
      } else {
        cancelled = true;
        cancelMessage = message;
        cancelCause = cause;
      }
    }

    @Override
    public void halfClose() {
      if (delegate != null) {
        delegate.halfClose();
      } else {
        pendingEvents.add(() -> delegate.halfClose());
      }
    }

    @Override
    public void sendMessage(ReqT message) {
      if (delegate != null) {
        delegate.sendMessage(message);
      } else {
        pendingEvents.add(() -> delegate.sendMessage(message));
      }
    }

    @Override
    public void setMessageCompression(boolean enabled) {
      if (delegate != null) {
        delegate.setMessageCompression(enabled);
      } else {
        pendingEvents.add(() -> delegate.setMessageCompression(enabled));
      }
    }
  }
}
