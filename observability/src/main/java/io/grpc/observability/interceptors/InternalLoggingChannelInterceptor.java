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
import io.grpc.internal.TimeProvider;
import io.grpc.observability.interceptors.LogHelper.LogSinkWriter;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A logging interceptor for {@code LoggingChannelProvider}.
 */
@Internal
public final class InternalLoggingChannelInterceptor implements ClientInterceptor {
  private final LogSinkWriter writer;

  public interface Factory {
    ClientInterceptor create();
  }

  public static class FactoryImpl implements Factory {
    Sink sink;

    public FactoryImpl(Sink sink) {
      this.sink = sink;
    }

    @Override
    public ClientInterceptor create() {
      return new InternalLoggingChannelInterceptor(sink);
    }
  }

  private InternalLoggingChannelInterceptor(Sink sink) {
    this.writer = new LogSinkWriter(sink, TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {

    final AtomicLong seq = new AtomicLong(1);
    final String rpcId = UUID.randomUUID().toString();
    final String authority = next.authority();
    final String serviceName = method.getServiceName();
    final String methodName = method.getBareMethodName();
    // Get the stricter deadline to calculate the timeout once the call starts
    final Deadline deadline = LogHelper.min(callOptions.getDeadline(),
        Context.current().getDeadline());

    return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        // Event: EventType.GRPC_CALL_REQUEST_HEADER
        // The timeout should reflect the time remaining when the call is started, so compute
        // remaining time here.
        final Duration timeout = deadline == null ? null
            : Durations.fromNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS));

        writer.logRequestHeader(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            authority,
            timeout,
            headers,
            EventLogger.LOGGER_CLIENT,
            rpcId,
            null);

        Listener<RespT> observabilityListener =
            new SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onMessage(RespT message) {
                // Event: EventType.GRPC_CALL_RESPONSE_MESSAGE
                writer.logRpcMessage(
                    seq.getAndIncrement(),
                    serviceName,
                    methodName,
                    EventType.GRPC_CALL_RESPONSE_MESSAGE,
                    message,
                    EventLogger.LOGGER_CLIENT,
                    rpcId);
                super.onMessage(message);
              }

              @Override
              public void onHeaders(Metadata headers) {
                // Event: EventType.GRPC_CALL_RESPONSE_HEADER
                writer.logResponseHeader(
                    seq.getAndIncrement(),
                    serviceName,
                    methodName,
                    headers,
                    EventLogger.LOGGER_CLIENT,
                    rpcId,
                    LogHelper.getPeerAddress(getAttributes()));
                super.onHeaders(headers);
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
                // Event: EventType.GRPC_CALL_TRAILER
                writer.logTrailer(
                    seq.getAndIncrement(),
                    serviceName,
                    methodName,
                    status,
                    trailers,
                    EventLogger.LOGGER_CLIENT,
                    rpcId,
                    LogHelper.getPeerAddress(getAttributes()));
                super.onClose(status, trailers);
              }
            };
        super.start(observabilityListener, headers);
      }

      @Override
      public void sendMessage(ReqT message) {
        // Event: EventType.GRPC_CALL_REQUEST_MESSAGE
        writer.logRpcMessage(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            EventType.GRPC_CALL_REQUEST_MESSAGE,
            message,
            EventLogger.LOGGER_CLIENT,
            rpcId);
        super.sendMessage(message);
      }

      @Override
      public void halfClose() {
        // Event: EventType.GRPC_CALL_HALF_CLOSE
        writer.logHalfClose(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            EventLogger.LOGGER_CLIENT,
            rpcId);
        super.halfClose();
      }

      @Override
      public void cancel(String message, Throwable cause) {
        // Event: EventType.GRPC_CALL_CANCEL
        writer.logCancel(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            EventLogger.LOGGER_CLIENT,
            rpcId);
        super.cancel(message, cause);
      }
    };
  }
}
