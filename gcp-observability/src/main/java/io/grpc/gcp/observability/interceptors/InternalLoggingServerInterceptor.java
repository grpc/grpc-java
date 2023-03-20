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

package io.grpc.gcp.observability.interceptors;

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
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.unsafe.ContextHandleUtils;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A logging server interceptor for Observability.
 */
@Internal
public final class InternalLoggingServerInterceptor implements ServerInterceptor {

  private static final Logger logger = Logger
      .getLogger(InternalLoggingServerInterceptor.class.getName());

  private final LogHelper helper;
  private final ConfigFilterHelper filterHelper;

  // TODO(dnvindhya): Remove factory and use interceptors directly
  public interface Factory {
    ServerInterceptor create();
  }

  public static class FactoryImpl implements Factory {

    private final LogHelper helper;
    private final ConfigFilterHelper filterHelper;

    /**
     * Create the {@link Factory} we need to create our {@link ServerInterceptor}s.
     */
    public FactoryImpl(LogHelper helper, ConfigFilterHelper filterHelper) {
      this.helper = helper;
      this.filterHelper = filterHelper;
    }

    @Override
    public ServerInterceptor create() {
      return new InternalLoggingServerInterceptor(helper, filterHelper);
    }
  }

  private InternalLoggingServerInterceptor(LogHelper helper, ConfigFilterHelper filterHelper) {
    this.helper = helper;
    this.filterHelper = filterHelper;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
      Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    final AtomicLong seq = new AtomicLong(1);
    final String callId = UUID.randomUUID().toString();
    final String authority = call.getAuthority();
    final String serviceName = call.getMethodDescriptor().getServiceName();
    final String methodName = call.getMethodDescriptor().getBareMethodName();
    final SocketAddress peerAddress = LogHelper.getPeerAddress(call.getAttributes());
    Deadline deadline = Context.current().getDeadline();
    final Duration timeout = deadline == null ? null
        : Durations.fromNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS));
    Span span = ContextHandleUtils.getValue(ContextHandleUtils.currentContext());
    final SpanContext serverSpanContext = span == null ? SpanContext.INVALID : span.getContext();

    FilterParams filterParams =
        filterHelper.logRpcMethod(call.getMethodDescriptor().getFullMethodName(), false);
    if (!filterParams.log()) {
      return next.startCall(call, headers);
    }

    final int maxHeaderBytes = filterParams.headerBytes();
    final int maxMessageBytes = filterParams.messageBytes();

    // Event: EventType.CLIENT_HEADER
    try {
      helper.logClientHeader(
          seq.getAndIncrement(),
          serviceName,
          methodName,
          authority,
          timeout,
          headers,
          maxHeaderBytes,
          EventLogger.SERVER,
          callId,
          peerAddress,
          serverSpanContext);
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
            // Event: EventType.SERVER_HEADER
            try {
              helper.logServerHeader(
                  seq.getAndIncrement(),
                  serviceName,
                  methodName,
                  authority,
                  headers,
                  maxHeaderBytes,
                  EventLogger.SERVER,
                  callId,
                  null,
                  serverSpanContext);
            } catch (Exception e) {
              logger.log(Level.SEVERE, "Unable to log response header", e);
            }
            super.sendHeaders(headers);
          }

          @Override
          public void sendMessage(RespT message) {
            // Event: EventType.SERVER_MESSAGE
            EventType responseMessageType = EventType.SERVER_MESSAGE;
            try {
              helper.logRpcMessage(
                  seq.getAndIncrement(),
                  serviceName,
                  methodName,
                  authority,
                  responseMessageType,
                  message,
                  maxMessageBytes,
                  EventLogger.SERVER,
                  callId,
                  serverSpanContext);
            } catch (Exception e) {
              logger.log(Level.SEVERE, "Unable to log response message", e);
            }
            super.sendMessage(message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            // Event: EventType.SERVER_TRAILER
            try {
              helper.logTrailer(
                  seq.getAndIncrement(),
                  serviceName,
                  methodName,
                  authority,
                  status,
                  trailers,
                  maxHeaderBytes,
                  EventLogger.SERVER,
                  callId,
                  null,
                  serverSpanContext);
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

        // Event: EventType.CLIENT_MESSAGE
        EventType requestMessageType = EventType.CLIENT_MESSAGE;
        try {
          helper.logRpcMessage(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              requestMessageType,
              message,
              maxMessageBytes,
              EventLogger.SERVER,
              callId,
              serverSpanContext);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log request message", e);
        }
        super.onMessage(message);
      }

      @Override
      public void onHalfClose() {
        // Event: EventType.CLIENT_HALF_CLOSE
        try {
          helper.logHalfClose(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              EventLogger.SERVER,
              callId,
              serverSpanContext);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log half close", e);
        }
        super.onHalfClose();
      }

      @Override
      public void onCancel() {
        // Event: EventType.CANCEL
        try {
          helper.logCancel(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              EventLogger.SERVER,
              callId,
              serverSpanContext);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log cancel", e);
        }
        super.onCancel();
      }
    };
  }
}
