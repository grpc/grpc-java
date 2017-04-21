/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.instrumentation.trace.ContextUtils.CONTEXT_SPAN_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.instrumentation.trace.BinaryPropagationHandler;
import com.google.instrumentation.trace.Span;
import com.google.instrumentation.trace.SpanContext;
import com.google.instrumentation.trace.Tracer;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StreamTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Provides factories for {@link StreamTracer} that records traces to Census.
 *
 * <p>On the client-side, a factory is created for each call, because ClientCall starts earlier than
 * the ClientStream, and in some cases may even not create a ClientStream at all.  Therefore, it's
 * the factory that reports the summary to Census.
 *
 * <p>On the server-side, a tracer is created for each call, because ServerStream starts earlier
 * than the ServerCall.  Therefore, it's the tracer that reports the summary to Census.
 */
final class CensusTracingModule {
  private static final Logger logger = Logger.getLogger(CensusTracingModule.class.getName());
  // TODO(zhangkun83): record NetworkEvent to Span for each message
  private static final ClientStreamTracer noopClientTracer = new ClientStreamTracer() {};

  private final Tracer censusTracer;
  private final BinaryPropagationHandler censusTracingPropagationHandler;
  @VisibleForTesting
  final Metadata.Key<SpanContext> tracingHeader;
  private final TracingClientInterceptor clientInterceptor = new TracingClientInterceptor();
  private final ServerTracerFactory serverTracerFactory = new ServerTracerFactory();

  CensusTracingModule(
      Tracer censusTracer, final BinaryPropagationHandler censusTracingPropagationHandler) {
    this.censusTracer = checkNotNull(censusTracer, "censusTracer");
    this.censusTracingPropagationHandler =
        checkNotNull(censusTracingPropagationHandler, "censusTracingPropagationHandler");
    this.tracingHeader =
        Metadata.Key.of("grpc-trace-bin", new Metadata.BinaryMarshaller<SpanContext>() {
            @Override
            public byte[] toBytes(SpanContext context) {
              return censusTracingPropagationHandler.toBinaryValue(context);
            }

            @Override
            public SpanContext parseBytes(byte[] serialized) {
              try {
                return censusTracingPropagationHandler.fromBinaryValue(serialized);
              } catch (Exception e) {
                logger.log(Level.FINE, "Failed to parse tracing header", e);
                return SpanContext.INVALID;
              }
            }
          });
  }

  /**
   * Creates a {@link ClientCallTracer} for a new call.
   */
  @VisibleForTesting
  ClientCallTracer newClientCallTracer(@Nullable Span parentSpan, String fullMethodName) {
    return new ClientCallTracer(parentSpan, fullMethodName);
  }

  /**
   * Returns the server tracer factory.
   */
  ServerStreamTracer.Factory getServerTracerFactory() {
    return serverTracerFactory;
  }

  /**
   * Returns the client interceptor that facilitates Census-based stats reporting.
   */
  ClientInterceptor getClientInterceptor() {
    return clientInterceptor;
  }

  private static String makeSpanName(String prefix, String fullMethodName) {
    return prefix + "." + fullMethodName.replace('/', '.');
  }

  @VisibleForTesting
  final class ClientCallTracer extends ClientStreamTracer.Factory {

    private final String fullMethodName;
    private final AtomicBoolean callEnded = new AtomicBoolean(false);
    private final Span span;

    ClientCallTracer(@Nullable Span parentSpan, String fullMethodName) {
      this.span =
          censusTracer.spanBuilder(parentSpan, makeSpanName("Sent", fullMethodName)).startSpan();
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(Metadata headers) {
      headers.discardAll(tracingHeader);
      headers.put(tracingHeader, span.getContext());
      return noopClientTracer;
    }

    /**
     * Record a finished call and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    void callEnded(Status status) {
      if (!callEnded.compareAndSet(false, true)) {
        return;
      }
      span.end();
    }
  }

  private final class ServerTracer extends ServerStreamTracer {
    private final String fullMethodName;
    private final Span span;
    private final AtomicBoolean streamClosed = new AtomicBoolean(false);

    ServerTracer(String fullMethodName, @Nullable SpanContext remoteSpan) {
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.span =
          censusTracer.spanBuilderWithRemoteParent(
              remoteSpan, makeSpanName("Recv", fullMethodName))
          .startSpan();
    }

    /**
     * Record a finished stream and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    @Override
    public void streamClosed(Status status) {
      if (!streamClosed.compareAndSet(false, true)) {
        return;
      }
      span.end();
    }

    @Override
    public <ReqT, RespT> Context filterContext(Context context) {
      return context.withValue(CONTEXT_SPAN_KEY, span);
    }
  }

  private final class ServerTracerFactory extends ServerStreamTracer.Factory {
    @SuppressWarnings("ReferenceEquality")
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      SpanContext remoteSpan = headers.get(tracingHeader);
      if (remoteSpan == SpanContext.INVALID) {
        remoteSpan = null;
      }
      return new ServerTracer(fullMethodName, remoteSpan);
    }
  }

  private class TracingClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      Span parentSpan = CONTEXT_SPAN_KEY.get();
      final ClientCallTracer tracerFactory =
          newClientCallTracer(parentSpan, method.getFullMethodName());
      ClientCall<ReqT, RespT> call =
          next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));
      return new SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          delegate().start(
              new SimpleForwardingClientCallListener<RespT>(responseListener) {
                @Override
                public void onClose(Status status, Metadata trailers) {
                  tracerFactory.callEnded(status);
                  super.onClose(status, trailers);
                }
              },
              headers);
        }
      };
    }
  }
}
