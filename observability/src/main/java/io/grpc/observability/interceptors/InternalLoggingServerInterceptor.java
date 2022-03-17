/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability.interceptors;

import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Internal;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.internal.TimeProvider;
import io.grpc.observability.ObservabilityConfig;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A logging interceptor for {@code LoggingServerProvider}. */
@Internal
public final class InternalLoggingServerInterceptor implements ServerInterceptor {
  private static final Logger logger = Logger
      .getLogger(InternalLoggingServerInterceptor.class.getName());

  private final LogHelper helper;

  public interface Factory {
    ServerInterceptor create();
  }

  public static class FactoryImpl implements Factory {
    private final Sink sink;
    private final LogHelper helper;

    /** Create the {@link Factory} we need to create our {@link ServerInterceptor}s. */
    public FactoryImpl(Sink sink, Map<String, String> globalTags,
        ObservabilityConfig observabilityConfig) {
      this.sink = sink;
      this.helper = new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER, globalTags,
          observabilityConfig);
    }

    @Override
    public ServerInterceptor create() {
      return new InternalLoggingServerInterceptor(helper);
    }

    /**
     * Closes the sink instance.
     */
    public void close() {
      if (sink != null) {
        sink.close();
      }
    }
  }

  private InternalLoggingServerInterceptor(LogHelper helper) {
    this.helper = helper;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
      Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    final AtomicLong seq = new AtomicLong(1);
    final String rpcId = UUID.randomUUID().toString();
    final String authority = call.getAuthority();
    final String serviceName = call.getMethodDescriptor().getServiceName();
    final String methodName = call.getMethodDescriptor().getBareMethodName();
    final SocketAddress peerAddress = LogHelper.getPeerAddress(call.getAttributes());
    Deadline deadline = Context.current().getDeadline();
    final Duration timeout = deadline == null ? null
        : Durations.fromNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS));

    // TODO (dnvindhya): implement isMethodToBeLogged() to check for methods to be logged
    // according to config. Until then always return true.
    if (!helper.isMethodToBeLogged(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }

    // Event: EventType.GRPC_CALL_REQUEST_HEADER
    try {
      helper.logRequestHeader(
          seq.getAndIncrement(),
          serviceName,
          methodName,
          authority,
          timeout,
          headers,
          EventLogger.LOGGER_SERVER,
          rpcId,
          peerAddress);
    } catch (Exception e) {
      // Catching generic exceptions instead of specific ones for all the events.
      // This way we can catch both expected and unexpected exceptions instead of re-throwing
      // exceptions to callers which will lead to RPC getting aborted.
      // Expected exceptions to be caught:
      // 1. IllegalArgumentException
      // 2. NullPointerException
      logger.log(Level.SEVERE, "Unable to log request header", e);
    }

    ServerCall<ReqT, RespT> wrapperCall =
        new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendHeaders(Metadata headers) {
            // Event: EventType.GRPC_CALL_RESPONSE_HEADER
            try {
              helper.logResponseHeader(
                  seq.getAndIncrement(),
                  serviceName,
                  methodName,
                  headers,
                  EventLogger.LOGGER_SERVER,
                  rpcId,
                  null);
            } catch (Exception e) {
              logger.log(Level.SEVERE, "Unable to log response header", e);
            }
            super.sendHeaders(headers);
          }

          @Override
          public void sendMessage(RespT message) {
            // Event: EventType.GRPC_CALL_RESPONSE_MESSAGE
            try {
              helper.logRpcMessage(
                  seq.getAndIncrement(),
                  serviceName,
                  methodName,
                  EventType.GRPC_CALL_RESPONSE_MESSAGE,
                  message,
                  EventLogger.LOGGER_SERVER,
                  rpcId);
            } catch (Exception e) {
              logger.log(Level.SEVERE, "Unable to log response message", e);
            }
            super.sendMessage(message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            // Event: EventType.GRPC_CALL_TRAILER
            try {
              helper.logTrailer(
                  seq.getAndIncrement(),
                  serviceName,
                  methodName,
                  status,
                  trailers,
                  EventLogger.LOGGER_SERVER,
                  rpcId,
                  null);
            } catch (Exception e) {
              logger.log(Level.SEVERE, "Unable to log trailer", e);
            }
            super.close(status, trailers);
          }
        };

    ServerCall.Listener<ReqT> listener = next.startCall(wrapperCall, headers);
    return new SimpleForwardingServerCallListener<ReqT>(listener) {
      @Override
      public void onMessage(ReqT message) {
        // Event: EventType.GRPC_CALL_REQUEST_MESSAGE
        try {
          helper.logRpcMessage(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              EventType.GRPC_CALL_REQUEST_MESSAGE,
              message,
              EventLogger.LOGGER_SERVER,
              rpcId);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log request message", e);
        }
        super.onMessage(message);
      }

      @Override
      public void onHalfClose() {
        // Event: EventType.GRPC_CALL_HALF_CLOSE
        try {
          helper.logHalfClose(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              EventLogger.LOGGER_SERVER,
              rpcId);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log half close", e);
        }
        super.onHalfClose();
      }

      @Override
      public void onCancel() {
        // Event: EventType.GRPC_CALL_CANCEL
        try {
          helper.logCancel(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              EventLogger.LOGGER_SERVER,
              rpcId);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log cancel", e);
        }
        super.onCancel();
      }
    };
  }
}
