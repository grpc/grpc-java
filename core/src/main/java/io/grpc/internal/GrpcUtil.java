/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.InternalMetadata;
import io.grpc.InternalMetadata.TrustedAsciiMarshaller;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ProxiedSocketAddress;
import io.grpc.ProxyDetector;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.internal.StreamListener.MessageProducer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Common utilities for GRPC.
 */
public final class GrpcUtil {

  private static final Logger log = Logger.getLogger(GrpcUtil.class.getName());

  private static final Set<Status.Code> INAPPROPRIATE_CONTROL_PLANE_STATUS
      = Collections.unmodifiableSet(EnumSet.of(
          Status.Code.OK,
          Status.Code.INVALID_ARGUMENT,
          Status.Code.NOT_FOUND,
          Status.Code.ALREADY_EXISTS,
          Status.Code.FAILED_PRECONDITION,
          Status.Code.ABORTED,
          Status.Code.OUT_OF_RANGE,
          Status.Code.DATA_LOSS));

  public static final Charset US_ASCII = Charset.forName("US-ASCII");

  /**
   * {@link io.grpc.Metadata.Key} for the timeout header.
   */
  public static final Metadata.Key<Long> TIMEOUT_KEY =
          Metadata.Key.of(GrpcUtil.TIMEOUT, new TimeoutMarshaller());

  /**
   * {@link io.grpc.Metadata.Key} for the message encoding header.
   */
  public static final Metadata.Key<String> MESSAGE_ENCODING_KEY =
          Metadata.Key.of(GrpcUtil.MESSAGE_ENCODING, Metadata.ASCII_STRING_MARSHALLER);

  /**
   * {@link io.grpc.Metadata.Key} for the accepted message encodings header.
   */
  public static final Metadata.Key<byte[]> MESSAGE_ACCEPT_ENCODING_KEY =
      InternalMetadata.keyOf(GrpcUtil.MESSAGE_ACCEPT_ENCODING, new AcceptEncodingMarshaller());

  /**
   * {@link io.grpc.Metadata.Key} for the stream's content encoding header.
   */
  public static final Metadata.Key<String> CONTENT_ENCODING_KEY =
      Metadata.Key.of(GrpcUtil.CONTENT_ENCODING, Metadata.ASCII_STRING_MARSHALLER);

  /**
   * {@link io.grpc.Metadata.Key} for the stream's accepted content encoding header.
   */
  public static final Metadata.Key<byte[]> CONTENT_ACCEPT_ENCODING_KEY =
      InternalMetadata.keyOf(GrpcUtil.CONTENT_ACCEPT_ENCODING, new AcceptEncodingMarshaller());

  static final Metadata.Key<String> CONTENT_LENGTH_KEY =
      Metadata.Key.of("content-length", Metadata.ASCII_STRING_MARSHALLER);

  private static final class AcceptEncodingMarshaller implements TrustedAsciiMarshaller<byte[]> {
    @Override
    public byte[] toAsciiString(byte[] value) {
      return value;
    }

    @Override
    public byte[] parseAsciiString(byte[] serialized) {
      return serialized;
    }
  }

  /**
   * {@link io.grpc.Metadata.Key} for the Content-Type request/response header.
   */
  public static final Metadata.Key<String> CONTENT_TYPE_KEY =
          Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * {@link io.grpc.Metadata.Key} for the Transfer encoding.
   */
  public static final Metadata.Key<String> TE_HEADER =
      Metadata.Key.of("te", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * {@link io.grpc.Metadata.Key} for the Content-Type request/response header.
   */
  public static final Metadata.Key<String> USER_AGENT_KEY =
          Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * The default port for plain-text connections.
   */
  public static final int DEFAULT_PORT_PLAINTEXT = 80;

  /**
   * The default port for SSL connections.
   */
  public static final int DEFAULT_PORT_SSL = 443;

  /**
   * Content-Type used for GRPC-over-HTTP/2.
   */
  public static final String CONTENT_TYPE_GRPC = "application/grpc";

  /**
   * The HTTP method used for GRPC requests.
   */
  public static final String HTTP_METHOD = "POST";

  /**
   * The TE (transport encoding) header for requests over HTTP/2.
   */
  public static final String TE_TRAILERS = "trailers";

  /**
   * The Timeout header name.
   */
  public static final String TIMEOUT = "grpc-timeout";

  /**
   * The message encoding (i.e. compression) that can be used in the stream.
   */
  public static final String MESSAGE_ENCODING = "grpc-encoding";

  /**
   * The accepted message encodings (i.e. compression) that can be used in the stream.
   */
  public static final String MESSAGE_ACCEPT_ENCODING = "grpc-accept-encoding";

  /**
   * The content-encoding used to compress the full gRPC stream.
   */
  public static final String CONTENT_ENCODING = "content-encoding";

  /**
   * The accepted content-encodings that can be used to compress the full gRPC stream.
   */
  public static final String CONTENT_ACCEPT_ENCODING = "accept-encoding";

  /**
   * The default maximum uncompressed size (in bytes) for inbound messages. Defaults to 4 MiB.
   */
  public static final int DEFAULT_MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

  /**
   * The default maximum size (in bytes) for inbound header/trailer.
   */
  // Update documentation in public-facing Builders when changing this value.
  public static final int DEFAULT_MAX_HEADER_LIST_SIZE = 8192;

  public static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();

  public static final String IMPLEMENTATION_VERSION = "1.70.0-SNAPSHOT"; // CURRENT_GRPC_VERSION

  /**
   * The default timeout in nanos for a keepalive ping request.
   */
  public static final long DEFAULT_KEEPALIVE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20L);

