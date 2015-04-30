/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;

import io.grpc.ChannelImpl;
import io.grpc.ServerImpl;
import io.grpc.ServerInterceptors;
import io.grpc.stub.StreamObserver;
import io.grpc.stub.StreamRecorder;
import io.grpc.testing.TestUtils;
import io.grpc.transport.netty.NegotiationType;
import io.grpc.transport.netty.NettyChannelBuilder;
import io.grpc.transport.netty.NettyServerBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tests to verify IO efficiency of the Netty transport implementation.
 */
@RunWith(JUnit4.class)
public class NettyTransportIoEfficiencyTest {

  private static final Messages.SimpleRequest SMALL_REQUEST = Messages.SimpleRequest.newBuilder()
      .setResponseSize(1)
      .setResponseType(Messages.PayloadType.COMPRESSABLE)
      .setPayload(Messages.Payload.newBuilder()
          .setBody(ByteString.copyFrom(new byte[1])))
      .build();
  private static int serverPort = Util.pickUnusedPort();
  private ServerImpl server;
  protected ChannelImpl channel;
  protected TestServiceGrpc.TestServiceBlockingStub blockingStub;
  protected TestServiceGrpc.TestService asyncStub;

  @After
  public void tearDown() throws Exception {
    ClientCountingChannel.flushCount = 0;
    ServerCountingChannel.flushCount = 0;
    server.shutdownNow();
    channel.shutdownNow();
  }

  @Test(timeout = 10000)
  public void smallUnaryBlocking() throws Exception {
    startServer(false);
    startClient(false);
    blockingStub.unaryCall(SMALL_REQUEST);
    // Expect 1 flush each from client and server as they know the call is unary and so group
    // header, payload & trailers (server) into a single flush.
    assertEquals(1, ClientCountingChannel.flushCount);
    assertEquals(1, ServerCountingChannel.flushCount);
  }

