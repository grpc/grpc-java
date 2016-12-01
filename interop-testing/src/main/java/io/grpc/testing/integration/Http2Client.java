/*
 * Copyright 2014, Google Inc. All rights reserved.
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

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.testing.StreamRecorder;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.PayloadType;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.List;

/**
 * Client application for the {@link TestServiceGrpc.TestServiceImplBase} that runs through a series
 * of HTTP/2 interop tests. The tests are designed to simulate incorrect behavior on the part of the
 * server. Some of the test cases require server-side checks and do not have assertions within the
 * client code.
 */
public class Http2Client {

  /**
   * The main application allowing this client to be launched from the command line.
   */
  public static void main(String[] args) throws Exception {
    final Http2Client client = new Http2Client();
    client.parseArgs(args);
    client.setUp();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        try {
          client.tearDown();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });

    try {
      client.run();
    } finally {
      client.tearDown();
    }
    System.exit(0);
  }

  private String serverHost = "localhost";
  private int serverPort = 8080;
  private String testCase = "rst_stream_after_header";

  private Tester tester = new Tester();

  protected ManagedChannel channel;
  protected TestServiceGrpc.TestServiceBlockingStub blockingStub;
  protected TestServiceGrpc.TestServiceStub asyncStub;

  private void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("server_host".equals(key)) {
        serverHost = value;
      } else if ("server_port".equals(key)) {
        serverPort = Integer.parseInt(value);
      } else if ("test_case".equals(key)) {
        testCase = value;
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      Http2Client c = new Http2Client();
      System.out.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --server_host=HOST          Server to connect to. Default " + c.serverHost
              + "\n  --server_port=PORT          Port to connect to. Default " + c.serverPort
              + "\n  --test_case=TESTCASE        Test case to run. Default " + c.testCase
              + "\n    Valid options:"
              + validTestCasesHelpText()
      );
      System.exit(1);
    }
  }

  private void setUp() {
    channel = createChannel();
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
    asyncStub = TestServiceGrpc.newStub(channel);
  }

  private synchronized void tearDown() {
    try {
      if (channel != null) {
        channel.shutdown();
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void run() {
    System.out.println("Running test " + testCase);
    try {
      runTest(Http2TestCases.fromString(testCase));
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    System.out.println("Test completed.");
  }

  private void runTest(Http2TestCases testCase) throws Exception {
    switch (testCase) {
      case RST_AFTER_HEADER:
        tester.rstStreamAfterHeader();
        break;
      case RST_AFTER_DATA:
        tester.rstStreamAfterData();
        break;
      case RST_DURING_DATA:
        tester.rstStreamDuringData();
        break;
      case GOAWAY:
        tester.goAway();
        break;
      case PING:
        tester.ping();
        break;
      case MAX_STREAMS:
        tester.maxStreams();
        break;
      default:
        throw new IllegalArgumentException("Unknown test case: " + testCase);
    }
  }

  private class Tester {
    private final SimpleRequest simpleRequest = SimpleRequest.newBuilder()
        .setResponseSize(314159)
        .setResponseType(PayloadType.COMPRESSABLE)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();

    public void rstStreamAfterHeader() throws Exception {
      try {
        blockingStub.unaryCall(simpleRequest);
        fail("Expected call to fail");
      } catch (StatusRuntimeException ex) {
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
      }
    }

    public void rstStreamAfterData() throws Exception {
      try {
        blockingStub.unaryCall(simpleRequest);
        fail("Expected call to fail");
      } catch (StatusRuntimeException ex) {
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
      }

      // Use async stub to verify data was received.
      StreamRecorder<SimpleResponse> responseObserver = StreamRecorder.create();
      asyncStub.unaryCall(simpleRequest, responseObserver);
      responseObserver.awaitCompletion();
      assertEquals(1, responseObserver.getValues().size());
      assertEquals(Status.Code.UNAVAILABLE,
          Status.fromThrowable(responseObserver.getError()).getCode());
    }

    public void rstStreamDuringData() throws Exception {
      try {
        blockingStub.unaryCall(simpleRequest);
        fail("Expected call to fail");
      } catch (StatusRuntimeException ex) {
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
      }

      // Use async stub to verify data was not received.
      StreamRecorder<SimpleResponse> responseObserver = StreamRecorder.create();
      asyncStub.unaryCall(simpleRequest, responseObserver);
      responseObserver.awaitCompletion();
      assertEquals(0, responseObserver.getValues().size());
      assertEquals(Status.Code.UNAVAILABLE,
          Status.fromThrowable(responseObserver.getError()).getCode());
    }

    public void goAway() throws Exception {
      blockingStub.unaryCall(simpleRequest);
      blockingStub.unaryCall(simpleRequest);
    }

    public void ping() throws Exception {
      blockingStub.unaryCall(simpleRequest);
    }

    public void maxStreams() throws Exception {
      final int numThreads = 10;

      // Preliminary call to ensure MAX_STREAMS setting is received by the client.
      blockingStub.unaryCall(simpleRequest);

      ListeningExecutorService threadpool =
          MoreExecutors.listeningDecorator(newFixedThreadPool(numThreads));
      List<ListenableFuture<?>> workerFutures = new ArrayList<ListenableFuture<?>>();
      ManagedChannel channel = createChannel();
      for (int i = 0; i < numThreads; i++) {
        workerFutures.add(threadpool.submit(new MaxStreamsWorker(channel, i, simpleRequest)));
      }
      ListenableFuture<?> f = Futures.allAsList(workerFutures);
      f.get();
    }

    private class MaxStreamsWorker implements Runnable {
      ManagedChannel channel;
      int stubNum;
      SimpleRequest request;

      MaxStreamsWorker(ManagedChannel channel, int stubNum, SimpleRequest request) {
        this.channel = channel;
        this.stubNum = stubNum;
        this.request = request;
      }

      @Override
      public void run() {
        Thread.currentThread().setName("thread:" + stubNum);
        try {
          TestServiceGrpc.TestServiceBlockingStub blockingStub =
              TestServiceGrpc.newBlockingStub(channel);
          blockingStub.unaryCall(request);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private ManagedChannel createChannel() {
    InetAddress address;
    try {
      address = InetAddress.getByName(serverHost);
    } catch (UnknownHostException ex) {
      throw new RuntimeException(ex);
    }
    return NettyChannelBuilder.forAddress(new InetSocketAddress(address, serverPort))
        .flowControlWindow(65 * 1024)
        .negotiationType(NegotiationType.PLAINTEXT)
        .build();
  }

  private static String validTestCasesHelpText() {
    StringBuilder builder = new StringBuilder();
    for (Http2TestCases testCase : Http2TestCases.values()) {
      String strTestcase = testCase.name().toLowerCase();
      builder.append("\n      ")
          .append(strTestcase)
          .append(": ")
          .append(testCase.description());
    }
    return builder.toString();
  }
}

