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
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
  @Mock
  private ClientCall.Listener<Integer> mockCallListener;

  @Test
  public void retryUntilBufferLimitExceeded() throws Exception {
    String message = "String of length 20.";
    int bufferLimit = message.length() * 2 - 1; // Can buffer no more than 1 message.

    MethodDescriptor<String, Integer> clientStreamingMethod =
        MethodDescriptor.<String, Integer>newBuilder()
            .setType(MethodType.CLIENT_STREAMING)
            .setFullMethodName("service/method")
            .setRequestMarshaller(new StringMarshaller())
            .setResponseMarshaller(new IntegerMarshaller())
            .build();
    final LinkedBlockingQueue<ServerCall<String, Integer>> serverCalls =
        new LinkedBlockingQueue<>();
    ServerMethodDefinition<String, Integer> methodDefinition = ServerMethodDefinition.create(
        clientStreamingMethod,
        new ServerCallHandler<String, Integer>() {
          @Override
          public Listener<String> startCall(ServerCall<String, Integer> call, Metadata headers) {
            serverCalls.offer(call);
            return new Listener<String>() {};
          }
        }
    );
    ServerServiceDefinition serviceDefinition =
        ServerServiceDefinition.builder(clientStreamingMethod.getServiceName())
            .addMethod(methodDefinition)
            .build();
    EventLoopGroup group = new DefaultEventLoopGroup();
    LocalAddress localAddress = new LocalAddress("RetryTest.retryUntilBufferLimitExceeded");
    Server localServer = cleanupRule.register(NettyServerBuilder.forAddress(localAddress)
        .channelType(LocalServerChannel.class)
        .bossEventLoopGroup(group)
        .workerEventLoopGroup(group)
        .addService(serviceDefinition)
        .build());
    localServer.start();

    Map<String, Object> retryPolicy = new HashMap<>();
    retryPolicy.put("maxAttempts", 4D);
    retryPolicy.put("initialBackoff", "10s");
    retryPolicy.put("maxBackoff", "10s");
    retryPolicy.put("backoffMultiplier", 1D);
    retryPolicy.put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"));
    Map<String, Object> methodConfig = new HashMap<>();
    Map<String, Object> name = new HashMap<>();
    name.put("service", "service");
    methodConfig.put("name", Arrays.<Object>asList(name));
    methodConfig.put("retryPolicy", retryPolicy);
    Map<String, Object> rawServiceConfig = new HashMap<>();
    rawServiceConfig.put("methodConfig", Arrays.<Object>asList(methodConfig));
    ManagedChannel channel = cleanupRule.register(
        NettyChannelBuilder.forAddress(localAddress)
            .channelType(LocalChannel.class)
            .eventLoopGroup(group)
            .usePlaintext()
            .enableRetry()
            .perRpcBufferLimit(bufferLimit)
            .defaultServiceConfig(rawServiceConfig)
            .build());
    ClientCall<String, Integer> call = channel.newCall(clientStreamingMethod, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    call.sendMessage(message);

    ServerCall<String, Integer> serverCall = serverCalls.poll(5, TimeUnit.SECONDS);
    serverCall.request(2);
    // trigger retry
    Metadata pushBackMetadata = new Metadata();
    pushBackMetadata.put(
        Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER),
        "0");   // retry immediately
    serverCall.close(
        Status.UNAVAILABLE.withDescription("original attempt failed"),
        pushBackMetadata);
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
