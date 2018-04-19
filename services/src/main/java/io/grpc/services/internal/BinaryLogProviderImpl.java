/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.services.internal;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.ExperimentalApi;
import io.grpc.InternalClientInterceptors;
import io.grpc.InternalServerInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.BinaryLogProvider;
import io.grpc.internal.IoUtils;
import io.opencensus.trace.Span;
import io.opencensus.trace.Tracing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4209")
public class BinaryLogProviderImpl extends BinaryLogProvider {
  private static final Logger log = Logger.getLogger(BinaryLogProviderImpl.class.getName());
  static final Context.Key<CallId> SERVER_CALL_ID_CONTEXT_KEY
      = Context.key("binarylog-context-key");
  static final CallOptions.Key<CallId> CLIENT_CALL_ID_CALLOPTION_KEY
      = CallOptions.Key.of("binarylog-calloptions-key", null);
  static final Marshaller<byte[]> BYTEARRAY_MARSHALLER = new ByteArrayMarshaller();

  private final ClientInterceptor binaryLogShim = new BinaryLogShim();
  private final boolean enabled;
  private final BinaryLogSink sink;
  private final BinaryLog.Factory factory;

  /**
   * Default constructor for service loader.
   */
  public BinaryLogProviderImpl() {
    sink = BinaryLogSinkProvider.provider();
    String globalConfigStr = System.getenv("GRPC_BINARY_LOG_CONFIG");
    if (sink == null || globalConfigStr == null) {
      log.log(
          Level.INFO,
          "Binary logging is disabled. Sink={} config={}",
          new Object[] {sink, globalConfigStr});
      enabled = false;
      factory = null;
      return;
    }
    factory = new BinaryLog.FactoryImpl(sink, globalConfigStr);
    enabled = true;
  }

  @VisibleForTesting
  BinaryLogProviderImpl(BinaryLogSink sink, BinaryLog.Factory factory) {
    enabled = true;
    this.sink = sink;
    this.factory = factory;
  }

  @Override
  public Channel wrapChannel(Channel channel) {
    if (!enabled) {
      return channel;
    }
    return ClientInterceptors.intercept(channel, binaryLogShim);
  }

  /**
   * Wraps a {@link ServerMethodDefinition} such that it performs binary logging if needed.
   */
  @Override
  public <ReqT, RespT> ServerMethodDefinition<?, ?> wrapMethodDefinition(
      ServerMethodDefinition<ReqT, RespT> oMethodDef) {
    if (!enabled) {
      return oMethodDef;
    }
    ServerInterceptor binlogInterceptor =
        factory.getServerInterceptor(oMethodDef.getMethodDescriptor().getFullMethodName());
    if (binlogInterceptor == null) {
      return oMethodDef;
    }
    MethodDescriptor<byte[], byte[]> binMethod =
        toByteBufferMethod(oMethodDef.getMethodDescriptor());
    ServerMethodDefinition<byte[], byte[]> binDef = InternalServerInterceptors
        .wrapMethod(oMethodDef, binMethod);
    ServerCallHandler<byte[], byte[]> binlogHandler = InternalServerInterceptors
        .interceptCallHandler(binlogInterceptor, binDef.getServerCallHandler());
    return ServerMethodDefinition.create(binMethod, binlogHandler);
  }

  private static MethodDescriptor<byte[], byte[]> toByteBufferMethod(
      MethodDescriptor<?, ?> method) {
    return method.toBuilder(BYTEARRAY_MARSHALLER, BYTEARRAY_MARSHALLER).build();
  }

  private static final ServerStreamTracer SERVER_CALLID_SETTER = new ServerStreamTracer() {
    @Override
    public Context filterContext(Context context) {
      Context toRestore = context.attach();
      try {
        Span span = Tracing.getTracer().getCurrentSpan();
        if (span == null) {
          return context;
        }

        return context.withValue(SERVER_CALL_ID_CONTEXT_KEY, CallId.fromCensusSpan(span));
      } finally {
        context.detach(toRestore);
      }
    }
  };

  private static final ServerStreamTracer.Factory SERVER_CALLID_SETTER_FACTORY
      = new ServerStreamTracer.Factory() {
          @Override
          public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
            return SERVER_CALLID_SETTER;
          }
      };

  @Override
  public ServerStreamTracer.Factory getServerCallIdSetter() {
    return SERVER_CALLID_SETTER_FACTORY;
  }

  private static final ClientInterceptor CLIENT_CALLID_SETTER = new ClientInterceptor() {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      Span span = Tracing.getTracer().getCurrentSpan();
      if (span == null) {
        return next.newCall(method, callOptions);
      }

      return next.newCall(
          method,
          callOptions.withOption(CLIENT_CALL_ID_CALLOPTION_KEY, CallId.fromCensusSpan(span)));
    }
  };

  @Override
  public ClientInterceptor getClientCallIdSetter() {
    return CLIENT_CALLID_SETTER;
  }

  @Override
  public void close() throws IOException {
    sink.close();
  }

  // Creating a named class makes debugging easier
  private static final class ByteArrayMarshaller implements Marshaller<byte[]> {
    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return parseHelper(stream);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private byte[] parseHelper(InputStream stream) throws IOException {
      try {
        return IoUtils.toByteArray(stream);
      } finally {
        stream.close();
      }
    }
  }

  /**
   * The pipeline of interceptors is hard coded when the ManagedChannelImpl is created.
   * This shim interceptor should always be installed as a placeholder. When a call starts,
   * this interceptor checks with the {@link BinaryLogProvider} to see if logging should happen
   * for this particular {@link ClientCall}'s method.
   */
  private final class BinaryLogShim implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      ClientInterceptor binlogInterceptor
          = factory.getClientInterceptor(method.getFullMethodName());
      if (binlogInterceptor == null) {
        return next.newCall(method, callOptions);
      } else {
        return InternalClientInterceptors
            .wrapClientInterceptor(
                binlogInterceptor,
                BYTEARRAY_MARSHALLER,
                BYTEARRAY_MARSHALLER)
            .interceptCall(method, callOptions, next);
      }
    }
  }

  /**
   * A CallId is two byte[] arrays both of size 8 that uniquely identifies the RPC. Users are
   * free to use the byte arrays however they see fit.
   */
  public static final class CallId {
    public final long hi;
    public final long lo;

    /**
     * Creates an instance.
     */
    public CallId(long hi, long lo) {
      this.hi = hi;
      this.lo = lo;
    }

    static CallId fromCensusSpan(Span span) {
      return new CallId(0, ByteBuffer.wrap(span.getContext().getSpanId().getBytes()).getLong());
    }
  }

  @Override
  protected int priority() {
    return 5;
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }
}
