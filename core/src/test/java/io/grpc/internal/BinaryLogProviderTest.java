/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.IntegerMarshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ReplacingClassLoader;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.StringMarshaller;
import io.grpc.internal.NoopClientCall.NoopClientCallListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BinaryLogProvider}. */
@RunWith(JUnit4.class)
public class BinaryLogProviderTest {
  private static final MethodDescriptor<Integer, Integer> INCREMENT_BY_ONE =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("some/incrementbyone")
          .setRequestMarshaller(new IntegerMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();

  private final String serviceFile = "META-INF/services/io.grpc.internal.BinaryLogProvider";
  private final Marshaller<String> reqMarshaller = spy(StringMarshaller.INSTANCE);
  private final Marshaller<Integer> respMarshaller = spy(IntegerMarshaller.INSTANCE);
  private final MethodDescriptor<String, Integer> method =
      MethodDescriptor
          .newBuilder(reqMarshaller, respMarshaller)
          .setFullMethodName("myservice/mymethod")
          .setType(MethodType.UNARY)
          .setSchemaDescriptor(new Object())
          .setIdempotent(true)
          .setSafe(true)
          .setSampledToLocalTracing(true)
          .build();
  private final List<byte[]> binlogReq = new ArrayList<byte[]>();
  private final List<byte[]> binlogResp = new ArrayList<byte[]>();
  private final TestBinaryLogClientInterceptor clientBinlogInterceptor =
      new TestBinaryLogClientInterceptor();
  private final BinaryLogProvider binlogProvider = new BinaryLogProvider() {
      @Override
      public ServerInterceptor getServerInterceptor(String fullMethodName) {
        return null;
      }

      @Override
      public ClientInterceptor getClientInterceptor(String fullMethodName) {
        return clientBinlogInterceptor;
      }

      @Override
      protected int priority() {
        return 0;
      }
    };

  @Test
  public void noProvider() {
    assertNull(BinaryLogProvider.provider());
  }

  @Test
  public void multipleProvider() {
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/internal/BinaryLogProviderTest-multipleProvider.txt");
    assertSame(Provider7.class, BinaryLogProvider.load(cl).getClass());
  }

  @Test
  public void unavailableProvider() {
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/internal/BinaryLogProviderTest-unavailableProvider.txt");
    assertNull(BinaryLogProvider.load(cl));
  }

  @Test
  public void wrapChannel_methodDescriptor() throws Exception {
    final AtomicReference<MethodDescriptor<?, ?>> methodRef =
        new AtomicReference<MethodDescriptor<?, ?>>();
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions) {
        methodRef.set(method);
        return new NoopClientCall<RequestT, ResponseT>();
      }

      @Override
      public String authority() {
        throw new UnsupportedOperationException();
      }
    };
    Channel wChannel = binlogProvider.wrapChannel(channel);
    ClientCall<String, Integer> ignoredClientCall = wChannel.newCall(method, CallOptions.DEFAULT);
    validateWrappedMethod(methodRef.get());
  }

  @Test
  public void wrapChannel_handler() throws Exception {
    final List<InputStream> serializedReq = new ArrayList<InputStream>();
    final AtomicReference<ClientCall.Listener<?>> listener =
        new AtomicReference<ClientCall.Listener<?>>();
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new NoopClientCall<RequestT, ResponseT>() {
          @Override
          public void start(Listener<ResponseT> responseListener, Metadata headers) {
            listener.set(responseListener);
          }

          @Override
          public void sendMessage(RequestT message) {
            serializedReq.add((InputStream) message);
          }
        };
      }

      @Override
      public String authority() {
        throw new UnsupportedOperationException();
      }
    };
    Channel wChannel = binlogProvider.wrapChannel(channel);
    ClientCall<String, Integer> clientCall = wChannel.newCall(method, CallOptions.DEFAULT);
    final List<Integer> observedResponse = new ArrayList<Integer>();
    clientCall.start(
        new NoopClientCallListener<Integer>() {
          @Override
          public void onMessage(Integer message) {
            observedResponse.add(message);
          }
        },
        new Metadata());

    String actualRequest = "hello world";
    assertThat(binlogReq).isEmpty();
    assertThat(serializedReq).isEmpty();
    verify(reqMarshaller, never()).stream(any(String.class));
    clientCall.sendMessage(actualRequest);
    // it is unacceptably expensive for the binlog to double parse every logged message
    verify(reqMarshaller, times(1)).stream(any(String.class));
    verify(reqMarshaller, never()).parse(any(InputStream.class));
    assertThat(binlogReq).hasSize(1);
    assertThat(serializedReq).hasSize(1);
    assertEquals(
        actualRequest,
        StringMarshaller.INSTANCE.parse(new ByteArrayInputStream(binlogReq.get(0))));
    assertEquals(
        actualRequest,
        StringMarshaller.INSTANCE.parse(serializedReq.get(0)));

    int actualResponse = 12345;
    assertThat(binlogResp).isEmpty();
    assertThat(observedResponse).isEmpty();
    verify(respMarshaller, never()).parse(any(InputStream.class));
    onClientMessageHelper(listener.get(), IntegerMarshaller.INSTANCE.stream(actualResponse));
    // it is unacceptably expensive for the binlog to double parse every logged message
    verify(respMarshaller, times(1)).parse(any(InputStream.class));
    verify(respMarshaller, never()).stream(any(Integer.class));
    assertThat(binlogResp).hasSize(1);
    assertThat(observedResponse).hasSize(1);
    assertEquals(
        actualResponse,
        (int) IntegerMarshaller.INSTANCE.parse(new ByteArrayInputStream(binlogResp.get(0))));
    assertEquals(actualResponse, (int) observedResponse.get(0));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void onClientMessageHelper(ClientCall.Listener listener, Object request) {
    listener.onMessage(request);
  }

  private void validateWrappedMethod(MethodDescriptor<?, ?> wMethod) {
    assertSame(BinaryLogProvider.IDENTITY_MARSHALLER, wMethod.getRequestMarshaller());
    assertSame(BinaryLogProvider.IDENTITY_MARSHALLER, wMethod.getResponseMarshaller());
    assertEquals(method.getType(), wMethod.getType());
    assertEquals(method.getFullMethodName(), wMethod.getFullMethodName());
    assertEquals(method.getSchemaDescriptor(), wMethod.getSchemaDescriptor());
    assertEquals(method.isIdempotent(), wMethod.isIdempotent());
    assertEquals(method.isSafe(), wMethod.isSafe());
    assertEquals(method.isSampledToLocalTracing(), wMethod.isSampledToLocalTracing());
  }

  @Test
  public void serverInterceptorFirstAndIsInputstream() throws Exception {
    String serverName = getClass().getName() + "-serverInterceptor";
    ServerInterceptor interceptor = new TracingServerInterceptor(req, resp, methods);
    InProcessServerBuilder builder = InProcessServerBuilder
        .forName(serverName)
        .addService(service)
        .intercept(interceptor);
    TestBinaryLogProvider binlog = new TestBinaryLogProvider(req, resp, binaryMethods);
    InternalInProcessServerBuilder.setBinaryLogProvider(builder, binlog);
    server = builder.build();
    server.start();
    channel = InProcessChannelBuilder.forName(serverName).build();
    int request = 10;
    int result = ClientCalls.blockingUnaryCall(
        channel.newCall(INCREMENT_BY_ONE, CallOptions.DEFAULT),
        request);
    assertEquals(request + 1, result);

    // The binlog interceptor must operate on binary data (InputStream)
    assertThat(binaryMethods).hasSize(1);
    assertSame(BinaryLogProvider.IDENTITY_MARSHALLER, binaryMethods.get(0).getRequestMarshaller());
    assertSame(BinaryLogProvider.IDENTITY_MARSHALLER, binaryMethods.get(0).getResponseMarshaller());

    // The user supplied interceptor must still operate on the original message types
    assertThat(methods).hasSize(1);
    assertSame(serverReqMarshaller, methods.get(0).getRequestMarshaller());
    assertSame(serverRespMarshaller, methods.get(0).getResponseMarshaller());

    // The original marshallers should have only been invoked once each
    assertEquals(1, serverReqMarshaller.parseInvocations);
    assertEquals(0, serverReqMarshaller.streamInvocations);
    assertEquals(0, serverRespMarshaller.parseInvocations);
    assertEquals(1, serverRespMarshaller.streamInvocations);

    // The binlog interceptor must be closest to the transport
    assertThat(req).hasSize(2);
    assertThat(req.get(0)).isInstanceOf(InputStream.class);
    assertEquals(request, req.get(1));

    assertThat(resp).hasSize(2);
    assertEquals(result, resp.get(0));
    assertThat(resp.get(1)).isInstanceOf(InputStream.class);
  }

  /**
   * A server interceptor that logs tracing information when events pass through it.
   */
  private static final class TracingServerInterceptor implements ServerInterceptor {
    private final List<Object> req;
    private final List<Object> resp;
    private final List<MethodDescriptor<?, ?>> methods;

    TracingServerInterceptor(
        List<Object> req, List<Object> resp, List<MethodDescriptor<?, ?>> methods) {
      this.req = req;
      this.resp = resp;
      this.methods = methods;
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        final ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
      methods.add(call.getMethodDescriptor());
      ForwardingServerCall<ReqT, RespT> wCall = new ForwardingServerCall<ReqT, RespT>() {
        @Override
        public void sendMessage(RespT message) {
          resp.add(message);
          super.sendMessage(message);
        }

        @Override
        public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
          return call.getMethodDescriptor();
        }

        @Override
        protected ServerCall<ReqT, RespT> delegate() {
          return call;
        }
      };
      final Listener<ReqT> oListener = next.startCall(wCall, headers);
      return new ForwardingServerCallListener<ReqT>() {
        @Override
        public void onMessage(ReqT message) {
          req.add(message);
          super.onMessage(message);
        }

        @Override
        protected Listener<ReqT> delegate() {
          return oListener;
        }
      };
    }
  }

  /**
   * A provider that returns tracing interceptors, designed for testing.
   */
  private static final class TestBinaryLogProvider extends BinaryLogProvider {
    private final ServerInterceptor serverInterceptor;
    private final ClientInterceptor clientInterceptor = null;

    TestBinaryLogProvider(
        List<Object> incoming, List<Object> outgoing, List<MethodDescriptor<?, ?>> methods) {
      serverInterceptor = new TracingServerInterceptor(incoming, outgoing, methods);
    }

    @Override
    public ServerInterceptor getServerInterceptor(String fullMethodName) {
      return serverInterceptor;
    }

    @Nullable
    @Override
    public ClientInterceptor getClientInterceptor(String fullMethodName) {
      return clientInterceptor;
    }

    @Override
    protected int priority() {
      return 0;
    }
  }

  public static final class Provider0 extends BaseProvider {
    public Provider0() {
      super(0);
    }
  }

  public static final class Provider5 extends BaseProvider {
    public Provider5() {
      super(5);
    }
  }

  public static final class Provider7 extends BaseProvider {
    public Provider7() {
      super(7);
    }
  }

  public static class BaseProvider extends BinaryLogProvider {
    private int priority;

    BaseProvider(int priority) {
      this.priority = priority;
    }

    @Nullable
    @Override
    public ServerInterceptor getServerInterceptor(String fullMethodName) {
      throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public ClientInterceptor getClientInterceptor(String fullMethodName) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected int priority() {
      return priority;
    }
  }

  private final class TestBinaryLogClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        final MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      assertSame(BinaryLogProvider.IDENTITY_MARSHALLER, method.getRequestMarshaller());
      assertSame(BinaryLogProvider.IDENTITY_MARSHALLER, method.getResponseMarshaller());
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          delegate().start(
              new SimpleForwardingClientCallListener<RespT>(responseListener) {
                @Override
                public void onMessage(RespT message) {
                  assertTrue(message instanceof InputStream);
                  try {
                    byte[] bytes = IoUtils.toByteArray((InputStream) message);
                    binlogResp.add(bytes);
                    ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                    RespT dup = method.parseResponse(input);
                    assertSame(input, dup);
                    super.onMessage(dup);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
              },
              headers);
        }

        @Override
        public void sendMessage(ReqT message) {
          assertTrue(message instanceof InputStream);
          try {
            byte[] bytes = IoUtils.toByteArray((InputStream) message);
            binlogReq.add(bytes);
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            ReqT dup = method.parseRequest(input);
            assertSame(input, dup);
            super.sendMessage(dup);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      };
    }
  }
}
