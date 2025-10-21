/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Codec;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.testing.integration.Messages.BoolValue;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that compression is turned on.
 */
@RunWith(JUnit4.class)
public class TransportCompressionTest extends AbstractInteropTest {

  // Masquerade as identity.
  private static final Fzip FZIPPER = new Fzip("gzip", new Codec.Gzip());
  private volatile boolean expectFzip;

  private static final DecompressorRegistry decompressors = DecompressorRegistry.emptyInstance()
      .with(Codec.Identity.NONE, false)
      .with(FZIPPER, true);
  private static final CompressorRegistry compressors = CompressorRegistry.newEmptyInstance();

  @Before
  public void beforeTests() {
    FZIPPER.anyRead = false;
    FZIPPER.anyWritten = false;
  }

  @BeforeClass
  public static void registerCompressors() {
    compressors.register(FZIPPER);
    compressors.register(Codec.Identity.NONE);
  }

  @Rule
  public final TestName currentTest = new TestName();

  @Override
  protected ServerBuilder<?> getServerBuilder() {
    NettyServerBuilder builder = NettyServerBuilder.forPort(0, InsecureServerCredentials.create())
        .maxInboundMessageSize(
            DECOMPRESSED_MESSAGE_TOO_LONG_METHOD_NAME.equals(currentTest.getMethodName())
                ? 1000
                : AbstractInteropTest.MAX_MESSAGE_SIZE)
        .compressorRegistry(compressors)
        .decompressorRegistry(decompressors)
        .intercept(new ServerInterceptor() {
            @Override
            public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                Metadata headers, ServerCallHandler<ReqT, RespT> next) {
              Listener<ReqT> listener = next.startCall(call, headers);
              // TODO(carl-mastrangelo): check that encoding was set.
              call.setMessageCompression(true);
              return listener;
            }
          });
    // Disable the default census stats tracer, use testing tracer instead.
    InternalNettyServerBuilder.setStatsEnabled(builder, false);
    return builder.addStreamTracerFactory(createCustomCensusTracerFactory());
  }

  @Test
  public void compresses() {
    expectFzip = true;
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(314159)
        .setResponseCompressed(BoolValue.newBuilder().setValue(true))
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[314159])))
        .build();


    assertEquals(goldenResponse, blockingStub.unaryCall(request));
    // Assert that compression took place
    assertTrue(FZIPPER.anyRead);
    assertTrue(FZIPPER.anyWritten);
  }

  private static final String DECOMPRESSED_MESSAGE_TOO_LONG_METHOD_NAME =
      "decompressedMessageTooLong";

  @Test
  @Ignore("for PR #12360")
  public void decompressedMessageTooLong() {
    assertEquals(DECOMPRESSED_MESSAGE_TOO_LONG_METHOD_NAME, currentTest.getMethodName());
    final SimpleRequest bigRequest = SimpleRequest.newBuilder()
        .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[10_000])))
        .build();
    StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
        () -> blockingStub.withCompression("gzip").unaryCall(bigRequest));
    assertCodeEquals(Code.RESOURCE_EXHAUSTED, e.getStatus());
    assertEquals("Decompressed gRPC message exceeds maximum size 1000",
        e.getStatus().getDescription());
  }

  @Override
  protected NettyChannelBuilder createChannelBuilder() {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(getListenAddress())
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .decompressorRegistry(decompressors)
        .compressorRegistry(compressors)
        .intercept(new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            final ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall<ReqT, RespT>() {

              @Override
              protected ClientCall<ReqT, RespT> delegate() {
                return call;
              }

              @Override
              public void start(
                  final ClientCall.Listener<RespT> responseListener, Metadata headers) {
                ClientCall.Listener<RespT> listener = new ForwardingClientCallListener<RespT>() {

                  @Override
                  protected io.grpc.ClientCall.Listener<RespT> delegate() {
                    return responseListener;
                  }

                  @Override
                  public void onHeaders(Metadata headers) {
                    super.onHeaders(headers);
                    if (expectFzip) {
                      String encoding = headers.get(GrpcUtil.MESSAGE_ENCODING_KEY);
                      assertEquals(encoding, FZIPPER.getMessageEncoding());
                    }
                  }
                };
                super.start(listener, headers);
                setMessageCompression(true);
              }
            };
          }
        })
        .usePlaintext();
    // Disable the default census stats interceptor, use testing interceptor instead.
    InternalNettyChannelBuilder.setStatsEnabled(builder, false);
    return builder.intercept(createCensusStatsClientInterceptor());
  }

  /**
   * Fzip is a custom compressor.
   */
  static class Fzip implements Codec {
    volatile boolean anyRead;
    volatile boolean anyWritten;
    volatile Codec delegate;

    private final String actualName;

    public Fzip(String actualName, Codec delegate) {
      this.actualName = actualName;
      this.delegate = delegate;
    }

    @Override
    public String getMessageEncoding() {
      return actualName;
    }

    @Override
    public OutputStream compress(OutputStream os) throws IOException {
      return new FilterOutputStream(delegate.compress(os)) {
        @Override
        public void write(int b) throws IOException {
          super.write(b);
          anyWritten = true;
        }
      };
    }

    @Override
    public InputStream decompress(InputStream is) throws IOException {
      return new FilterInputStream(delegate.decompress(is)) {
        @Override
        public int read() throws IOException {
          int val = super.read();
          anyRead = true;
          return val;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          int total = super.read(b, off, len);
          anyRead = true;
          return total;
        }
      };
    }
  }
}
