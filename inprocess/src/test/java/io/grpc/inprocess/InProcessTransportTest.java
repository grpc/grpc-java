/*
 * Copyright 2016 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ServerStream;
import io.grpc.internal.testing.TestStreamTracer;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InProcessTransport}. */
@RunWith(JUnit4.class)
public class InProcessTransportTest extends AbstractTransportTest {
  private static final String TRANSPORT_NAME = "perfect-for-testing";
  private static final String AUTHORITY = "a-testing-authority";
  protected static final String USER_AGENT = "a-testing-user-agent";
  private static final int TIMEOUT_MS = 5000;
  private static final long TEST_MESSAGE_LENGTH = 100;

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Override
  protected InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer(streamTracerFactories);
  }

  @Override
  protected InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    InProcessServerBuilder builder = InProcessServerBuilder
        .forName(TRANSPORT_NAME)
        .maxInboundMetadataSize(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE);
    return new InProcessServer(builder, streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return AUTHORITY;
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    return new InProcessTransport(
        new InProcessSocketAddress(TRANSPORT_NAME), GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
        testAuthority(server), USER_AGENT, eagAttrs(), false, -1);
  }

  private ManagedClientTransport newClientTransportWithAssumedMessageSize(InternalServer server) {
    return new InProcessTransport(
        new InProcessSocketAddress(TRANSPORT_NAME), GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
        testAuthority(server), USER_AGENT, eagAttrs(), false, TEST_MESSAGE_LENGTH);
  }

  @Test
  @Ignore
  @Override
  public void socketStats() throws Exception {
    // test does not apply to in-process
  }

  @Test
  public void causeShouldBePropagatedWithStatus() throws Exception {
    server = null;
    String failingServerName = "server_foo";
    String serviceFoo = "service_foo";
    final Status s = Status.INTERNAL.withCause(new Throwable("failing server exception"));
    ServerServiceDefinition definition = ServerServiceDefinition.builder(serviceFoo)
        .addMethod(TestMethodDescriptors.voidMethod(), new ServerCallHandler<Void, Void>() {
          @Override
          public ServerCall.Listener<Void> startCall(
              ServerCall<Void, Void> call, Metadata headers) {
            call.close(s, new Metadata());
            return new ServerCall.Listener<Void>() {};
          }
        })
        .build();
    Server failingServer = InProcessServerBuilder
        .forName(failingServerName)
        .addService(definition)
        .directExecutor()
        .build()
        .start();
    grpcCleanupRule.register(failingServer);
    ManagedChannel channel = InProcessChannelBuilder
        .forName(failingServerName)
        .propagateCauseWithStatus(true)
        .build();
    grpcCleanupRule.register(channel);
    try {
      ClientCalls.blockingUnaryCall(channel, TestMethodDescriptors.voidMethod(),
          CallOptions.DEFAULT, null);
      fail("exception should have been thrown");
    } catch (StatusRuntimeException e) {
      // When propagateCauseWithStatus is true, the cause should be sent forward
      assertEquals(s.getCause(), e.getCause());
    }
  }

  @Test
  public void methodNotFound() throws Exception {
    server = null;
    ServerServiceDefinition definition = ServerServiceDefinition.builder("service_foo")
            .addMethod(TestMethodDescriptors.voidMethod(), new ServerCallHandler<Void, Void>() {
              @Override
              public Listener<Void> startCall(ServerCall<Void, Void> call, Metadata headers) {
                return null;
              }
            })
            .build();
    Server failingServer = InProcessServerBuilder
            .forName("nocall-service")
            .addService(definition)
            .directExecutor()
            .build()
            .start();
    grpcCleanupRule.register(failingServer);
    ManagedChannel channel = InProcessChannelBuilder
            .forName("nocall-service")
            .propagateCauseWithStatus(true)
            .build();
    grpcCleanupRule.register(channel);
    MethodDescriptor<Void, Void> nonMatchMethod =
            MethodDescriptor.<Void, Void>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNKNOWN)
                    .setFullMethodName("Waiter/serve")
                    .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
                    .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
                    .build();
    ClientCall<Void,Void> call = channel.newCall(nonMatchMethod, CallOptions.DEFAULT);
    try {
      ClientCalls.futureUnaryCall(call, null).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      fail("Call should fail.");
    } catch (ExecutionException ex) {
      StatusRuntimeException s = (StatusRuntimeException)ex.getCause();
      assertEquals(Code.UNIMPLEMENTED, s.getStatus().getCode());
    }
  }

  @Test
  public void basicStreamInProcess() throws Exception {
    InProcessServerBuilder builder = InProcessServerBuilder
        .forName(TRANSPORT_NAME)
        .maxInboundMetadataSize(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE);
    server = new InProcessServer(builder, Arrays.asList(serverStreamTracerFactory));
    server.start(serverListener);
    client = newClientTransportWithAssumedMessageSize(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;
    // Set up client stream
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), CallOptions.DEFAULT, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;
    serverStream.request(1);
    assertTrue(clientStream.isReady());
    // Send message from client to server
    clientStream.writeMessage(methodDescriptor.streamRequest("Hello from client"));
    clientStream.flush();
    // Verify server received the message and check its size
    InputStream message =
        serverStreamListener.messageQueue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals("Hello from client", methodDescriptor.parseRequest(message));
    message.close();
    clientStream.halfClose();
    assertAssumedMessageSize(clientStreamTracer1, serverStreamTracer1);

    clientStream.request(1);
    assertTrue(serverStream.isReady());
    serverStream.writeMessage(methodDescriptor.streamResponse("Hi from server"));
    serverStream.flush();
    message = clientStreamListener.messageQueue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals("Hi from server", methodDescriptor.parseResponse(message));
    assertAssumedMessageSize(serverStreamTracer1, clientStreamTracer1);
    message.close();
    Status status = Status.OK.withDescription("That was normal");
    serverStream.close(status, new Metadata());
  }

  private void assertAssumedMessageSize(
      TestStreamTracer streamTracerSender, TestStreamTracer streamTracerReceiver) {
    if (isEnabledSupportTracingMessageSizes()) {
      Assert.assertEquals(TEST_MESSAGE_LENGTH, streamTracerSender.getOutboundWireSize());
      Assert.assertEquals(TEST_MESSAGE_LENGTH, streamTracerSender.getOutboundUncompressedSize());
      Assert.assertEquals(TEST_MESSAGE_LENGTH, streamTracerReceiver.getInboundWireSize());
      Assert.assertEquals(TEST_MESSAGE_LENGTH, streamTracerReceiver.getInboundUncompressedSize());
    }
  }
}
