/*
 * Copyright 2021 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.IntegerMarshaller;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StringMarshaller;
import io.grpc.internal.FakeClock;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RetryTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private final FakeClock fakeClock = new FakeClock();
  @Mock
  private ClientCall.Listener<Integer> mockCallListener;
  private CountDownLatch backoffLatch = new CountDownLatch(1);
  private final EventLoopGroup group = new DefaultEventLoopGroup() {
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public ScheduledFuture<?> schedule(
        final Runnable command, final long delay, final TimeUnit unit) {
      if (!command.getClass().getName().contains("RetryBackoffRunnable")) {
        return super.schedule(command, delay, unit);
      }
      fakeClock.getScheduledExecutorService().schedule(
          new Runnable() {
            @Override
            public void run() {
              group.execute(command);
            }
          },
          delay,
          unit);
      backoffLatch.countDown();
      return super.schedule(
          new Runnable() {
            @Override
            public void run() {} // no-op
          },
          0,
          TimeUnit.NANOSECONDS);
    }
  };

  private final MethodDescriptor<String, Integer> clientStreamingMethod =
      MethodDescriptor.<String, Integer>newBuilder()
          .setType(MethodType.CLIENT_STREAMING)
          .setFullMethodName("service/method")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();
  private final LinkedBlockingQueue<ServerCall<String, Integer>> serverCalls =
      new LinkedBlockingQueue<>();
  private final ServerMethodDefinition<String, Integer> methodDefinition =
      ServerMethodDefinition.create(
          clientStreamingMethod,
          new ServerCallHandler<String, Integer>() {
            @Override
            public Listener<String> startCall(ServerCall<String, Integer> call, Metadata headers) {
              serverCalls.offer(call);
              return new Listener<String>() {};
            }
          }
  );
  private final ServerServiceDefinition serviceDefinition =
      ServerServiceDefinition.builder(clientStreamingMethod.getServiceName())
          .addMethod(methodDefinition)
          .build();
  private final LocalAddress localAddress = new LocalAddress(this.getClass().getName());
  private Server localServer;
  private ManagedChannel channel;
  private Map<String, Object> retryPolicy = ImmutableMap.of();
  private long bufferLimit = 1L << 20; // 1M

  private void startNewServer() throws Exception {
    localServer = cleanupRule.register(NettyServerBuilder.forAddress(localAddress)
        .channelType(LocalServerChannel.class)
        .bossEventLoopGroup(group)
        .workerEventLoopGroup(group)
        .addService(serviceDefinition)
        .build());
    localServer.start();
  }

  private void createNewChannel() {
    Map<String, Object> methodConfig = new HashMap<>();
    Map<String, Object> name = new HashMap<>();
    name.put("service", "service");
    methodConfig.put("name", Arrays.<Object>asList(name));
    methodConfig.put("retryPolicy", retryPolicy);
    Map<String, Object> rawServiceConfig = new HashMap<>();
    rawServiceConfig.put("methodConfig", Arrays.<Object>asList(methodConfig));
    channel = cleanupRule.register(
        NettyChannelBuilder.forAddress(localAddress)
            .channelType(LocalChannel.class)
            .eventLoopGroup(group)
            .usePlaintext()
            .enableRetry()
            .perRpcBufferLimit(bufferLimit)
            .defaultServiceConfig(rawServiceConfig)
            .build());
  }

  private void elapseBackoff(long time, TimeUnit unit) throws Exception {
    assertThat(backoffLatch.await(5, TimeUnit.SECONDS)).isTrue();
    backoffLatch = new CountDownLatch(1);
    fakeClock.forwardTime(time, unit);
  }

  @Test
  public void retryUntilBufferLimitExceeded() throws Exception {
    String message = "String of length 20.";

    startNewServer();
    bufferLimit = message.length() * 2L - 1; // Can buffer no more than 1 message.
    retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 4D)
        .put("initialBackoff", "10s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1D)
        .put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"))
        .build();
    createNewChannel();
    ClientCall<String, Integer> call = channel.newCall(clientStreamingMethod, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    call.sendMessage(message);

    ServerCall<String, Integer> serverCall = serverCalls.poll(5, TimeUnit.SECONDS);
    serverCall.request(2);
    // trigger retry
    serverCall.close(
        Status.UNAVAILABLE.withDescription("original attempt failed"),
        new Metadata());
    elapseBackoff(10, TimeUnit.SECONDS);
    // 2nd attempt received
    serverCall = serverCalls.poll(5, TimeUnit.SECONDS);
    serverCall.request(2);
    verify(mockCallListener, never()).onClose(any(Status.class), any(Metadata.class));
    // send one more message, should exceed buffer limit
    call.sendMessage(message);
    // let attempt fail
    serverCall.close(
        Status.UNAVAILABLE.withDescription("2nd attempt failed"),
        new Metadata());
    // no more retry
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(mockCallListener, timeout(5000)).onClose(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getDescription()).contains("2nd attempt failed");
  }
}
