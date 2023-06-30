/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls.BidiStreamingMethod;
import io.grpc.stub.ServerCallsTest.IntegerMarshaller;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BlockingBidiStreamTest {
  private static final Logger logger = Logger.getLogger(BlockingBidiStreamTest.class.getName());

  public static final int DELAY_MILLIS = 1000;
  public static final long DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS);
  private static final MethodDescriptor<Integer, Integer> BIDI_STREAMING_METHOD =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodType.BIDI_STREAMING)
          .setFullMethodName("some/method")
          .setRequestMarshaller(new IntegerMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();

  private Server server;

  private ManagedChannel channel;

  private IntegerTestMethod testMethod;
  private BlockingBiDiStream<Integer, Integer> biDiStream;

  @Before
  public void setUp() throws Exception {
    testMethod = new IntegerTestMethod();

    ServerServiceDefinition service = ServerServiceDefinition.builder(
            new ServiceDescriptor("some", BIDI_STREAMING_METHOD))
        .addMethod(BIDI_STREAMING_METHOD, ServerCalls.asyncBidiStreamingCall(testMethod))
        .build();
    long tag = System.nanoTime();

    server = InProcessServerBuilder.forName("go-with-the-flow" + tag).directExecutor()
        .addService(service).build().start();

    channel = InProcessChannelBuilder.forName("go-with-the-flow" + tag).directExecutor().build();
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdownNow();
    }
    if (channel != null) {
      channel.shutdownNow();
    }
    if (biDiStream != null) {
      biDiStream.cancel("In teardown", null);
    }
  }

  @Test
  public void sanityTest() throws Exception {
    Integer req = 2;
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    //  verify activity ready
    assertTrue(biDiStream.isEitherReadOrWriteReady());
    assertTrue(biDiStream.isWriteReady());

    // Have server send a value
    testMethod.sendValueToClient(10);

    // Do a writeOrRead
    biDiStream.write(req, 3, TimeUnit.SECONDS);
    assertEquals(Integer.valueOf(10), biDiStream.read(DELAY_MILLIS, TimeUnit.MILLISECONDS));

    // mark complete
    biDiStream.halfClose();
    assertNull(biDiStream.read(2, TimeUnit.SECONDS));

    // verify activity !ready and !writeable
    assertFalse(biDiStream.isEitherReadOrWriteReady());
    assertFalse(biDiStream.isWriteReady());

    assertEquals(Code.OK, biDiStream.getClosedStatus().getCode());
  }

  @Test
  public void testReadSuccess_withoutBlocking() throws InterruptedException, TimeoutException {
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    // Have server push a value
    testMethod.sendValueToClient(11);

    long start = System.nanoTime();
    Integer value = biDiStream.read(100, TimeUnit.SECONDS);
    assertNotNull(value);
    long timeTaken = System.nanoTime() - start;
    assertThat(timeTaken).isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
  }

  @Test
  public void testReadSuccess_withBlocking() throws InterruptedException, TimeoutException {
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    try {
      biDiStream.read(1, TimeUnit.SECONDS);
      fail("Expected timeout");
    } catch (TimeoutException t) {
      // ignore
    }

    long start = System.nanoTime();
    delayedAddValue(DELAY_MILLIS, 12);
    assertNotNull(biDiStream.read(DELAY_MILLIS * 2, TimeUnit.MILLISECONDS));
    long timeTaken = System.nanoTime() - start;
    assertTrue(timeTaken > DELAY_NANOS && timeTaken < DELAY_NANOS * 2);

    start = System.nanoTime();
    Integer[] values = {13, 14, 15, 16};
    delayedAddValue(DELAY_MILLIS, values);
    for (Integer value : values) {
      Integer readValue = biDiStream.read(2, TimeUnit.SECONDS);
      assertEquals(value, readValue);
    }
    timeTaken = System.nanoTime() - start;
    assertThat(timeTaken).isLessThan(DELAY_NANOS * 2);
    assertThat(timeTaken).isAtLeast(DELAY_NANOS);

    start = System.nanoTime();
    delayedVoidMethod(100, testMethod::halfClose);
    assertNull(biDiStream.read(DELAY_MILLIS * 2, TimeUnit.MILLISECONDS));
    timeTaken = System.nanoTime() - start;
    assertThat(timeTaken).isLessThan(DELAY_NANOS);
  }

  @Test
  public void testCancel() throws InterruptedException, TimeoutException {
    testMethod.disableAutoRequest();
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    // read terminated
    long start = System.currentTimeMillis();
    delayedCancel(biDiStream, "cancel read");
    try {
      assertNull(biDiStream.read(2, TimeUnit.SECONDS));
      fail("No exception thrown by read after cancel");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.CANCELLED.getCode(), e.getStatus().getCode());
      assertThat(System.currentTimeMillis() - start).isLessThan(2000);
    }

    // write terminated
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);
    start = System.currentTimeMillis();
    delayedCancel(biDiStream, "cancel write");

    // Write interrupted by cancel
    try {
      assertFalse(biDiStream.write(30)); // this is interrupted by cancel
    } catch (StatusRuntimeException e) {
      assertEquals(Status.CANCELLED.getCode(), e.getStatus().getCode());
    }

    // Write after cancel
    try {
      biDiStream.write(30);
      fail("No exception doing write after cancel");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).contains("cancel");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.CANCELLED.getCode(), e.getStatus().getCode());
      assertThat(System.currentTimeMillis() - start).isLessThan(2 * DELAY_MILLIS);
      assertThat(System.currentTimeMillis() - start).isAtLeast(DELAY_MILLIS);
    }

    // new read after cancel immediately throws an exception
    start = System.currentTimeMillis();
    try {
      assertNull(biDiStream.read(2, TimeUnit.SECONDS));
      assertThat(System.currentTimeMillis() - start).isLessThan(2000);
    } catch (StatusRuntimeException e) {
      assertEquals(Status.CANCELLED.getCode(), e.getStatus().getCode());
    }

  }

  @Test
  public void testIsActivityReady() throws InterruptedException, TimeoutException {
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    // write only ready
    assertTrue(biDiStream.isEitherReadOrWriteReady());
    assertTrue(biDiStream.isWriteReady());
    assertFalse(biDiStream.isReadReady());

    // both ready
    testMethod.sendValueToClient(40);
    assertTrue(biDiStream.isEitherReadOrWriteReady());
    assertTrue(biDiStream.isReadReady());
    assertTrue(biDiStream.isWriteReady());

    // read only ready
    biDiStream.halfClose();
    assertTrue(biDiStream.isEitherReadOrWriteReady());
    assertTrue(biDiStream.isReadReady());
    assertFalse(biDiStream.isWriteReady());

    // Neither ready
    assertNotNull(biDiStream.read(1, TimeUnit.MILLISECONDS));
    assertFalse(biDiStream.isEitherReadOrWriteReady());
    assertFalse(biDiStream.isReadReady());
    assertFalse(biDiStream.isWriteReady());
  }

  @Test
  public void testWriteSuccess_withBlocking() throws InterruptedException, TimeoutException {
    testMethod.disableAutoRequest();
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    assertFalse(biDiStream.isWriteReady());
    delayedWriteEnable(500);
    assertTrue(biDiStream.write(40));

    delayedWriteEnable(500);
    assertTrue(biDiStream.write(41, 0, TimeUnit.NANOSECONDS));
  }


  @Test
  public void testReadNonblocking_whenWriteBlocked() throws InterruptedException {
    testMethod.disableAutoRequest();
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    // One value waiting
    testMethod.sendValueToClient(50);
    long start = System.currentTimeMillis();
    assertEquals(Integer.valueOf(50), biDiStream.read());
    assertThat(System.currentTimeMillis() - start).isLessThan(1000L);

    // Two values waiting
    start = System.currentTimeMillis();
    testMethod.sendValuesToClient(51, 52);
    assertEquals(Integer.valueOf(51), biDiStream.read());
    assertEquals(Integer.valueOf(52), biDiStream.read());
    assertThat(System.currentTimeMillis() - start).isLessThan(1000L);
  }

  @Test
  public void testReadsAndWritesInterleaved_withBlocking()
      throws InterruptedException, TimeoutException {
    testMethod.disableAutoRequest();
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    Integer[] valuesOut = {1, 2, 3};
    Integer[] valuesIn = new Integer[valuesOut.length];
    delayedAddValue(300, valuesOut);
    for (int i = 0; i < valuesOut.length; ) {
      try {
        if ((valuesIn[i] = biDiStream.read(50, TimeUnit.MILLISECONDS)) != null) {
          i++;
        }
      } catch (TimeoutException e) {
        logger.info("Read timed out for " + i);
      }
    }
    assertArrayEquals(valuesOut, valuesIn);

    testMethod.sendValuesToClient(10, 11, 12);
    delayedWriteEnable(500);
    long start = System.currentTimeMillis();
    boolean done = false;
    int count = 0;
    while (!done) {
      count++;
      if (!biDiStream.isWriteReady() && biDiStream.isReadReady()) {
        biDiStream.read(100, TimeUnit.MILLISECONDS);
      } else {
        done = biDiStream.write(100, 1, TimeUnit.SECONDS);
      }
    }
    assertEquals(4, count);
    assertThat(System.currentTimeMillis() - start).isLessThan(700);

    testMethod.sendValuesToClient(20, 21, 22);
    delayedWriteEnable(100);
    while (!biDiStream.isWriteReady()) {
      Thread.sleep(20);
    }

    assertTrue(biDiStream.write(1000, 2, TimeUnit.SECONDS));

    assertEquals(Integer.valueOf(20), biDiStream.read(200, TimeUnit.MILLISECONDS));
    assertEquals(Integer.valueOf(21), biDiStream.read(200, TimeUnit.MILLISECONDS));
    assertEquals(Integer.valueOf(22), biDiStream.read(200, TimeUnit.MILLISECONDS));
    try {
      Integer value = biDiStream.read(200, TimeUnit.MILLISECONDS);
      fail("Unexpected read success instead of timeout.  Value was: " + value);
    } catch (TimeoutException e) {
      // ignore since expected
    }
  }

  @Test
  public void testWriteCompleted() throws InterruptedException, TimeoutException {
    testMethod.disableAutoRequest();
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    // Verify pending write released
    long start = System.currentTimeMillis();
    delayedVoidMethod(DELAY_MILLIS, biDiStream::halfClose);
    assertFalse(biDiStream.write(1)); // should block until writeComplete is triggered
    long end = System.currentTimeMillis();
    assertThat(end - start).isAtLeast(DELAY_MILLIS);

    // verify new writes throw an illegalStateException
    try {
      assertFalse(biDiStream.write(2));
      fail("write did not throw an exception when called after halfClose");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).containsMatch("after.*halfClose.*cancel");
    }

    // verify pending writeOrRead released
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);
    delayedVoidMethod(DELAY_MILLIS, biDiStream::halfClose);
    assertFalse(biDiStream.write(3, 2, TimeUnit.SECONDS));
  }

  @Test
  public void testClose_withException() throws InterruptedException {
    biDiStream = ClientCalls.blockingBidiStreamingCall(channel,  BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    String descr = "too many small numbers";
    testMethod.sendError(
        Status.FAILED_PRECONDITION.withDescription(descr).asRuntimeException());
    Status closedStatus = biDiStream.getClosedStatus();
    assertEquals(Code.FAILED_PRECONDITION, closedStatus.getCode());
    assertEquals(descr, closedStatus.getDescription());
    try {
      assertFalse(biDiStream.write(1));
    } catch (StatusRuntimeException e) {
      assertThat(e.getMessage()).startsWith("FAILED_PRECONDITION");
    }
  }

  private void delayedAddValue(int delayMillis, Integer... values) {
    new Thread("delayedAddValue " + values.length) {
      @Override
      public void run() {
        try {
          Thread.sleep(delayMillis);
          for (Integer cur : values) {
            testMethod.sendValueToClient(cur);
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }.start();
  }

  public interface Thunk { void apply(); } // supports passing void method w/out args

  private void delayedVoidMethod(int delayMillis, Thunk method) {
    new Thread("delayedHalfClose") {
      @Override
      public void run() {
        try {
          Thread.sleep(delayMillis);
          method.apply();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }.start();
  }

  private void delayedWriteEnable(int delayMillis) {
    delayedVoidMethod(delayMillis, testMethod::readValueFromClient);
  }

  private void delayedCancel(BlockingBiDiStream<Integer, Integer> biDiStream, String message) {
    new Thread("delayedCancel") {
      @Override
      public void run() {
        try {
          Thread.sleep(BlockingBidiStreamTest.DELAY_MILLIS);
          biDiStream.cancel(message, new RuntimeException("Test requested close"));
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }.start();
  }

  private static class IntegerTestMethod implements BidiStreamingMethod<Integer, Integer> {
    boolean autoRequest = true;

    void disableAutoRequest() {
      assertNull("Can't disable auto request after invoke has been called", serverCallObserver);
      autoRequest = false;
    }

    ServerCallStreamObserver<Integer> serverCallObserver;

    @Override
    public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
      serverCallObserver = (ServerCallStreamObserver<Integer>) responseObserver;
      if (!autoRequest) {
        serverCallObserver.disableAutoRequest();
      }

      return new StreamObserver<Integer>() {
        @Override
        public void onNext(Integer value) {
          if (!autoRequest) {
            serverCallObserver.request(1);
          }

          // For testing ReqResp actions
          if (value > 1000) {
            serverCallObserver.onNext(value);
          }
        }

        @Override
        public void onError(Throwable t) {
          // no-op
        }

        @Override
        public void onCompleted() {
          serverCallObserver.onCompleted();
        }
      };
    }

    void readValueFromClient() {
      serverCallObserver.request(1);
    }

    void sendValueToClient(int value) {
      serverCallObserver.onNext(value);
    }

    private void sendValuesToClient(int ...values) {
      for (int cur : values) {
        sendValueToClient(cur);
      }
    }

    void halfClose() {
      serverCallObserver.onCompleted();
    }

    void sendError(Throwable t) {
      serverCallObserver.onError(t);
    }
  }

}
