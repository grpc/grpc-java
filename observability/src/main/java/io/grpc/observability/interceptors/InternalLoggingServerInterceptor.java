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
import io.grpc.observability.interceptors.LogHelper.LogSinkWriter;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A logging interceptor for {@code LoggingServerProvider}. */
@Internal
public final class InternalLoggingServerInterceptor implements ServerInterceptor {
  private final LogSinkWriter writer;

  public interface Factory {
    ServerInterceptor create();
  }

  public static class FactoryImpl implements Factory {
    Sink sink;

    public FactoryImpl(Sink sink) {
      this.sink = sink;
    }

    @Override
    public ServerInterceptor create() {
      return new InternalLoggingServerInterceptor(sink);
    }
  }

  private InternalLoggingServerInterceptor(Sink sink) {
    this.writer = new LogSinkWriter(sink, TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
      Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    // TODO(dnvindhya) implement the interceptor
    final AtomicLong seq = new AtomicLong(1);
    final String rpcId = UUID.randomUUID().toString();
    final String authority = call.getAuthority();
    final String serviceName = call.getMethodDescriptor().getServiceName();
    final String methodName = call.getMethodDescriptor().getBareMethodName();
    final SocketAddress peerAddress = LogHelper.getPeerAddress(call.getAttributes());
    Deadline deadline = Context.current().getDeadline();
    final Duration timeout = deadline == null ? null
        : Durations.fromNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS));

    // Event: EventType.GRPC_CALL_REQUEST_HEADER
    writer.logRequestHeader(
        seq.getAndIncrement(),
        serviceName,
        methodName,
        authority,
        timeout,
        headers,
        EventLogger.LOGGER_SERVER,
        rpcId,
        peerAddress);

    ServerCall<ReqT, RespT> wrapperCall =
        new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendMessage(RespT message) {
            // Event: EventType.GRPC_CALL_RESPONSE_MESSAGE
            writer.logRpcMessage(
                seq.getAndIncrement(),
                serviceName,
                methodName,
                EventType.GRPC_CALL_RESPONSE_MESSAGE,
                message,
                EventLogger.LOGGER_SERVER,
                rpcId);
            super.sendMessage(message);
          }

          @Override
          public void sendHeaders(Metadata headers) {
            // Event: EventType.GRPC_CALL_RESPONSE_HEADER
            writer.logResponseHeader(
                seq.getAndIncrement(),
                serviceName,
                methodName,
                headers,
                EventLogger.LOGGER_SERVER,
                rpcId,
                null);
            super.sendHeaders(headers);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            // Event: EventType.GRPC_CALL_TRAILER
            writer.logTrailer(
                seq.getAndIncrement(),
                serviceName,
                methodName,
                status,
                trailers,
                EventLogger.LOGGER_SERVER,
                rpcId,
                null);
            super.close(status, trailers);
          }
        };

    ServerCall.Listener<ReqT> listener = next.startCall(wrapperCall, headers);
    return new SimpleForwardingServerCallListener<ReqT>(listener) {
      @Override
      public void onMessage(ReqT message) {
        // Event: EventType.GRPC_CALL_REQUEST_MESSAGE
        writer.logRpcMessage(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            EventType.GRPC_CALL_REQUEST_MESSAGE,
            message,
            EventLogger.LOGGER_SERVER,
            rpcId);
        super.onMessage(message);
      }

      @Override
      public void onHalfClose() {
        // Event: EventType.GRPC_CALL_HALF_CLOSE
        writer.logHalfClose(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            EventLogger.LOGGER_SERVER,
            rpcId);
        super.onHalfClose();
      }

      @Override
      public void onCancel() {
        // Event: EventType.GRPC_CALL_CANCEL
        writer.logCancel(
            seq.getAndIncrement(),
            serviceName,
            methodName,
            EventLogger.LOGGER_SERVER,
            rpcId);
        super.onCancel();
      }
    };
  }
}
