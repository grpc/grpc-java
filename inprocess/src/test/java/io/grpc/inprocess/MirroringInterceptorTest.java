/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.inprocess;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MirroringInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class MirroringInterceptorTest {
  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public java.io.InputStream stream(String value) {
          return new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(java.io.InputStream stream) {
          return "response";
        }
      };

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test/Method")
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .build();

  // ─── Helper to build a simple auto-closing server ───────────────────────────

  private String buildAutoCloseServer(CountDownLatch latch, AtomicBoolean headerVerified,
      Metadata.Key<String> key, String expectedValue) throws Exception {
    String name = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder("test")
                    .addMethod(method, (call, headers) -> {
                      if (key != null && expectedValue.equals(headers.get(key))) {
                        headerVerified.set(true);
                      }
                      if (latch != null) {
                        latch.countDown();
                      }
                      call.sendHeaders(new Metadata());
                      call.close(Status.OK, new Metadata());
                      return new ServerCall.Listener<String>() {};
                    })
                    .build())
            .build()
            .start());
    return name;
  }

  // ─── Test 1: Unary call is mirrored with headers ────────────────────────────

  @Test
  public void unaryCallIsMirroredWithHeaders() throws Exception {
    CountDownLatch mirrorLatch = new CountDownLatch(1);
    Metadata.Key<String> testKey =
        Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER);
    AtomicBoolean mirrorHeaderVerified = new AtomicBoolean(false);

    String mirrorName = buildAutoCloseServer(mirrorLatch, mirrorHeaderVerified,
        testKey, "shadow-value");
    String primaryName = buildAutoCloseServer(null, null, null, "");

    ManagedChannel mirrorChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(mirrorName).build());
    ManagedChannel primaryChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(primaryName).build());

    Channel interceptedChannel = ClientInterceptors.intercept(
        primaryChannel, new MirroringInterceptor(mirrorChannel, Runnable::run));

    Metadata headers = new Metadata();
    headers.put(testKey, "shadow-value");

    ClientCall<String, String> call = interceptedChannel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {}, headers);
    call.sendMessage("hello");
    call.halfClose();

    assertTrue("Mirror server was not reached", mirrorLatch.await(1, TimeUnit.SECONDS));
    assertTrue("Headers not mirrored", mirrorHeaderVerified.get());
  }

  // ─── Test 2: Cancel is propagated to mirror ──────────────────────────────────

  @Test
  public void cancelIsPropagatedToMirror() throws Exception {
    CountDownLatch mirrorStartLatch = new CountDownLatch(1);
    AtomicBoolean mirrorCancelSeen = new AtomicBoolean(false);

    String mirrorName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(mirrorName)
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder("test")
                    .addMethod(method, (call, headers) -> {
                      mirrorStartLatch.countDown();
                      call.sendHeaders(new Metadata());
                      return new ServerCall.Listener<String>() {
                        @Override
                        public void onCancel() {
                          mirrorCancelSeen.set(true);
                        }
                      };
                    })
                    .build())
            .build()
            .start());

    String primaryName = buildAutoCloseServer(null, null, null, "");

    ManagedChannel mirrorChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(mirrorName).build());
    ManagedChannel primaryChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(primaryName).build());

    Channel interceptedChannel = ClientInterceptors.intercept(
        primaryChannel, new MirroringInterceptor(mirrorChannel, Runnable::run));

    ClientCall<String, String> call =
        interceptedChannel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertTrue("Mirror call never started", mirrorStartLatch.await(1, TimeUnit.SECONDS));

    // Now cancel — should propagate to mirror
    call.cancel("test cancel", null);

    // Give mirror time to process cancel
    Thread.sleep(200);
    assertTrue("Cancel was not propagated to mirror", mirrorCancelSeen.get());
  }

  // ─── Test 3: Multiple messages are all mirrored ──────────────────────────────

  @Test
  public void multipleMessagesAreMirrored() throws Exception {
    AtomicInteger mirrorMessageCount = new AtomicInteger(0);
    CountDownLatch halfCloseLatch = new CountDownLatch(1);

    String mirrorName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(mirrorName)
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder("test")
                    .addMethod(method, (call, headers) -> {
                      call.sendHeaders(new Metadata());
                      call.request(10);
                      return new ServerCall.Listener<String>() {
                        @Override
                        public void onMessage(String message) {
                          mirrorMessageCount.incrementAndGet();
                        }

                        @Override
                        public void onHalfClose() {
                          halfCloseLatch.countDown();
                          call.close(Status.OK, new Metadata());
                        }
                      };
                    })
                    .build())
            .build()
            .start());

    String primaryName = buildAutoCloseServer(null, null, null, "");

    ManagedChannel mirrorChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(mirrorName).build());
    ManagedChannel primaryChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(primaryName).build());

    Channel interceptedChannel = ClientInterceptors.intercept(
        primaryChannel, new MirroringInterceptor(mirrorChannel, Runnable::run));

    ClientCall<String, String> call =
        interceptedChannel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {}, new Metadata());
    call.request(1);
    call.sendMessage("msg1");
    call.sendMessage("msg2");
    call.sendMessage("msg3");
    call.halfClose();

    assertTrue("Mirror halfClose never received", halfCloseLatch.await(1, TimeUnit.SECONDS));
    assertTrue("Expected 3 mirrored messages, got: " + mirrorMessageCount.get(),
        mirrorMessageCount.get() >= 3);
  }

  // ─── Test 4: Null mirrorChannel throws NullPointerException ─────────────────

  @Test
  public void nullMirrorChannelThrowsException() {
    try {
      new MirroringInterceptor(null, Runnable::run);
      fail("Expected NullPointerException for null mirrorChannel");
    } catch (NullPointerException e) {
      assertNotNull(e);
    }
  }

  // ─── Test 5: Null executor throws NullPointerException ──────────────────────

  @Test
  public void nullExecutorThrowsException() throws Exception {
    String mirrorName = buildAutoCloseServer(null, null, null, "");
    ManagedChannel mirrorChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(mirrorName).build());
    try {
      new MirroringInterceptor(mirrorChannel, null);
      fail("Expected NullPointerException for null executor");
    } catch (NullPointerException e) {
      assertNotNull(e);
    }
  }

  // ─── Test 6: Mirror call failure is handled silently ────────────────────────

  @Test
  public void mirrorCallFailureDoesNotAffectPrimary() throws Exception {
    CountDownLatch primaryLatch = new CountDownLatch(1);

    String primaryName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(primaryName)
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder("test")
                    .addMethod(method, (call, headers) -> {
                      primaryLatch.countDown();
                      call.sendHeaders(new Metadata());
                      call.close(Status.OK, new Metadata());
                      return new ServerCall.Listener<String>() {};
                    })
                    .build())
            .build()
            .start());

    // Mirror channel points to non-existent server — will fail silently
    ManagedChannel brokenMirrorChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName("non-existent-server-xyz").build());
    ManagedChannel primaryChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(primaryName).build());

    Channel interceptedChannel = ClientInterceptors.intercept(
        primaryChannel, new MirroringInterceptor(brokenMirrorChannel, Runnable::run));

    ClientCall<String, String> call =
        interceptedChannel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {}, new Metadata());
    call.sendMessage("hello");
    call.halfClose();

    // Primary should still succeed even if mirror fails
    assertTrue("Primary call was affected by mirror failure",
        primaryLatch.await(1, TimeUnit.SECONDS));
  }
}