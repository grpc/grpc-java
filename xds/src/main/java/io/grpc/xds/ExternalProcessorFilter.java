package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;

import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcOverrides;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.HeaderForwardingRules;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpBody;
import io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers;
import io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProtocolConfiguration;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.DelayedClientCall;
import io.grpc.internal.SerializingExecutor;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceParseException;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.MatcherParser;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParseException;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParser;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Filter for external processing as per gRFC A93.
 */
public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = 
      "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  final String filterInstanceName;
  private final CachedChannelManager cachedChannelManager;

  public ExternalProcessorFilter(String name) {
    this(name, new CachedChannelManager());
  }

  ExternalProcessorFilter(String name, CachedChannelManager cachedChannelManager) {
    this.filterInstanceName = checkNotNull(name, "name");
    this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
  }

  @Override
  public void close() {
    cachedChannelManager.close();
  }

  static final class Provider implements Filter.Provider {
    @Override
    public String[] typeUrls() {
      return new String[]{TYPE_URL};
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public ExternalProcessorFilter newInstance(String name) {
      return new ExternalProcessorFilter(name);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterConfig> parseFilterConfig(
        Message rawProtoMessage, FilterContext context) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      ExternalProcessor externalProcessor;
      try {
        externalProcessor = ((Any) rawProtoMessage).unpack(ExternalProcessor.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }

      return ExternalProcessorFilterConfig.create(externalProcessor, context);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterConfig> parseFilterConfigOverride(
        Message rawProtoMessage, FilterContext context) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      ExtProcPerRoute perRoute;
      try {
        perRoute = ((Any) rawProtoMessage).unpack(ExtProcPerRoute.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
      if (perRoute.hasOverrides()) {
        return ExternalProcessorFilterConfig.create(perRoute.getOverrides(), context);
      }
      return ConfigOrError.fromError("ExtProcPerRoute must have overrides");
    }
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(@Nullable FilterConfig filterConfig,
      @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
    ExternalProcessorFilterConfig config = (ExternalProcessorFilterConfig) filterConfig;
    if (overrideConfig instanceof ExternalProcessorFilterConfig) {
      ExternalProcessorFilterConfig over = (ExternalProcessorFilterConfig) overrideConfig;
      ExtProcOverrides overrides = over.getExtProcOverrides();
      if (overrides != null && config != null) {
        config = mergeConfigs(config, overrides);
      } else {
        config = over;
      }
    }
    checkNotNull(config, "config");
    return new ExternalProcessorInterceptor(config, cachedChannelManager, scheduler);
  }

  private static ExternalProcessorFilterConfig mergeConfigs(
      ExternalProcessorFilterConfig parent, ExtProcOverrides overrides) {
    ExternalProcessor parentProto = parent.getExternalProcessor();
    ExternalProcessor.Builder mergedProtoBuilder = parentProto.toBuilder();

    if (overrides.hasProcessingMode()) {
      mergedProtoBuilder.setProcessingMode(overrides.getProcessingMode());
    }

    if (overrides.getRequestAttributesCount() > 0) {
      mergedProtoBuilder.clearRequestAttributes()
          .addAllRequestAttributes(overrides.getRequestAttributesList());
    }
    if (overrides.getResponseAttributesCount() > 0) {
      mergedProtoBuilder.clearResponseAttributes()
          .addAllResponseAttributes(overrides.getResponseAttributesList());
    }
    if (overrides.hasGrpcService()) {
      mergedProtoBuilder.setGrpcService(overrides.getGrpcService());
    }
    
    boolean failureModeAllow = parent.getFailureModeAllow();
    if (overrides.hasFailureModeAllow()) {
       failureModeAllow = overrides.getFailureModeAllow().getValue();
       mergedProtoBuilder.setFailureModeAllow(failureModeAllow);
    }

    ConfigOrError<ExternalProcessorFilterConfig> merged =
        ExternalProcessorFilterConfig.create(mergedProtoBuilder.build(), parent.getFilterContext());
    checkNotNull(merged, "merged");
    checkState(merged.errorDetail == null, "Error merging configs: %s", merged.errorDetail);
    return merged.config;
  }

  static final class ExternalProcessorFilterConfig implements FilterConfig {

    private final ExternalProcessor externalProcessor;
    private final ExtProcOverrides extProcOverrides;
    private final GrpcServiceConfig grpcServiceConfig;
    private final Optional<HeaderMutationRulesConfig> mutationRulesConfig;
    private final Optional<HeaderForwardingRulesConfig> forwardRulesConfig;
    private final ImmutableList<String> requestAttributes;
    private final boolean disableImmediateResponse;
    private final long deferredCloseTimeoutNanos;
    private final FilterContext filterContext;

    static ConfigOrError<ExternalProcessorFilterConfig> create(
        ExternalProcessor externalProcessor, FilterContext context) {
      return createInternal(externalProcessor, null, context);
    }

    static ConfigOrError<ExternalProcessorFilterConfig> create(
        ExtProcOverrides overrides, FilterContext context) {
      return createInternal(null, overrides, context);
    }

    private static ConfigOrError<ExternalProcessorFilterConfig> createInternal(
        @Nullable ExternalProcessor externalProcessor,
        @Nullable ExtProcOverrides overrides,
        FilterContext context) {
      
      ProcessingMode mode;
      GrpcService grpcService;
      HeaderMutationRulesConfig mutationRulesConfig = null;
      HeaderForwardingRulesConfig forwardRulesConfig = null;
      ImmutableList<String> requestAttributes = ImmutableList.of();
      long deferredCloseTimeoutNanos = TimeUnit.SECONDS.toNanos(5);
      boolean disableImmediateResponse = false;

      if (externalProcessor != null) {
        mode = externalProcessor.getProcessingMode();
        grpcService = externalProcessor.getGrpcService();
        disableImmediateResponse = externalProcessor.getDisableImmediateResponse();
        requestAttributes = ImmutableList.copyOf(externalProcessor.getRequestAttributesList());

        if (externalProcessor.hasMutationRules()) {
          try {
            mutationRulesConfig = 
                HeaderMutationRulesParser.parse(externalProcessor.getMutationRules());
          } catch (HeaderMutationRulesParseException e) {
            return ConfigOrError.fromError("Error parsing HeaderMutationRules: " + e.getMessage());
          }
        }

        if (externalProcessor.hasForwardRules()) {
          forwardRulesConfig = 
              HeaderForwardingRulesConfig.create(externalProcessor.getForwardRules());
        }

        if (externalProcessor.hasDeferredCloseTimeout()) {
          Duration deferredCloseTimeout = externalProcessor.getDeferredCloseTimeout();
          try {
            Durations.checkValid(deferredCloseTimeout);
          } catch (IllegalArgumentException e) {
            return ConfigOrError.fromError("Invalid deferred_close_timeout: " + e.getMessage());
          }
          deferredCloseTimeoutNanos = Durations.toNanos(deferredCloseTimeout);
          if (deferredCloseTimeoutNanos <= 0) {
            return ConfigOrError.fromError("deferred_close_timeout must be positive");
          }
        }
      } else if (overrides != null) {
        mode = overrides.getProcessingMode();
        grpcService = overrides.getGrpcService();
        requestAttributes = ImmutableList.copyOf(overrides.getRequestAttributesList());
      } else {
        return ConfigOrError.fromError("Either externalProcessor or overrides must be non-null");
      }

      if (mode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC
          && mode.getRequestBodyMode() != ProcessingMode.BodySendMode.NONE) {
        return ConfigOrError.fromError("Invalid request_body_mode: " + mode.getRequestBodyMode()
            + ". Only GRPC and NONE are supported.");
      }
      if (mode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC
          && mode.getResponseBodyMode() != ProcessingMode.BodySendMode.NONE) {
        return ConfigOrError.fromError("Invalid response_body_mode: " + mode.getResponseBodyMode()
            + ". Only GRPC and NONE are supported.");
      }

      try {
        GrpcServiceConfig grpcServiceConfig = null;
        if (grpcService != null && grpcService.hasGoogleGrpc()) {
          grpcServiceConfig = GrpcServiceConfigParser.parse(
              grpcService, context.bootstrapInfo(), context.serverInfo());
        } else if (externalProcessor != null) {
          return ConfigOrError.fromError("Error parsing GrpcService config: " 
              + "Unsupported: GrpcService must have GoogleGrpc, got: " + grpcService);
        }
        
        return ConfigOrError.fromConfig(new ExternalProcessorFilterConfig(
            externalProcessor, overrides, grpcServiceConfig, 
            Optional.ofNullable(mutationRulesConfig), 
            Optional.ofNullable(forwardRulesConfig), requestAttributes,
            disableImmediateResponse, deferredCloseTimeoutNanos, context));
      } catch (GrpcServiceParseException e) {
        return ConfigOrError.fromError("Error parsing GrpcService config: " + e.getMessage());
      }
    }

    ExternalProcessorFilterConfig(
        @Nullable ExternalProcessor externalProcessor,
        @Nullable ExtProcOverrides extProcOverrides,
        GrpcServiceConfig grpcServiceConfig,
        Optional<HeaderMutationRulesConfig> mutationRulesConfig,
        Optional<HeaderForwardingRulesConfig> forwardRulesConfig,
        ImmutableList<String> requestAttributes,
        boolean disableImmediateResponse,
        long deferredCloseTimeoutNanos,
        FilterContext filterContext) {
      this.externalProcessor = externalProcessor;
      this.extProcOverrides = extProcOverrides;
      this.grpcServiceConfig = grpcServiceConfig;
      this.mutationRulesConfig = mutationRulesConfig;
      this.forwardRulesConfig = forwardRulesConfig;
      this.requestAttributes = requestAttributes;
      this.disableImmediateResponse = disableImmediateResponse;
      this.deferredCloseTimeoutNanos = deferredCloseTimeoutNanos;
      this.filterContext = filterContext;
    }

    @Override
    public String typeUrl() {
      return TYPE_URL;
    }

    @Nullable
    ExternalProcessor getExternalProcessor() {
      return externalProcessor;
    }

    @Nullable
    ExtProcOverrides getExtProcOverrides() {
      return extProcOverrides;
    }

    GrpcServiceConfig getGrpcServiceConfig() {
      return grpcServiceConfig;
    }

    Optional<HeaderMutationRulesConfig> getMutationRulesConfig() {
      return mutationRulesConfig;
    }

    Optional<HeaderForwardingRulesConfig> getForwardRulesConfig() {
      return forwardRulesConfig;
    }

    ImmutableList<String> getRequestAttributes() {
      return requestAttributes;
    }

    boolean getDisableImmediateResponse() {
      return disableImmediateResponse;
    }

    long getDeferredCloseTimeoutNanos() {
      return deferredCloseTimeoutNanos;
    }

    FilterContext getFilterContext() {
      return filterContext;
    }

    boolean getObservabilityMode() {
      return externalProcessor != null ? externalProcessor.getObservabilityMode() : false;
    }

    boolean getFailureModeAllow() {
      if (extProcOverrides != null && extProcOverrides.hasFailureModeAllow()) {
        return extProcOverrides.getFailureModeAllow().getValue();
      }
      return externalProcessor != null ? externalProcessor.getFailureModeAllow() : false;
    }
  }

  static final class HeaderForwardingRulesConfig {
    private final ImmutableList<Matchers.StringMatcher> allowedHeaders;
    private final ImmutableList<Matchers.StringMatcher> disallowedHeaders;

    HeaderForwardingRulesConfig(
        @Nullable ImmutableList<Matchers.StringMatcher> allowedHeaders,
        @Nullable ImmutableList<Matchers.StringMatcher> disallowedHeaders) {
      this.allowedHeaders = allowedHeaders;
      this.disallowedHeaders = disallowedHeaders;
    }

    static HeaderForwardingRulesConfig create(HeaderForwardingRules proto) {
      ImmutableList<Matchers.StringMatcher> allowedHeaders = null;
      if (proto.hasAllowedHeaders()) {
        allowedHeaders = MatcherParser.parseListStringMatcher(proto.getAllowedHeaders());
      }
      ImmutableList<Matchers.StringMatcher> disallowedHeaders = null;
      if (proto.hasDisallowedHeaders()) {
        disallowedHeaders = MatcherParser.parseListStringMatcher(proto.getDisallowedHeaders());
      }
      return new HeaderForwardingRulesConfig(allowedHeaders, disallowedHeaders);
    }

    boolean isAllowed(String headerName) {
      String lowerHeaderName = headerName.toLowerCase(Locale.ROOT);
      if (allowedHeaders != null) {
        boolean matched = false;
        for (Matchers.StringMatcher matcher : allowedHeaders) {
          if (matcher.matches(lowerHeaderName)) {
            matched = true;
            break;
          }
        }
        if (!matched) {
          return false;
        }
      }
      if (disallowedHeaders != null) {
        for (Matchers.StringMatcher matcher : disallowedHeaders) {
          if (matcher.matches(lowerHeaderName)) {
            return false;
          }
        }
      }
      return true;
    }
  }

  static final class ExternalProcessorInterceptor implements ClientInterceptor {
    private final CachedChannelManager cachedChannelManager;
    private final ExternalProcessorFilterConfig filterConfig;
    private final ScheduledExecutorService scheduler;

    private static final MethodDescriptor.Marshaller<InputStream> RAW_MARSHALLER =
        new MethodDescriptor.Marshaller<InputStream>() {
          @Override
          public InputStream stream(InputStream value) { return value; }
          @Override
          public InputStream parse(InputStream stream) { return stream; }
        };

    ExternalProcessorInterceptor(ExternalProcessorFilterConfig filterConfig,
        CachedChannelManager cachedChannelManager,
        ScheduledExecutorService scheduler) {
      this.filterConfig = filterConfig;
      this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
      this.scheduler = checkNotNull(scheduler, "scheduler");
    }

    @VisibleForTesting
    ExternalProcessorFilterConfig getFilterConfig() {
      return filterConfig;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      SerializingExecutor serializingExecutor = new SerializingExecutor(callOptions.getExecutor());
      
      ExternalProcessorGrpc.ExternalProcessorStub extProcStub = ExternalProcessorGrpc.newStub(
          cachedChannelManager.getChannel(filterConfig.grpcServiceConfig))
          .withExecutor(serializingExecutor);
      
      if (filterConfig.grpcServiceConfig.timeout() != null 
          && filterConfig.grpcServiceConfig.timeout().isPresent()) {
        long timeoutNanos = filterConfig.grpcServiceConfig.timeout().get().toNanos();
        if (timeoutNanos > 0) {
          extProcStub = extProcStub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
        }
      }

      ImmutableList<HeaderValue> initialMetadata = filterConfig.grpcServiceConfig.initialMetadata();
      extProcStub = extProcStub.withInterceptors(new ClientInterceptor() {
        @Override
        public <ExtReqT, ExtRespT> ClientCall<ExtReqT, ExtRespT> interceptCall(
            MethodDescriptor<ExtReqT, ExtRespT> extMethod, 
            CallOptions extCallOptions, 
            Channel extNext) {
          return new SimpleForwardingClientCall<ExtReqT, ExtRespT>(
              extNext.newCall(extMethod, extCallOptions)) {
            @Override
            public void start(Listener<ExtRespT> responseListener, Metadata headers) {
              for (HeaderValue headerValue : initialMetadata) {
                String key = headerValue.key();
                if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                  if (headerValue.rawValue().isPresent()) {
                    Metadata.Key<byte[]> metadataKey = 
                        Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
                    headers.put(metadataKey, headerValue.rawValue().get().toByteArray());
                  }
                } else {
                  if (headerValue.value().isPresent()) {
                    Metadata.Key<String> metadataKey = 
                        Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
                    headers.put(metadataKey, headerValue.value().get());
                  }
                }
              }
              super.start(responseListener, headers);
            }
          };
        }
      });

      MethodDescriptor<InputStream, InputStream> rawMethod = 
          method.toBuilder(RAW_MARSHALLER, RAW_MARSHALLER).build();
      ClientCall<InputStream, InputStream> rawCall = next.newCall(rawMethod, callOptions);

      // Create a local subclass instance to buffer outbound actions
      DataPlaneDelayedCall<InputStream, InputStream> delayedCall =
          new DataPlaneDelayedCall<>(
              serializingExecutor, scheduler, callOptions.getDeadline());

      DataPlaneClientCall dataPlaneCall = new DataPlaneClientCall(
          delayedCall, rawCall, extProcStub, filterConfig, filterConfig.getMutationRulesConfig(),
          scheduler, method, next);

      return new ClientCall<ReqT, RespT>() {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          dataPlaneCall.start(new Listener<InputStream>() {
            @Override
            public void onHeaders(Metadata headers) {
              responseListener.onHeaders(headers);
            }

            @Override
            public void onMessage(InputStream message) {
              responseListener.onMessage(method.getResponseMarshaller().parse(message));
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              responseListener.onClose(status, trailers);
            }

            @Override
            public void onReady() {
              responseListener.onReady();
            }
          }, headers);
        }

        @Override
        public void request(int numMessages) {
          dataPlaneCall.request(numMessages);
        }

        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {
          dataPlaneCall.cancel(message, cause);
        }

        @Override
        public void halfClose() {
          dataPlaneCall.halfClose();
        }

        @Override
        public void sendMessage(ReqT message) {
          dataPlaneCall.sendMessage(method.getRequestMarshaller().stream(message));
        }

        @Override
        public boolean isReady() {
          return dataPlaneCall.isReady();
        }

        @Override
        public void setMessageCompression(boolean enabled) {
          dataPlaneCall.setMessageCompression(enabled);
        }

        @Override
        public Attributes getAttributes() {
          return dataPlaneCall.getAttributes();
        }
      };
    }

    // --- SHARED UTILITY METHODS ---
    private static HeaderMap toHeaderMap(Metadata metadata, Optional<HeaderForwardingRulesConfig> forwardRules) {
      HeaderMap.Builder builder =
          HeaderMap.newBuilder();

      for (String key : metadata.keys()) {
        if (forwardRules.isPresent() && !forwardRules.get().isAllowed(key)) {
          continue;
        }
        // Skip binary headers for this basic mapping
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          Metadata.Key<byte[]> binKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
          Iterable<byte[]> values = metadata.getAll(binKey);
          if (values != null) {
            for (byte[] binValue : values) {
              String encoded = BaseEncoding.base64().encode(binValue);
              io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                  io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                      .setKey(key.toLowerCase(Locale.ROOT))
                      .setValue(encoded)
                      .build();
              builder.addHeaders(headerValue);
            }
          }
        } else {
          Metadata.Key<String> asciiKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          Iterable<String> values = metadata.getAll(asciiKey);
          if (values != null) {
            for (String value : values) {
              io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                  io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                      .setKey(key.toLowerCase(Locale.ROOT))
                      .setValue(value)
                      .build();
              builder.addHeaders(headerValue);
            }
          }
        }
      }
      return builder.build();
    }

    private static ImmutableMap<String, Struct> collectAttributes(
        ImmutableList<String> requestedAttributes,
        MethodDescriptor<?, ?> method,
        Channel channel,
        Metadata headers) {
      if (requestedAttributes.isEmpty()) {
        return ImmutableMap.of();
      }
      ImmutableMap.Builder<String, Struct> attributes = ImmutableMap.builder();
      for (String attr : requestedAttributes) {
        switch (attr) {
          case "request.path":
          case "request.url_path":
            attributes.put(attr, encodeAttribute("/" + method.getFullMethodName()));
            break;
          case "request.host":
            attributes.put(attr, encodeAttribute(channel.authority()));
            break;
          case "request.method":
            attributes.put(attr, encodeAttribute("POST"));
            break;
          case "request.headers":
            attributes.put(attr, encodeHeaders(headers));
            break;
          case "request.referer":
            String referer = getHeaderValue(headers, "referer");
            if (referer != null) {
              attributes.put(attr, encodeAttribute(referer));
            }
            break;
          case "request.useragent":
            String ua = getHeaderValue(headers, "user-agent");
            if (ua != null) {
              attributes.put(attr, encodeAttribute(ua));
            }
            break;
          case "request.id":
            String id = getHeaderValue(headers, "x-request-id");
            if (id != null) {
              attributes.put(attr, encodeAttribute(id));
            }
            break;
          case "request.query":
            attributes.put(attr, encodeAttribute(""));
            break;
          default:
            // "Not set" attributes or unrecognized ones (already validated) are skipped.
            break;
        }
      }
      return attributes.buildOrThrow();
    }

    private static Struct encodeAttribute(String value) {
      return Struct.newBuilder()
          .putFields("", Value.newBuilder().setStringValue(value).build())
          .build();
    }

    private static Struct encodeHeaders(Metadata headers) {
      Struct.Builder builder = Struct.newBuilder();
      for (String key : headers.keys()) {
        String value = getHeaderValue(headers, key);
        if (value != null) {
          builder.putFields(key.toLowerCase(Locale.ROOT),
              Value.newBuilder().setStringValue(value).build());
        }
      }
      return builder.build();
    }

    @Nullable
    private static String getHeaderValue(Metadata headers, String headerName) {
      if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Metadata.Key<byte[]> key;
        try {
          key = Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER);
        } catch (IllegalArgumentException e) {
          return null;
        }
        Iterable<byte[]> values = headers.getAll(key);
        if (values == null) {
          return null;
        }
        java.util.List<String> encoded = new ArrayList<>();
        for (byte[] v : values) {
          encoded.add(BaseEncoding.base64().omitPadding().encode(v));
        }
        return com.google.common.base.Joiner.on(",").join(encoded);
      }
      Metadata.Key<String> key;
      try {
        key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
      } catch (IllegalArgumentException e) {
        return null;
      }
      Iterable<String> values = headers.getAll(key);
      return values == null ? null : com.google.common.base.Joiner.on(",").join(values);
    }

    /**
     * A local subclass to expose the protected constructor of DelayedClientCall.
     */
    private static class DataPlaneDelayedCall<ReqT, RespT> extends DelayedClientCall<ReqT, RespT> {
      DataPlaneDelayedCall(Executor executor, ScheduledExecutorService scheduler, @Nullable Deadline deadline) {
        super(executor, scheduler, deadline);
      }
    }

    /**
     * Handles the bidirectional stream with the External Processor.
     * Buffers the actual RPC start until the Ext Proc header response is received.
     */
    private static class DataPlaneClientCall 
        extends SimpleForwardingClientCall<InputStream, InputStream> {
      private enum EventType {
        REQUEST_HEADERS,
        REQUEST_BODY,
        RESPONSE_HEADERS,
        RESPONSE_BODY,
        RESPONSE_TRAILERS
      }

      private final ExternalProcessorGrpc.ExternalProcessorStub stub;
      private final ExternalProcessorFilterConfig config;
      private final ClientCall<InputStream, InputStream> rawCall;
      private final DataPlaneDelayedCall<InputStream, InputStream> delayedCall;
      private final ScheduledExecutorService scheduler;
      private final Object streamLock = new Object();
      private final Queue<EventType> expectedResponses = new ConcurrentLinkedQueue<>();
      private volatile ClientCallStreamObserver<ProcessingRequest> extProcClientCallRequestObserver;
      private final Queue<ProcessingRequest> pendingProcessingRequests = new ConcurrentLinkedQueue<>();
      private volatile DataPlaneListener wrappedListener;
      private final HeaderMutationFilter mutationFilter;
      private final HeaderMutator mutator = HeaderMutator.create();
      private final AtomicInteger pendingRequests = new AtomicInteger(0);
      private final ProcessingMode currentProcessingMode;
      private final MethodDescriptor<?, ?> method;
      private final Channel channel;

      private volatile Metadata requestHeaders;
      final AtomicBoolean activated = new AtomicBoolean(false);
      final AtomicBoolean extProcStreamFailed = new AtomicBoolean(false);
      final AtomicBoolean extProcStreamCompleted = new AtomicBoolean(false);
      final AtomicBoolean passThroughMode = new AtomicBoolean(false);
      final AtomicBoolean notifiedApp = new AtomicBoolean(false);
      final AtomicBoolean drainingExtProcStream = new AtomicBoolean(false);
      final AtomicBoolean halfClosed = new AtomicBoolean(false);
      final AtomicBoolean requestSideClosed = new AtomicBoolean(false);
      final AtomicBoolean isProcessingTrailers = new AtomicBoolean(false);

      protected DataPlaneClientCall(
          DataPlaneDelayedCall<InputStream, InputStream> delayedCall,
          ClientCall<InputStream, InputStream> rawCall,
          ExternalProcessorGrpc.ExternalProcessorStub stub,
          ExternalProcessorFilterConfig config,
          Optional<HeaderMutationRulesConfig> mutationRulesConfig,
          ScheduledExecutorService scheduler,
          MethodDescriptor<?, ?> method,
          Channel channel) {
        super(delayedCall);
        this.delayedCall = delayedCall;
        this.rawCall = rawCall;
        this.stub = stub;
        this.config = config;
        this.currentProcessingMode = config.getExternalProcessor().getProcessingMode();
        this.mutationFilter = new HeaderMutationFilter(mutationRulesConfig);
        this.scheduler = scheduler;
        this.method = method;
        this.channel = channel;
      }

      private void activateCall() {
        if (extProcStreamFailed.get() || !activated.compareAndSet(false, true)) {
          return;
        }
        Runnable toRun = delayedCall.setCall(rawCall);
        if (toRun != null) {
          toRun.run();
        }
        drainPendingRequests();
        onReadyNotify();
      }

      private boolean checkCompressionSupport(BodyResponse bodyResponse) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          BodyMutation mutation = 
              bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasStreamedResponse()
              && mutation.getStreamedResponse().getGrpcMessageCompressed()) {
            StatusRuntimeException ex = Status.UNAVAILABLE
                .withDescription("gRPC message compression not supported in ext_proc")
                .asRuntimeException();
            if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
              extProcClientCallRequestObserver.onError(ex);
            }
            activateCall();
            extProcStreamFailed.set(true);
            delayedCall.cancel("gRPC message compression not supported in ext_proc", ex);
            closeExtProcStream();
            return false;
          }
        }
        return true;
      }

      private void applyHeaderMutations(Metadata metadata,
          HeaderMutation mutation)
          throws HeaderMutationDisallowedException {
        if (metadata == null) {
          return;
        }
        ImmutableList.Builder<HeaderValueOption> headersToModify = ImmutableList.builder();
        for (io.envoyproxy.envoy.config.core.v3.HeaderValueOption protoOption : mutation.getSetHeadersList()) {
          io.envoyproxy.envoy.config.core.v3.HeaderValue protoHeader = protoOption.getHeader();
          HeaderValue headerValue;
          if (protoHeader.getKey().endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
            headerValue = HeaderValue.create(protoHeader.getKey(),
                ByteString.copyFrom(
                    BaseEncoding.base64().decode(protoHeader.getValue())));
          } else {
            headerValue = HeaderValue.create(protoHeader.getKey(), protoHeader.getValue());
          }
          headersToModify.add(HeaderValueOption.create(
              headerValue,
              HeaderValueOption.HeaderAppendAction.valueOf(protoOption.getAppendAction().name()),
              protoOption.getKeepEmptyValue()));
        }

        HeaderMutations mutations = HeaderMutations.create(
            headersToModify.build(),
            ImmutableList.copyOf(mutation.getRemoveHeadersList()));

        HeaderMutations filteredMutations = mutationFilter.filter(mutations);
        mutator.applyMutations(filteredMutations, metadata);
      }

      @Override
      public void start(Listener<InputStream> responseListener, Metadata headers) {
        this.requestHeaders = headers;
        this.wrappedListener = new DataPlaneListener(responseListener, rawCall, this);

        // DelayedClientCall.start will buffer the listener and headers until setCall is called.
        super.start(wrappedListener, headers);

        stub.process(new ClientResponseObserver<ProcessingRequest, ProcessingResponse>() {
          @Override
          public void beforeStart(ClientCallStreamObserver<ProcessingRequest> requestStream) {
            synchronized (streamLock) {
              extProcClientCallRequestObserver = requestStream;
              while (!pendingProcessingRequests.isEmpty()) {
                requestStream.onNext(pendingProcessingRequests.poll());
              }
            }
            requestStream.setOnReadyHandler(DataPlaneClientCall.this::onExtProcStreamReady);
          }

          @Override
          public void onNext(ProcessingResponse response) {
            try {
              if (response.hasImmediateResponse()) {
                if (config.getDisableImmediateResponse()) {
                  onError(Status.UNAVAILABLE
                      .withDescription("Immediate response is disabled but received from external processor")
                      .asRuntimeException());
                  return;
                }
                handleImmediateResponse(response.getImmediateResponse(), wrappedListener);
                return;
              }

              if (config.getObservabilityMode()) {
                return;
              }

              EventType expected = expectedResponses.peek();
              EventType received = null;
              if (response.hasRequestHeaders()) {
                received = EventType.REQUEST_HEADERS;
              } else if (response.hasRequestBody()) {
                received = EventType.REQUEST_BODY;
              } else if (response.hasResponseHeaders()) {
                received = EventType.RESPONSE_HEADERS;
              } else if (response.hasResponseBody()) {
                received = EventType.RESPONSE_BODY;
              } else if (response.hasResponseTrailers()) {
                received = EventType.RESPONSE_TRAILERS;
              }

              if (received != null) {
                if (expected == null || expected != received) {
                  onError(Status.UNAVAILABLE
                      .withDescription("Protocol error: received response out of order. Expected: " 
                          + expected + ", Received: " + received)
                      .asRuntimeException());
                  return;
                }
                expectedResponses.poll();
              }

              if (response.getRequestDrain()) {
                drainingExtProcStream.set(true);
                halfCloseExtProcStream();
                activateCall();
              }

              // 1. Client Headers
              if (response.hasRequestHeaders()) {
                if (response.getRequestHeaders().hasResponse()) {
                  if (response.getRequestHeaders().getResponse().getStatus()
                      == CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE) {
                    onError(Status.UNAVAILABLE
                        .withDescription("CONTINUE_AND_REPLACE is not supported")
                        .asRuntimeException());
                    return;
                  }
                  applyHeaderMutations(requestHeaders, response.getRequestHeaders().getResponse().getHeaderMutation());
                }
                activateCall();
              }
              // 2. Client Message (Request Body)
              else if (response.hasRequestBody()) {
                if (checkCompressionSupport(response.getRequestBody())) {
                  handleRequestBodyResponse(response.getRequestBody());
                }
              }
              // 3. Client Trailers
              else if (response.hasRequestTrailers()) {
                wrappedListener.proceedWithClose();
              }
              // 4. Server Headers
              else if (response.hasResponseHeaders()) {
                if (response.getResponseHeaders().hasResponse()) {
                  if (response.getResponseHeaders().getResponse().getStatus()
                      == CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE) {
                    onError(Status.UNAVAILABLE
                        .withDescription("CONTINUE_AND_REPLACE is not supported")
                        .asRuntimeException());
                    return;
                  }
                  Metadata target = wrappedListener.trailersOnly.get()
                      ? wrappedListener.savedTrailers : wrappedListener.savedHeaders;
                  applyHeaderMutations(target, response.getResponseHeaders().getResponse().getHeaderMutation());
                }
                if (wrappedListener.trailersOnly.get()) {
                  wrappedListener.proceedWithClose();
                } else {
                  wrappedListener.proceedWithHeaders();
                }
              }
              // 5. Server Message (Response Body)
              else if (response.hasResponseBody()) {
                if (checkCompressionSupport(response.getResponseBody())) {
                  handleResponseBodyResponse(response.getResponseBody(), wrappedListener);
                }
              }
              // 6. Response Trailers
              else if (response.hasResponseTrailers()) {
                if (response.getResponseTrailers().hasHeaderMutation()) {
                  applyHeaderMutations(
                      wrappedListener.savedTrailers,
                      response.getResponseTrailers().getHeaderMutation()
                  );
                }
              }

              checkEndOfStream(response);
            } catch (Throwable t) {
              onError(t);
            }
          }

          @Override
          public void onError(Throwable t) {
            if (extProcStreamCompleted.compareAndSet(false, true)) {
              synchronized (streamLock) {
                if (extProcClientCallRequestObserver != null) {
                  extProcClientCallRequestObserver.onError(t);
                  extProcClientCallRequestObserver = null;
                }
              }
              if (config.getFailureModeAllow()) {
                handleFailOpen(wrappedListener);
              } else {
                extProcStreamFailed.set(true);
                String message = "External processor stream failed";
                delayedCall.cancel(message, t);
                wrappedListener.proceedWithClose();
              }
            }
          }

          @Override
          public void onCompleted() {
            if (extProcStreamCompleted.compareAndSet(false, true)) {
              drainingExtProcStream.set(false);
              handleFailOpen(wrappedListener);
            }
          }
        });

        boolean sendRequestHeaders = currentProcessingMode.getRequestHeaderMode() == ProcessingMode.HeaderSendMode.SEND
            || currentProcessingMode.getRequestHeaderMode() == ProcessingMode.HeaderSendMode.DEFAULT;

        if (sendRequestHeaders) {
          sendToExtProc(ProcessingRequest.newBuilder()
              .setRequestHeaders(HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(headers, config.getForwardRulesConfig()))
                  .setEndOfStream(false)
                  .build())
              .putAllAttributes(collectAttributes(config.getRequestAttributes(), method, channel, headers))
              .setProtocolConfig(ProtocolConfiguration.newBuilder()
                  .setRequestBodyMode(currentProcessingMode.getRequestBodyMode())
                  .setResponseBodyMode(currentProcessingMode.getResponseBodyMode())
                  .build())
              .build());
        }

        if (config.getObservabilityMode() || !sendRequestHeaders) {
          activateCall();
        }
      }

      private void sendToExtProc(ProcessingRequest request) {
        synchronized (streamLock) {
          if (extProcStreamCompleted.get()) {
            return;
          }
          
          if (request.hasRequestHeaders()) {
            expectedResponses.add(EventType.REQUEST_HEADERS);
          } else if (request.hasRequestBody()) {
            expectedResponses.add(EventType.REQUEST_BODY);
          } else if (request.hasResponseHeaders()) {
            expectedResponses.add(EventType.RESPONSE_HEADERS);
          } else if (request.hasResponseBody()) {
            expectedResponses.add(EventType.RESPONSE_BODY);
          } else if (request.hasResponseTrailers()) {
            expectedResponses.add(EventType.RESPONSE_TRAILERS);
          }

          if (extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onNext(request);
          } else {
            pendingProcessingRequests.add(request);
          }
        }
      }

      private void onExtProcStreamReady() {
        drainPendingRequests();
        onReadyNotify();
      }

      private void drainPendingRequests() {
        int toRequest = pendingRequests.getAndSet(0);
        if (toRequest > 0) {
          super.request(toRequest);
        }
      }

      private void closeExtProcStream() {
        synchronized (streamLock) {
          if (extProcStreamCompleted.compareAndSet(false, true)) {
            if (extProcClientCallRequestObserver != null) {
              extProcClientCallRequestObserver.onCompleted();
            }
          }
        }
      }

      private void halfCloseExtProcStream() {
        synchronized (streamLock) {
          if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onCompleted();
          }
        }
      }

      private void onReadyNotify() {
        wrappedListener.onReadyNotify();
      }

      private boolean isSidecarReady() {
        if (extProcStreamCompleted.get()) {
          return true;
        }
        if (drainingExtProcStream.get()) {
          return false;
        }
        synchronized (streamLock) {
          ClientCallStreamObserver<ProcessingRequest> observer = extProcClientCallRequestObserver;
          return observer != null && observer.isReady();
        }
      }

      @Override
      public boolean isReady() {
        if (passThroughMode.get()) {
          return super.isReady();
        }
        if (extProcStreamCompleted.get()) {
          return super.isReady();
        }
        if (!activated.get() && !config.getObservabilityMode()) {
          return false;
        }
        boolean sidecarReady = isSidecarReady();
        if (config.getObservabilityMode()) {
          return super.isReady() && sidecarReady;
        }
        return sidecarReady;
      }

      @Override
      public void request(int numMessages) {
        if (passThroughMode.get() || extProcStreamCompleted.get()) {
          super.request(numMessages);
          return;
        }
        if (!isSidecarReady()) {
          pendingRequests.addAndGet(numMessages);
          return;
        }
        super.request(numMessages);
      }

      @Override
      public void sendMessage(InputStream message) {
        if (requestSideClosed.get()) {
          // External processor already closed the request stream. Discard further messages.
          return;
        }

        if (passThroughMode.get() || extProcStreamCompleted.get()) {
          super.sendMessage(message);
          return;
        }

        if (currentProcessingMode.getRequestBodyMode() == ProcessingMode.BodySendMode.NONE) {
          super.sendMessage(message);
          return;
        }

        // Mode is GRPC
        try {
          byte[] bodyBytes = ByteStreams.toByteArray(message);
          sendToExtProc(ProcessingRequest.newBuilder()
              .setRequestBody(HttpBody.newBuilder()
                  .setBody(ByteString.copyFrom(bodyBytes))
                  .setEndOfStream(false)
                  .build())
              .build());

          if (config.getObservabilityMode()) {
            super.sendMessage(new ByteArrayInputStream(bodyBytes));
          }
        } catch (IOException e) {
          rawCall.cancel("Failed to serialize message for External Processor", e);
        }
      }

      @Override
      public void halfClose() {
        halfClosed.set(true);
        if (passThroughMode.get() || extProcStreamCompleted.get()) {
          if (requestSideClosed.compareAndSet(false, true)) {
            super.halfClose();
          }
          return;
        }

        if (currentProcessingMode.getRequestBodyMode() == ProcessingMode.BodySendMode.NONE) {
          if (requestSideClosed.compareAndSet(false, true)) {
            super.halfClose();
          }
          return;
        }

        // Mode is GRPC
        sendToExtProc(ProcessingRequest.newBuilder()
            .setRequestBody(HttpBody.newBuilder()
                .setEndOfStreamWithoutMessage(true)
                .build())
            .build());
        
        // Defer super.halfClose() until ext-proc response signals end_of_stream.
      }

      @Override
      public void cancel(@Nullable String message, @Nullable Throwable cause) {
        synchronized (streamLock) {
          if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onError(Status.CANCELLED.withDescription(message).withCause(cause).asRuntimeException());
          }
        }
        super.cancel(message, cause);
      }

      private void handleRequestBodyResponse(BodyResponse bodyResponse) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasStreamedResponse()) {
            StreamedBodyResponse streamed = mutation.getStreamedResponse();
            if (!streamed.getBody().isEmpty()) {
              super.sendMessage(streamed.getBody().newInput());
            }
          }
        }
        // If the application already half-closed, and we just received a response from
        // the sidecar for the last part of the request body, we can now half-close the data plane.
        if (halfClosed.get()) {
          if (requestSideClosed.compareAndSet(false, true)) {
            super.halfClose();
          }
        }
      }

      private void handleResponseBodyResponse(BodyResponse bodyResponse, DataPlaneListener listener) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasStreamedResponse()) {
            StreamedBodyResponse streamed = mutation.getStreamedResponse();
            if (!streamed.getBody().isEmpty()) {
              listener.onExternalBody(streamed.getBody());
            }
            if (streamed.getEndOfStream() || streamed.getEndOfStreamWithoutMessage()) {
              listener.proceedWithClose();
            }
          }
        }
      }

      private void handleImmediateResponse(ImmediateResponse immediate, DataPlaneListener listener)
          throws HeaderMutationDisallowedException {
        Status status = Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
        if (!immediate.getDetails().isEmpty()) {
          status = status.withDescription(immediate.getDetails());
        }

        Metadata trailers = new Metadata();
        if (immediate.hasHeaders()) {
          applyHeaderMutations(trailers, immediate.getHeaders());
        }

        // ImmediateResponse should take precedence over any other closure if it arrives before the app is notified.
        listener.savedStatus = status;
        listener.savedTrailers = trailers;

        if (isProcessingTrailers.get()) {
          // If sent in response to a server trailers event, sets the status and optionally
          // headers to be included in the trailers.
          listener.unblockAfterStreamComplete();
        } else {
          // If sent in response to any other event, it will cause the data plane RPC to
          // immediately fail with the specified status as if it were an out-of-band
          // cancellation.
          rawCall.cancel(status.getDescription(), null);
          listener.unblockAfterStreamComplete();
        }
        closeExtProcStream();
      }

      private void handleFailOpen(DataPlaneListener listener) {
        activateCall();
        listener.unblockAfterStreamComplete();
        closeExtProcStream();
      }

      private void checkEndOfStream(ProcessingResponse response) {
        boolean terminal = false;
        if (response.hasResponseTrailers()) {
          terminal = true;
        } else if (response.hasResponseHeaders() && wrappedListener.trailersOnly.get()) {
          terminal = true;
        }

        if (terminal) {
          wrappedListener.unblockAfterStreamComplete();
          closeExtProcStream();
        }
      }
    }

    private static class DataPlaneListener extends ClientCall.Listener<InputStream> {
      private final ClientCall.Listener<InputStream> delegate;
      private final ClientCall<?, ?> rawCall;
      private final DataPlaneClientCall dataPlaneClientCall;
      private final Queue<InputStream> savedMessages = new ConcurrentLinkedQueue<>();
      private volatile Metadata savedHeaders;
      private volatile Metadata savedTrailers;
      private volatile Status savedStatus;
      private final AtomicBoolean terminationTriggered = new AtomicBoolean(false);
      private final AtomicBoolean responseHeadersSent = new AtomicBoolean(false);
      private final AtomicBoolean trailersOnly = new AtomicBoolean(false);

      protected DataPlaneListener(ClientCall.Listener<InputStream> delegate, ClientCall<?, ?> rawCall,
                                DataPlaneClientCall dataPlaneClientCall) {
        this.delegate = checkNotNull(delegate, "delegate");
        this.rawCall = rawCall;
        this.dataPlaneClientCall = dataPlaneClientCall;
      }

      @Override
      public void onReady() {
        dataPlaneClientCall.drainPendingRequests();
        onReadyNotify();
      }

      void onReadyNotify() {
        delegate.onReady();
      }

      @Override
      public void onHeaders(Metadata headers) {
        responseHeadersSent.set(true);
        boolean sendResponseHeaders = dataPlaneClientCall.currentProcessingMode.getResponseHeaderMode() 
            == ProcessingMode.HeaderSendMode.SEND
            || dataPlaneClientCall.currentProcessingMode.getResponseHeaderMode() == ProcessingMode.HeaderSendMode.DEFAULT;

        if (dataPlaneClientCall.passThroughMode.get() 
            || dataPlaneClientCall.extProcStreamCompleted.get() 
            || !sendResponseHeaders) {
          delegate.onHeaders(headers);
          return;
        }

        this.savedHeaders = headers;
        dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseHeaders(HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers, dataPlaneClientCall.config.getForwardRulesConfig()))
                .build())
            .build());

        if (dataPlaneClientCall.config.getObservabilityMode()) {
          proceedWithHeaders();
        }
      }

      void proceedWithHeaders() {
        if (savedHeaders != null) {
          delegate.onHeaders(savedHeaders);
          savedHeaders = null;
          InputStream msg;
          while ((msg = savedMessages.poll()) != null) {
            onMessage(msg);
          }
          onReadyNotify();
          if (savedStatus != null) {
             triggerCloseHandshake();
          }
        }
      }

      @Override
      public void onMessage(InputStream message) {
        if (dataPlaneClientCall.passThroughMode.get()) {
          delegate.onMessage(message);
          return;
        }

        if (savedHeaders != null) {
          savedMessages.add(message);
          return;
        }

        if (dataPlaneClientCall.extProcStreamCompleted.get()
            || dataPlaneClientCall.currentProcessingMode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
          delegate.onMessage(message);
          return;
        }

        try {
          byte[] bodyBytes = ByteStreams.toByteArray(message);
          sendResponseBodyToExtProc(bodyBytes, false);

          if (dataPlaneClientCall.config.getObservabilityMode()) {
            delegate.onMessage(new ByteArrayInputStream(bodyBytes));
          }
        } catch (IOException e) {
           rawCall.cancel("Failed to read server response", e);
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        if (dataPlaneClientCall.extProcStreamFailed.get()) {
          if (dataPlaneClientCall.notifiedApp.compareAndSet(false, true)) {
            delegate.onClose(Status.UNAVAILABLE.withDescription("External processor stream failed")
                .withCause(status.getCause()), new Metadata());
          }
          return;
        }
        if (dataPlaneClientCall.passThroughMode.get()) {
          if (dataPlaneClientCall.notifiedApp.compareAndSet(false, true)) {
            delegate.onClose(status, trailers);
          }
          return;
        }

        this.savedStatus = status;
        this.savedTrailers = trailers;

        if (dataPlaneClientCall.extProcStreamCompleted.get()) {
           proceedWithClose();
           return;
        }

        if (savedHeaders != null) {
           return;
        }

        if (!responseHeadersSent.get()) {
           trailersOnly.set(true);
        }

        triggerCloseHandshake();

        if (dataPlaneClientCall.config.getObservabilityMode()) {
          proceedWithClose();
          @SuppressWarnings("unused")
          ScheduledFuture<?> unused = dataPlaneClientCall.scheduler.schedule(
              dataPlaneClientCall::closeExtProcStream,
              dataPlaneClientCall.config.getDeferredCloseTimeoutNanos(),
              TimeUnit.NANOSECONDS);
        }
      }

      private void triggerCloseHandshake() {
        if (dataPlaneClientCall.extProcStreamCompleted.get() || !terminationTriggered.compareAndSet(false, true)) {
          return;
        }

        if (trailersOnly.get()) {
          dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseHeaders(HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(savedTrailers, dataPlaneClientCall.config.getForwardRulesConfig()))
                  .setEndOfStream(true)
                  .build())
              .build());
          return;
        }

        boolean sendResponseTrailers = dataPlaneClientCall.currentProcessingMode.getResponseTrailerMode() == ProcessingMode.HeaderSendMode.SEND;

        if (sendResponseTrailers) {
          dataPlaneClientCall.isProcessingTrailers.set(true);
          dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseTrailers(HttpTrailers.newBuilder()
                  .setTrailers(toHeaderMap(savedTrailers, dataPlaneClientCall.config.getForwardRulesConfig()))
                  .build())
              .build());
        } else {
          // Send EOS signal via empty body
          dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseBody(HttpBody.newBuilder()
                  .setEndOfStreamWithoutMessage(true)
                  .build())
              .build());
          
          if (dataPlaneClientCall.config.getObservabilityMode()) {
            // In observability mode we don't wait for handshake response
            proceedWithClose();
          }
        }
      }

      private void sendResponseBodyToExtProc(@Nullable byte[] bodyBytes, boolean endOfStream) {
        if (dataPlaneClientCall.extProcStreamCompleted.get()
            || dataPlaneClientCall.currentProcessingMode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
          return;
        }

        HttpBody.Builder bodyBuilder =
            HttpBody.newBuilder();
        if (bodyBytes != null) {
          bodyBuilder.setBody(ByteString.copyFrom(bodyBytes));
        }
        bodyBuilder.setEndOfStream(endOfStream);

        dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseBody(bodyBuilder.build())
            .build());
      }

      void proceedWithClose() {
        if (savedStatus != null) {
          if (dataPlaneClientCall.notifiedApp.compareAndSet(false, true)) {
            delegate.onClose(savedStatus, savedTrailers);
          }
          savedStatus = null;
          savedTrailers = null;
        }
      }

      void onExternalBody(ByteString body) {
        delegate.onMessage(body.newInput());
      }

      void unblockAfterStreamComplete() {
        proceedWithHeaders();
        dataPlaneClientCall.passThroughMode.set(true);
        proceedWithClose();
      }
    }
  }
}