  @Test(timeout = 10000)
  public void largeUnaryWithWindowUpdate() throws Exception {
    startServer(false);
    startClient(false);
    // Use payload slightly greater than a multiple of the window size.
    int payloadSize = 65535 * 5 + 10;
    int payloadWrites = 6;
    final Messages.SimpleRequest request = Messages.SimpleRequest.newBuilder()
        .setResponseSize(payloadSize)
        .setResponseType(Messages.PayloadType.COMPRESSABLE)
        .setPayload(Messages.Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[payloadSize])))
        .build();
    blockingStub.unaryCall(request);
    int maxWindowUpdates = (payloadSize / (65535 / 2));
    int minWindowUpdates = (payloadSize / 65535);
    // We use bounds checking as we don't have precisely predictable behavior here. The number
    // of DATA frames read in a unit depends on how big a chunk we get from the IO layer.
    assertTrue(maxWindowUpdates + payloadWrites >= ClientCountingChannel.flushCount);
    assertTrue(minWindowUpdates + payloadWrites <= ClientCountingChannel.flushCount);
    assertTrue(maxWindowUpdates + payloadWrites >= ServerCountingChannel.flushCount);
    assertTrue(minWindowUpdates + payloadWrites <= ServerCountingChannel.flushCount);
  }

  @Test(timeout = 10000)
  public void largeUnaryWithWindowUpdateAndDirectExecutor() throws Exception {
    System.err.println("START");
    try {
      startServer(true);
      startClient(true);
      // Use payload slightly greater than a multiple of the window size.
      int payloadSize = 65535 * 5 + 10;
      int payloadWrites = 6;
      final Messages.SimpleRequest request = Messages.SimpleRequest.newBuilder()
          .setResponseSize(payloadSize)
          .setResponseType(Messages.PayloadType.COMPRESSABLE)
          .setPayload(Messages.Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[payloadSize])))
          .build();
      blockingStub.unaryCall(request);
      int maxWindowUpdates = (payloadSize / (65535 / 2));
      int minWindowUpdates = (payloadSize / 65535);
      // No benefit for unary calls over non-direct executor here as deframer is already running in
      // transport thread so returning bytes to the flow controller causes window-updates to be
      // coalesced by the flush in read-complete.
      assertTrue(maxWindowUpdates + payloadWrites >= ClientCountingChannel.flushCount);
      assertTrue(minWindowUpdates + payloadWrites <= ClientCountingChannel.flushCount);
      assertTrue(maxWindowUpdates + payloadWrites >= ServerCountingChannel.flushCount);
      assertTrue(minWindowUpdates + payloadWrites <= ServerCountingChannel.flushCount);
    } finally {
      System.err.println("END");
    }
  }

  @Test(timeout = 10000)
  public void smallServerStreaming() throws Exception {
    startServer(false);
    startClient(false);
    Messages.ResponseParameters.Builder smallParam = Messages.ResponseParameters.newBuilder()
        .setSize(1)
        .setIntervalUs(0);
    final Messages.StreamingOutputCallRequest request =
        Messages.StreamingOutputCallRequest.newBuilder()
        .setResponseType(Messages.PayloadType.COMPRESSABLE)
        .addResponseParameters(smallParam)
        .addResponseParameters(smallParam)
        .addResponseParameters(smallParam)
        .addResponseParameters(smallParam)
        .build();
    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertEquals(1, ClientCountingChannel.flushCount);
    // 1 flush for headers, 1 for each message & 1 for trailers
    assertEquals(6, ServerCountingChannel.flushCount);
  }

  @Test(timeout = 10000)
  public void smallServerStreamingDirectExecutor() throws Exception {
    startServer(true);
    startClient(true);
    Messages.ResponseParameters.Builder smallParam = Messages.ResponseParameters.newBuilder()
        .setSize(1)
        .setIntervalUs(0);
    final Messages.StreamingOutputCallRequest request =
        Messages.StreamingOutputCallRequest.newBuilder()
            .setResponseType(Messages.PayloadType.COMPRESSABLE)
            .addResponseParameters(smallParam)
            .addResponseParameters(smallParam)
            .addResponseParameters(smallParam)
            .addResponseParameters(smallParam)
            .build();
    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertEquals(1, ClientCountingChannel.flushCount);
    // Using direct executor causes all the writes that the server streams back to be coalesced
    // into a single flush.
    assertEquals(1, ServerCountingChannel.flushCount);
  }

  @Test(timeout = 10000)
  public void largeServerStreaming() throws Exception {
    startServer(false);
    startClient(false);
    Messages.ResponseParameters.Builder largeParam = Messages.ResponseParameters.newBuilder()
        .setSize(65535)
        .setIntervalUs(0);
    final Messages.StreamingOutputCallRequest request =
        Messages.StreamingOutputCallRequest.newBuilder()
            .setResponseType(Messages.PayloadType.COMPRESSABLE)
            .addResponseParameters(largeParam)
            .addResponseParameters(largeParam)
            .addResponseParameters(largeParam)
            .addResponseParameters(largeParam)
            .build();
    int payloadSize = 65535 * 5 + 10;
    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    int maxWindowUpdates = (payloadSize / (65535 / 2));
    int minWindowUpdates = (payloadSize / 65535);
    // 1 for request & 5 window updates back to server.
    assertEquals(6, ClientCountingChannel.flushCount);

    // 1 flush for headers and a min/max range for window updates
    assertTrue(maxWindowUpdates + 1 >= ServerCountingChannel.flushCount);
    assertTrue(minWindowUpdates + 1 <= ServerCountingChannel.flushCount);
  }

  @Test(timeout = 10000)
  public void smallClientStreaming() throws Exception {
    startServer(false);
    startClient(false);
    Messages.StreamingInputCallRequest smallPayload =
        Messages.StreamingInputCallRequest.newBuilder()
        .setPayload(Messages.Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[1])))
        .build();
    final List<Messages.StreamingInputCallRequest> requests = Arrays.asList(
        smallPayload, smallPayload, smallPayload, smallPayload);
    StreamRecorder<Messages.StreamingInputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(recorder);
    for (Messages.StreamingInputCallRequest request : requests) {
      requestObserver.onValue(request);
    }
    requestObserver.onCompleted();
    recorder.awaitCompletion();

    // 1 flush for headers, 1 for each each message & 1 for trailing empty DATA.
    assertEquals(6, ClientCountingChannel.flushCount);
    // BROKEN: Should be 1 but because we don't differentiate between unary and streaming responses
    // in generated stubs, they both use ServerCalls.asyncStreamingCall which causes us to
    // make an unneeded call to call.request for the unary response case which triggers a flush.
    assertEquals(2, ServerCountingChannel.flushCount);
  }

  private void startClient(boolean directExecutor) {
    try {
      NettyChannelBuilder builder = NettyChannelBuilder
          .forAddress(new InetSocketAddress(InetAddress.getLocalHost(), serverPort))
          .channelType(ClientCountingChannel.class)
          .negotiationType(NegotiationType.PLAINTEXT);
      if (directExecutor) {
        builder.executor(MoreExecutors.newDirectExecutorService());
      }
      channel = builder.build();
      blockingStub = TestServiceGrpc.newBlockingStub(channel);
      asyncStub  = TestServiceGrpc.newStub(channel);
      blockingStub.unaryCall(SMALL_REQUEST);
      // Need to sleep so that SETTINGS_ACK is received and processed as it can cause a flush.
      Thread.sleep(1000);
      ClientCountingChannel.flushCount = 0;
      ServerCountingChannel.flushCount = 0;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void startServer(boolean directExecutor)
      throws Exception {
    NettyServerBuilder builder = NettyServerBuilder.forPort(serverPort)
        .channelType(ServerSocketChannel.class);
    ExecutorService testServiceExecutor;
    if (directExecutor) {
      builder.executor(MoreExecutors.newDirectExecutorService());
      testServiceExecutor = MoreExecutors.newDirectExecutorService();
    } else {
      testServiceExecutor = Executors.newScheduledThreadPool(2);
    }
    builder.addService(ServerInterceptors.intercept(
        TestServiceGrpc.bindService(new TestServiceImpl(testServiceExecutor)),
        TestUtils.echoRequestHeadersInterceptor(Util.METADATA_KEY)));
    server = builder.build();
    server.start();
  }


  public static class ClientCountingChannel extends NioSocketChannel {

    public static volatile int flushCount = 0;

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
      flushCount++;
      super.doWrite(in);
      Thread.dumpStack();
    }
  }

  public static class ServerCountingChannel extends NioSocketChannel {
    public static volatile int flushCount = 0;

    public ServerCountingChannel(Channel parent, SocketChannel socket) {
      super(parent, socket);
    }


    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
      flushCount++;
      super.doWrite(in);
    }
  }



  public static class ServerSocketChannel extends NioServerSocketChannel {
    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
      java.nio.channels.SocketChannel ch = javaChannel().accept();
      try {
        if (ch != null) {
          buf.add(new ServerCountingChannel(this, ch));
          return 1;
        }
      } catch (Throwable t) {
        try {
          ch.close();
        } catch (Throwable t2) {
          // Ignore
        }
      }
      return 0;
    }
  }
}
