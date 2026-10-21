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

import static io.grpc.census.internal.ObservabilityCensusConstants.CLIENT_TRACE_SPAN_CONTEXT_KEY;

import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Internal;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.opencensus.trace.SpanContext;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A logging client interceptor for Observability.
 */
@Internal
public final class InternalLoggingChannelInterceptor implements ClientInterceptor {

  private static final Logger logger = Logger
      .getLogger(InternalLoggingChannelInterceptor.class.getName());

  private final LogHelper helper;
  private final ConfigFilterHelper filterHelper;

  // TODO(dnvindhya): Remove factory and use interceptors directly
  public interface Factory {
    ClientInterceptor create();
  }

  public static class FactoryImpl implements Factory {

    private final LogHelper helper;
    private final ConfigFilterHelper filterHelper;

    /**
     * Create the {@link Factory} we need to create our {@link ClientInterceptor}s.
     */
    public FactoryImpl(LogHelper helper, ConfigFilterHelper filterHelper) {
      this.helper = helper;
      this.filterHelper = filterHelper;
    }

    @Override
    public ClientInterceptor create() {
      return new InternalLoggingChannelInterceptor(helper, filterHelper);
    }
  }

  private InternalLoggingChannelInterceptor(LogHelper helper, ConfigFilterHelper filterHelper) {
    this.helper = helper;
    this.filterHelper = filterHelper;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {

    final AtomicLong seq = new AtomicLong(1);
    final String callId = UUID.randomUUID().toString();
    final String authority = next.authority();
    final String serviceName = method.getServiceName();
    final String methodName = method.getBareMethodName();
    // Get the stricter deadline to calculate the timeout once the call starts
    final Deadline deadline = LogHelper.min(callOptions.getDeadline(),
        Context.current().getDeadline());
    final SpanContext clientSpanContext = callOptions.getOption(CLIENT_TRACE_SPAN_CONTEXT_KEY);

    FilterParams filterParams = filterHelper.logRpcMethod(method.getFullMethodName(), true);
    if (!filterParams.log()) {
      return next.newCall(method, callOptions);
    }

    final int maxHeaderBytes = filterParams.headerBytes();
    final int maxMessageBytes = filterParams.messageBytes();

    return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        // Event: EventType.CLIENT_HEADER
        // The timeout should reflect the time remaining when the call is started, so compute
        // remaining time here.
        final Duration timeout = deadline == null ? null
            : Durations.fromNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS));

        try {
          helper.logClientHeader(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              timeout,
              headers,
              maxHeaderBytes,
              EventLogger.CLIENT,
              callId,
              null,
              clientSpanContext);
        } catch (Exception e) {
          // Catching generic exceptions instead of specific ones for all the events.
          // This way we can catch both expected and unexpected exceptions instead of re-throwing
          // exceptions to callers which will lead to RPC getting aborted.
          // Expected exceptions to be caught:
          // 1. IllegalArgumentException
          // 2. NullPointerException
          logger.log(Level.SEVERE, "Unable to log request header", e);
        }

        Listener<RespT> observabilityListener =
            new SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onMessage(RespT message) {
                // Event: EventType.SERVER_MESSAGE
                try {
                  helper.logRpcMessage(
                      seq.getAndIncrement(),
                      serviceName,
                      methodName,
                      authority,
                      EventType.SERVER_MESSAGE,
                      message,
                      maxMessageBytes,
                      EventLogger.CLIENT,
                      callId,
                      clientSpanContext);
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "Unable to log response message", e);
                }
                super.onMessage(message);
              }

              @Override
              public void onHeaders(Metadata headers) {
                // Event: EventType.SERVER_HEADER
                try {
                  helper.logServerHeader(
                      seq.getAndIncrement(),
                      serviceName,
                      methodName,
                      authority,
                      headers,
                      maxHeaderBytes,
                      EventLogger.CLIENT,
                      callId,
                      LogHelper.getPeerAddress(getAttributes()),
                      clientSpanContext);
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "Unable to log response header", e);
                }
                super.onHeaders(headers);
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
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
                      EventLogger.CLIENT,
                      callId,
                      LogHelper.getPeerAddress(getAttributes()),
                      clientSpanContext);
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "Unable to log trailer", e);
                }
                super.onClose(status, trailers);
              }
            };
        super.start(observabilityListener, headers);
      }

      @Override
      public void sendMessage(ReqT message) {
        // Event: EventType.CLIENT_MESSAGE
        try {
          helper.logRpcMessage(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              EventType.CLIENT_MESSAGE,
              message,
              maxMessageBytes,
              EventLogger.CLIENT,
              callId,
              clientSpanContext);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log request message", e);
        }
        super.sendMessage(message);
      }

      @Override
      public void halfClose() {
        // Event: EventType.CLIENT_HALF_CLOSE
        try {
          helper.logHalfClose(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              EventLogger.CLIENT,
              callId,
              clientSpanContext);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log half close", e);
        }
        super.halfClose();
      }

      @Override
      public void cancel(String message, Throwable cause) {
        // Event: EventType.CANCEL
        try {
          helper.logCancel(
              seq.getAndIncrement(),
              serviceName,
              methodName,
              authority,
              EventLogger.CLIENT,
              callId,
              clientSpanContext);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Unable to log cancel", e);
        }
        super.cancel(message, cause);
      }
    };
  }
}