  /**
   * The magic keepalive time value that disables client keepalive.
   */
  public static final long KEEPALIVE_TIME_NANOS_DISABLED = Long.MAX_VALUE;

  /**
   * The default delay in nanos for server keepalive.
   */
  public static final long DEFAULT_SERVER_KEEPALIVE_TIME_NANOS = TimeUnit.HOURS.toNanos(2L);

  /**
   * The default timeout in nanos for a server keepalive ping request.
   */
  public static final long DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20L);

  /**
   * The magic keepalive time value that disables keepalive.
   */
  public static final long SERVER_KEEPALIVE_TIME_NANOS_DISABLED = Long.MAX_VALUE;

  /**
   * The default proxy detector.
   */
  public static final ProxyDetector DEFAULT_PROXY_DETECTOR = new ProxyDetectorImpl();

  /**
   * A proxy detector that always claims no proxy is needed.
   */
  public static final ProxyDetector NOOP_PROXY_DETECTOR = new ProxyDetector() {
    @Nullable
    @Override
    public ProxiedSocketAddress proxyFor(SocketAddress targetServerAddress) {
      return null;
    }
  };

  /**
   * The very default load-balancing policy.
   */
  public static final String DEFAULT_LB_POLICY = "pick_first";

  /**
   * RPCs created on the Channel returned by {@link io.grpc.LoadBalancer.Subchannel#asChannel}
   * will have this option with value {@code true}.  They will be treated differently from
   * the ones created by application.
   */
  public static final CallOptions.Key<Boolean> CALL_OPTIONS_RPC_OWNED_BY_BALANCER =
      CallOptions.Key.create("io.grpc.internal.CALL_OPTIONS_RPC_OWNED_BY_BALANCER");

  private static final ClientStreamTracer NOOP_TRACER = new ClientStreamTracer() {};

  /**
   * Returns true if an RPC with the given properties should be counted when calculating the
   * in-use state of a transport.
   */
  public static boolean shouldBeCountedForInUse(CallOptions callOptions) {
    return !Boolean.TRUE.equals(callOptions.getOption(CALL_OPTIONS_RPC_OWNED_BY_BALANCER));
  }

  /**
   * Maps HTTP error response status codes to transport codes, as defined in <a
   * href="https://github.com/grpc/grpc/blob/master/doc/http-grpc-status-mapping.md">
   * http-grpc-status-mapping.md</a>. Never returns a status for which {@code status.isOk()} is
   * {@code true}.
   */
  public static Status httpStatusToGrpcStatus(int httpStatusCode) {
    return httpStatusToGrpcCode(httpStatusCode).toStatus()
        .withDescription("HTTP status code " + httpStatusCode);
  }

  private static Status.Code httpStatusToGrpcCode(int httpStatusCode) {
    if (httpStatusCode >= 100 && httpStatusCode < 200) {
      // 1xx. These headers should have been ignored.
      return Status.Code.INTERNAL;
    }
    switch (httpStatusCode) {
      case HttpURLConnection.HTTP_BAD_REQUEST:  // 400
      case 431: // Request Header Fields Too Large
        // TODO(carl-mastrangelo): this should be added to the http-grpc-status-mapping.md doc.
        return Status.Code.INTERNAL;
      case HttpURLConnection.HTTP_UNAUTHORIZED:  // 401
        return Status.Code.UNAUTHENTICATED;
      case HttpURLConnection.HTTP_FORBIDDEN:  // 403
        return Status.Code.PERMISSION_DENIED;
      case HttpURLConnection.HTTP_NOT_FOUND:  // 404
        return Status.Code.UNIMPLEMENTED;
      case 429:  // Too Many Requests
      case HttpURLConnection.HTTP_BAD_GATEWAY:  // 502
      case HttpURLConnection.HTTP_UNAVAILABLE:  // 503
      case HttpURLConnection.HTTP_GATEWAY_TIMEOUT:  // 504
        return Status.Code.UNAVAILABLE;
      default:
        return Status.Code.UNKNOWN;
    }
  }

  /**
   * All error codes identified by the HTTP/2 spec. Used in GOAWAY and RST_STREAM frames.
   */
  public enum Http2Error {
    /**
     * Servers implementing a graceful shutdown of the connection will send {@code GOAWAY} with
     * {@code NO_ERROR}. In this case it is important to indicate to the application that the
     * request should be retried (i.e. {@link Status#UNAVAILABLE}).
     */
    NO_ERROR(0x0, Status.UNAVAILABLE),
    PROTOCOL_ERROR(0x1, Status.INTERNAL),
    INTERNAL_ERROR(0x2, Status.INTERNAL),
    FLOW_CONTROL_ERROR(0x3, Status.INTERNAL),
    SETTINGS_TIMEOUT(0x4, Status.INTERNAL),
    STREAM_CLOSED(0x5, Status.INTERNAL),
    FRAME_SIZE_ERROR(0x6, Status.INTERNAL),
    REFUSED_STREAM(0x7, Status.UNAVAILABLE),
    CANCEL(0x8, Status.CANCELLED),
    COMPRESSION_ERROR(0x9, Status.INTERNAL),
    CONNECT_ERROR(0xA, Status.INTERNAL),
    ENHANCE_YOUR_CALM(0xB, Status.RESOURCE_EXHAUSTED.withDescription("Bandwidth exhausted")),
    INADEQUATE_SECURITY(0xC, Status.PERMISSION_DENIED.withDescription("Permission denied as "
        + "protocol is not secure enough to call")),
    HTTP_1_1_REQUIRED(0xD, Status.UNKNOWN);

    // Populate a mapping of code to enum value for quick look-up.
    private static final Http2Error[] codeMap = buildHttp2CodeMap();

    private static Http2Error[] buildHttp2CodeMap() {
      Http2Error[] errors = Http2Error.values();
      int size = (int) errors[errors.length - 1].code() + 1;
      Http2Error[] http2CodeMap = new Http2Error[size];
      for (Http2Error error : errors) {
        int index = (int) error.code();
        http2CodeMap[index] = error;
      }
      return http2CodeMap;
    }

    private final int code;
    // Status is not guaranteed to be deeply immutable. Don't care though, since that's only true
    // when there are exceptions in the Status, which is not true here.
    @SuppressWarnings("ImmutableEnumChecker")
    private final Status status;

    Http2Error(int code, Status status) {
      this.code = code;
      String description = "HTTP/2 error code: " + this.name();
      if (status.getDescription() != null) {
        description += " (" + status.getDescription() + ")";
      }
      this.status = status.withDescription(description);
    }

    /**
     * Gets the code for this error used on the wire.
     */
    public long code() {
      return code;
    }

    /**
     * Gets the {@link Status} associated with this HTTP/2 code.
     */
    public Status status() {
      return status;
    }

    /**
     * Looks up the HTTP/2 error code enum value for the specified code.
     *
     * @param code an HTTP/2 error code value.
     * @return the HTTP/2 error code enum or {@code null} if not found.
     */
    public static Http2Error forCode(long code) {
      if (code >= codeMap.length || code < 0) {
        return null;
      }
      return codeMap[(int) code];
    }

    /**
     * Looks up the {@link Status} from the given HTTP/2 error code. This is preferred over {@code
     * forCode(code).status()}, to more easily conform to HTTP/2:
     *
     * <blockquote>Unknown or unsupported error codes MUST NOT trigger any special behavior.
     * These MAY be treated by an implementation as being equivalent to INTERNAL_ERROR.</blockquote>
     *
     * @param code the HTTP/2 error code.
     * @return a {@link Status} representing the given error.
     */
    public static Status statusForCode(long code) {
      Http2Error error = forCode(code);
      if (error == null) {
        // This "forgets" the message of INTERNAL_ERROR while keeping the same status code.
        Status.Code statusCode = INTERNAL_ERROR.status().getCode();
        return Status.fromCodeValue(statusCode.value())
            .withDescription("Unrecognized HTTP/2 error code: " + code);
      }

      return error.status();
    }
  }

  /**
   * Indicates whether or not the given value is a valid gRPC content-type.
   */
  public static boolean isGrpcContentType(String contentType) {
    if (contentType == null) {
      return false;
    }

    if (CONTENT_TYPE_GRPC.length() > contentType.length()) {
      return false;
    }

    contentType = contentType.toLowerCase(Locale.US);
    if (!contentType.startsWith(CONTENT_TYPE_GRPC)) {
      // Not a gRPC content-type.
      return false;
    }

    if (contentType.length() == CONTENT_TYPE_GRPC.length()) {
      // The strings match exactly.
      return true;
    }

    // The contentType matches, but is longer than the expected string.
    // We need to support variations on the content-type (e.g. +proto, +json) as defined by the
    // gRPC wire spec.
    char nextChar = contentType.charAt(CONTENT_TYPE_GRPC.length());
    return nextChar == '+' || nextChar == ';';
  }

  /**
   * Gets the User-Agent string for the gRPC transport.
   */
  public static String getGrpcUserAgent(
      String transportName, @Nullable String applicationUserAgent) {
    StringBuilder builder = new StringBuilder();
    if (applicationUserAgent != null) {
      builder.append(applicationUserAgent);
      builder.append(' ');
    }
    builder.append("grpc-java-");
    builder.append(transportName);
    builder.append('/');
    builder.append(IMPLEMENTATION_VERSION);
    return builder.toString();
  }

  @Immutable
  public static final class GrpcBuildVersion {
    private final String userAgent;
    private final String implementationVersion;

    private GrpcBuildVersion(String userAgent, String implementationVersion) {
      this.userAgent = Preconditions.checkNotNull(userAgent, "userAgentName");
      this.implementationVersion =
          Preconditions.checkNotNull(implementationVersion, "implementationVersion");
    }

    public String getUserAgent() {
      return userAgent;
    }

    public String getImplementationVersion() {
      return implementationVersion;
    }

    @Override
    public String toString() {
      return userAgent + " " + implementationVersion;
    }
  }

  /**
   * Returns the build version of gRPC.
   */
  public static GrpcBuildVersion getGrpcBuildVersion() {
    return new GrpcBuildVersion("gRPC Java", IMPLEMENTATION_VERSION);
  }

  /**
   * Parse an authority into a URI for retrieving the host and port.
   */
  public static URI authorityToUri(String authority) {
    Preconditions.checkNotNull(authority, "authority");
    URI uri;
    try {
      uri = new URI(null, authority, null, null, null);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid authority: " + authority, ex);
    }
    return uri;
  }

  /**
   * Verify {@code authority} is valid for use with gRPC. The syntax must be valid and it must not
   * include userinfo.
   *
   * @return the {@code authority} provided
   */
  public static String checkAuthority(String authority) {
    URI uri = authorityToUri(authority);
    // Verify that the user Info is not provided.
    checkArgument(uri.getAuthority().indexOf('@') == -1,
        "Userinfo must not be present on authority: '%s'", authority);
    return authority;
  }

  /**
   * Combine a host and port into an authority string.
   */
  // There is a copy of this method in io.grpc.Grpc
  public static String authorityFromHostAndPort(String host, int port) {
    try {
      return new URI(null, null, host, port, null, null, null).getAuthority();
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid host or port: " + host + " " + port, ex);
    }
  }

  /**
   * Shared executor for channels.
   */
  public static final Resource<Executor> SHARED_CHANNEL_EXECUTOR =
      new Resource<Executor>() {
        private static final String NAME = "grpc-default-executor";
        @Override
        public Executor create() {
          return Executors.newCachedThreadPool(getThreadFactory(NAME + "-%d", true));
        }

        @Override
        public void close(Executor instance) {
          ((ExecutorService) instance).shutdown();
        }

        @Override
        public String toString() {
          return NAME;
        }
      };

  /**
   * Shared single-threaded executor for managing channel timers.
   */
  public static final Resource<ScheduledExecutorService> TIMER_SERVICE =
      new Resource<ScheduledExecutorService>() {
        @Override
        public ScheduledExecutorService create() {
          // We don't use newSingleThreadScheduledExecutor because it doesn't return a
          // ScheduledThreadPoolExecutor.
          ScheduledExecutorService service = Executors.newScheduledThreadPool(
              1,
              getThreadFactory("grpc-timer-%d", true));

          // If there are long timeouts that are cancelled, they will not actually be removed from
          // the executors queue.  This forces immediate removal upon cancellation to avoid a
          // memory leak.  Reflection is used because we cannot use methods added in Java 1.7.  If
          // the method does not exist, we give up.  Note that the method is not present in 1.6, but
          // _is_ present in the android standard library.
          try {
            Method method = service.getClass().getMethod("setRemoveOnCancelPolicy", boolean.class);
            method.invoke(service, true);
          } catch (NoSuchMethodException e) {
            // no op
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }

          return Executors.unconfigurableScheduledExecutorService(service);
        }

        @Override
        public void close(ScheduledExecutorService instance) {
          instance.shutdown();
        }
      };


  /**
   * Get a {@link ThreadFactory} suitable for use in the current environment.
   * @param nameFormat to apply to threads created by the factory.
   * @param daemon {@code true} if the threads the factory creates are daemon threads, {@code false}
   *     otherwise.
   * @return a {@link ThreadFactory}.
   */
  public static ThreadFactory getThreadFactory(String nameFormat, boolean daemon) {
    return new ThreadFactoryBuilder()
        .setDaemon(daemon)
        .setNameFormat(nameFormat)
        .build();
  }

  /**
   * The factory of default Stopwatches.
   */
  public static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
      @Override
      public Stopwatch get() {
        return Stopwatch.createUnstarted();
      }
    };

  /**
   * Marshals a nanoseconds representation of the timeout to and from a string representation,
   * consisting of an ASCII decimal representation of a number with at most 8 digits, followed by a
   * unit. Available units:
   * n = nanoseconds
   * u = microseconds
   * m = milliseconds
   * S = seconds
   * M = minutes
   * H = hours
   *
   * <p>The representation is greedy with respect to precision. That is, 2 seconds will be
   * represented as `2000000u`.</p>
   *
   * <p>See <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests">the
   * request header definition</a></p>
   */
  @VisibleForTesting
  static class TimeoutMarshaller implements Metadata.AsciiMarshaller<Long> {

    @Override
    public String toAsciiString(Long timeoutNanos) {
      long cutoff = 100000000;
      TimeUnit unit = TimeUnit.NANOSECONDS;
      if (timeoutNanos < 0) {
        throw new IllegalArgumentException("Timeout too small");
      } else if (timeoutNanos < cutoff) {
        return timeoutNanos + "n";
      } else if (timeoutNanos < cutoff * 1000L) {
        return unit.toMicros(timeoutNanos) + "u";
      } else if (timeoutNanos < cutoff * 1000L * 1000L) {
        return unit.toMillis(timeoutNanos) + "m";
      } else if (timeoutNanos < cutoff * 1000L * 1000L * 1000L) {
        return unit.toSeconds(timeoutNanos) + "S";
      } else if (timeoutNanos < cutoff * 1000L * 1000L * 1000L * 60L) {
        return unit.toMinutes(timeoutNanos) + "M";
      } else {
        return unit.toHours(timeoutNanos) + "H";
      }
    }

    @Override
    public Long parseAsciiString(String serialized) {
      checkArgument(serialized.length() > 0, "empty timeout");
      checkArgument(serialized.length() <= 9, "bad timeout format");
      long value = Long.parseLong(serialized.substring(0, serialized.length() - 1));
      char unit = serialized.charAt(serialized.length() - 1);
      switch (unit) {
        case 'n':
          return value;
        case 'u':
          return TimeUnit.MICROSECONDS.toNanos(value);
        case 'm':
          return TimeUnit.MILLISECONDS.toNanos(value);
        case 'S':
          return TimeUnit.SECONDS.toNanos(value);
        case 'M':
          return TimeUnit.MINUTES.toNanos(value);
        case 'H':
          return TimeUnit.HOURS.toNanos(value);
        default:
          throw new IllegalArgumentException(String.format("Invalid timeout unit: %s", unit));
      }
    }
  }

  /**
   * Returns a transport out of a PickResult, or {@code null} if the result is "buffer".
   */
  @Nullable
  static ClientTransport getTransportFromPickResult(PickResult result, boolean isWaitForReady) {
    final ClientTransport transport;
    Subchannel subchannel = result.getSubchannel();
    if (subchannel != null) {
      transport = ((TransportProvider) subchannel.getInternalSubchannel()).obtainActiveTransport();
    } else {
      transport = null;
    }
    if (transport != null) {
      final ClientStreamTracer.Factory streamTracerFactory = result.getStreamTracerFactory();
      if (streamTracerFactory == null) {
        return transport;
      }
      return new ClientTransport() {
        @Override
        public ClientStream newStream(
            MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions,
            ClientStreamTracer[] tracers) {
          StreamInfo info = StreamInfo.newBuilder().setCallOptions(callOptions).build();
          ClientStreamTracer streamTracer =
              streamTracerFactory.newClientStreamTracer(info, headers);
          checkState(tracers[tracers.length - 1] == NOOP_TRACER, "lb tracer already assigned");
          tracers[tracers.length - 1] = streamTracer;
          return transport.newStream(method, headers, callOptions, tracers);
        }

        @Override
        public void ping(PingCallback callback, Executor executor) {
          transport.ping(callback, executor);
        }

        @Override
        public InternalLogId getLogId() {
          return transport.getLogId();
        }

        @Override
        public ListenableFuture<SocketStats> getStats() {
          return transport.getStats();
        }
      };
    }
    if (!result.getStatus().isOk()) {
      if (result.isDrop()) {
        return new FailingClientTransport(
            replaceInappropriateControlPlaneStatus(result.getStatus()), RpcProgress.DROPPED);
      }
      if (!isWaitForReady) {
        return new FailingClientTransport(
            replaceInappropriateControlPlaneStatus(result.getStatus()), RpcProgress.PROCESSED);
      }
    }
    return null;
  }

  /** Gets stream tracers based on CallOptions. */
  public static ClientStreamTracer[] getClientStreamTracers(
      CallOptions callOptions, Metadata headers, int previousAttempts, boolean isTransparentRetry) {
    List<ClientStreamTracer.Factory> factories = callOptions.getStreamTracerFactories();
    ClientStreamTracer[] tracers = new ClientStreamTracer[factories.size() + 1];
    StreamInfo streamInfo = StreamInfo.newBuilder()
        .setCallOptions(callOptions)
        .setPreviousAttempts(previousAttempts)
        .setIsTransparentRetry(isTransparentRetry)
        .build();
    for (int i = 0; i < factories.size(); i++) {
      tracers[i] = factories.get(i).newClientStreamTracer(streamInfo, headers);
    }
    // Reserved to be set later by the lb as per the API contract of ClientTransport.newStream().
    // See also GrpcUtil.getTransportFromPickResult()
    tracers[tracers.length - 1] = NOOP_TRACER;
    return tracers;
  }

  /** Quietly closes all messages in MessageProducer. */
  static void closeQuietly(MessageProducer producer) {
    InputStream message;
    while ((message = producer.next()) != null) {
      closeQuietly(message);
    }
  }

  /**
   * Closes a Closeable, ignoring IOExceptions.
   * This method exists because Guava's {@code Closeables.closeQuietly()} is beta.
   */
  public static void closeQuietly(@Nullable Closeable message) {
    if (message == null) {
      return;
    }
    try {
      message.close();
    } catch (IOException ioException) {
      // do nothing except log
      log.log(Level.WARNING, "exception caught in closeQuietly", ioException);
    }
  }

  /** Reads {@code in} until end of stream. */
  public static void exhaust(InputStream in) throws IOException {
    byte[] buf = new byte[256];
    while (in.read(buf) != -1) {}
  }

  /**
   * Some status codes from the control plane are not appropritate to use in the data plane. If one
   * is given it will be replaced with INTERNAL, indicating a bug in the control plane
   * implementation.
   */
  public static Status replaceInappropriateControlPlaneStatus(Status status) {
    checkArgument(status != null);
    return INAPPROPRIATE_CONTROL_PLANE_STATUS.contains(status.getCode())
        ? Status.INTERNAL.withDescription(
        "Inappropriate status code from control plane: " + status.getCode() + " "
            + status.getDescription()).withCause(status.getCause()) : status;
  }

  /**
   * Checks whether the given item exists in the iterable.  This is copied from Guava Collect's
   * {@code Iterables.contains()} because Guava Collect is not Android-friendly thus core can't
   * depend on it.
   */
  static <T> boolean iterableContains(Iterable<T> iterable, T item) {
    if (iterable instanceof Collection) {
      Collection<?> collection = (Collection<?>) iterable;
      try {
        return collection.contains(item);
      } catch (NullPointerException e) {
        return false;
      } catch (ClassCastException e) {
        return false;
      }
    }
    for (T i : iterable) {
      if (Objects.equal(i, item)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Percent encode the {@code authority} based on
   * https://datatracker.ietf.org/doc/html/rfc3986#section-3.2.
   *
   * <p>When escaping a String, the following rules apply:
   *
   * <ul>
   *   <li>The alphanumeric characters "a" through "z", "A" through "Z" and "0" through "9" remain
   *       the same.
   *   <li>The unreserved characters ".", "-", "~", and "_" remain the same.
   *   <li>The general delimiters for authority, "[", "]", "@" and ":" remain the same.
   *   <li>The subdelimiters "!", "$", "&amp;", "'", "(", ")", "*", "+", ",", ";", and "=" remain
   *       the same.
   *   <li>The space character " " is converted into %20.
   *   <li>All other characters are converted into one or more bytes using UTF-8 encoding and each
   *       byte is then represented by the 3-character string "%XY", where "XY" is the two-digit,
   *       uppercase, hexadecimal representation of the byte value.
   * </ul>
   * 
   * <p>This section does not use URLEscapers from Guava Net as its not Android-friendly thus core
   *    can't depend on it.
   */
  public static class AuthorityEscaper {
    // Escapers should output upper case hex digits.
    private static final char[] UPPER_HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    private static final Set<Character> UNRESERVED_CHARACTERS = Collections
        .unmodifiableSet(new HashSet<>(Arrays.asList('-', '_', '.', '~')));
    private static final Set<Character> SUB_DELIMS = Collections
        .unmodifiableSet(new HashSet<>(
            Arrays.asList('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')));
    private static final Set<Character> AUTHORITY_DELIMS = Collections
        .unmodifiableSet(new HashSet<>(Arrays.asList(':', '[', ']', '@')));

    private static boolean shouldEscape(char c) {
      // Only encode ASCII.
      if (c > 127) {
        return false;
      }
      // Letters don't need an escape.
      if (((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))) {
        return false;
      }
      // Numbers don't need to be escaped.
      if ((c >= '0' && c <= '9'))  {
        return false;
      }
      // Don't escape allowed characters.
      if (UNRESERVED_CHARACTERS.contains(c)
          || SUB_DELIMS.contains(c)
          || AUTHORITY_DELIMS.contains(c)) {
        return false;
      }
      return true;
    }

    public static String encodeAuthority(String authority) {
      Preconditions.checkNotNull(authority, "authority");
      int authorityLength = authority.length();
      int hexCount = 0;
      // Calculate how many characters actually need escaping.
      for (int index = 0; index < authorityLength; index++) {
        char c = authority.charAt(index);
        if (shouldEscape(c)) {
          hexCount++;
        }
      }
      // If no char need escaping, just return the original string back.
      if (hexCount == 0) {
        return authority;
      }

      // Allocate enough space as encoded characters need 2 extra chars.
      StringBuilder encoded_authority = new StringBuilder((2 * hexCount) + authorityLength);
      for (int index = 0; index < authorityLength; index++) {
        char c = authority.charAt(index);
        if (shouldEscape(c)) {
          encoded_authority.append('%');
          encoded_authority.append(UPPER_HEX_DIGITS[c >>> 4]);
          encoded_authority.append(UPPER_HEX_DIGITS[c & 0xF]);
        } else {
          encoded_authority.append(c);
        }
      }
      return encoded_authority.toString();
    }
  }

  public static boolean getFlag(String envVarName, boolean enableByDefault) {
    String envVar = System.getenv(envVarName);
    if (envVar == null) {
      envVar = System.getProperty(envVarName);
    }
    if (envVar != null) {
      envVar = envVar.trim();
    }
    if (enableByDefault) {
      return Strings.isNullOrEmpty(envVar) || Boolean.parseBoolean(envVar);
    } else {
      return !Strings.isNullOrEmpty(envVar) && Boolean.parseBoolean(envVar);
    }
  }



  private GrpcUtil() {}
}
