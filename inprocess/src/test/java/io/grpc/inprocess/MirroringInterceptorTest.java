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

import static org.junit.Assert.assertTrue;

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

  @Test
  public void unaryCallIsMirroredWithHeaders() throws Exception {
    CountDownLatch mirrorLatch = new CountDownLatch(1);
    Metadata.Key<String> testKey =
        Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER);
    AtomicBoolean mirrorHeaderVerified = new AtomicBoolean(false);

    // 1. Setup Mirror Server - IMPORTANT: It must CLOSE the call
    String mirrorName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(mirrorName)
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder("test")
                    .addMethod(
                        method,
                        (call, headers) -> {
                          if ("shadow-value".equals(headers.get(testKey))) {
                            mirrorHeaderVerified.set(true);
                          }
                          mirrorLatch.countDown();

                          // CRITICAL: Close the call so the channel can shut down
                          call.sendHeaders(new Metadata());
                          call.close(Status.OK, new Metadata());
                          return new ServerCall.Listener<String>() {};
                        })
                    .build())
            .build()
            .start());

    // 2. Setup Primary Server - Also must CLOSE the call
    String primaryName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(primaryName)
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder("test")
                    .addMethod(
                        method,
                        (call, headers) -> {
                          call.sendHeaders(new Metadata());
                          call.close(Status.OK, new Metadata());
                          return new ServerCall.Listener<String>() {};
                        })
                    .build())
            .build()
            .start());

    ManagedChannel mirrorChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(mirrorName).build());
    ManagedChannel primaryChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(primaryName).build());

    // Use direct executor to keep the mirror call on the same thread
    java.util.concurrent.Executor directExecutor = Runnable::run;

    Channel interceptedChannel =
        ClientInterceptors.intercept(
            primaryChannel, new MirroringInterceptor(mirrorChannel, directExecutor));

    // 3. Trigger call with Metadata
    Metadata headers = new Metadata();
    headers.put(testKey, "shadow-value");

    ClientCall<String, String> call = interceptedChannel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {}, headers);
    call.sendMessage("hello");
    call.halfClose();

    // 4. Assertions
    assertTrue("Mirror server was not reached", mirrorLatch.await(1, TimeUnit.SECONDS));
    assertTrue(
        "Headers were not correctly mirrored to shadow service", mirrorHeaderVerified.get());
    System.out.println("FULL MIRRORING SUCCESSFUL!");
  }
}