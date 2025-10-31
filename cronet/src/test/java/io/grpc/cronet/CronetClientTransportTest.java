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

package io.grpc.cronet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Build;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.cronet.CronetChannelBuilder.StreamBuilderFactory;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.DisconnectError;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.TransportTracer;
import io.grpc.testing.TestMethodDescriptors;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import org.chromium.net.BidirectionalStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public final class CronetClientTransportTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String AUTHORITY = "test.example.com";
  private static final Attributes.Key<String> EAG_ATTR_KEY =
      Attributes.Key.create("eag-attr");

  private static final Attributes EAG_ATTRS =
      Attributes.newBuilder().set(EAG_ATTR_KEY, "value").build();

  private final ClientStreamTracer[] tracers =
      new ClientStreamTracer[]{ new ClientStreamTracer() {} };
  private CronetClientTransport transport;
  @Mock private StreamBuilderFactory streamFactory;
  private MethodDescriptor<Void, Void> descriptor = TestMethodDescriptors.voidMethod();
  @Mock private ManagedClientTransport.Listener clientTransportListener;
  @Mock private BidirectionalStream.Builder builder;
  private final Executor executor = Runnable::run;

  @Before
  public void setUp() {
    when(clientTransportListener.filterTransport(any()))
        .thenAnswer(i -> i.getArgument(0, Attributes.class));
    transport =
        new CronetClientTransport(
            streamFactory,
            new InetSocketAddress("localhost", 443),
            AUTHORITY,
            null,
            EAG_ATTRS,
            executor,
            5000,
            false,
            TransportTracer.getDefaultFactory().create(),
            false,
            false);
    Runnable callback = transport.start(clientTransportListener);
    assertNotNull(callback);
    callback.run();
    verify(clientTransportListener).transportReady();
  }

  @Test
  public void transportAttributes() {
    Attributes attrs = transport.getAttributes();
    assertEquals(
        SecurityLevel.PRIVACY_AND_INTEGRITY, attrs.get(GrpcAttributes.ATTR_SECURITY_LEVEL));
    assertEquals(EAG_ATTRS, attrs.get(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS));
  }

  @Test
  public void shutdownTransport() throws Exception {
    CronetClientStream stream1 =
        transport.newStream(descriptor, new Metadata(), CallOptions.DEFAULT, tracers);
    CronetClientStream stream2 =
        transport.newStream(descriptor, new Metadata(), CallOptions.DEFAULT, tracers);

    // Create a transport and start two streams on it.
    ArgumentCaptor<BidirectionalStream.Callback> callbackCaptor =
        ArgumentCaptor.forClass(BidirectionalStream.Callback.class);
    when(streamFactory.newBidirectionalStreamBuilder(
            any(String.class), callbackCaptor.capture(), any(Executor.class)))
        .thenReturn(builder);
    BidirectionalStream cronetStream1 = mock(BidirectionalStream.class);
    when(builder.build()).thenReturn(cronetStream1);
    stream1.start(mock(ClientStreamListener.class));
    BidirectionalStream.Callback callback1 = callbackCaptor.getValue();

    BidirectionalStream cronetStream2 = mock(BidirectionalStream.class);
    when(builder.build()).thenReturn(cronetStream2);
    stream2.start(mock(ClientStreamListener.class));
    BidirectionalStream.Callback callback2 = callbackCaptor.getValue();
    // Shut down the transport. transportShutdown should be called immediately.
    transport.shutdown();
    verify(clientTransportListener).transportShutdown(any(Status.class),
        any(DisconnectError.class));
    // Have two live streams. Transport has not been terminated.
    verify(clientTransportListener, times(0)).transportTerminated();

    callback1.onCanceled(cronetStream1, null);
    // Still has one live stream
    verify(clientTransportListener, times(0)).transportTerminated();
    callback2.onCanceled(cronetStream1, null);
    // All streams are gone now.
    verify(clientTransportListener, times(1)).transportTerminated();
  }

  @Test
  public void startStreamAfterShutdown() throws Exception {
    CronetClientStream stream =
        transport.newStream(descriptor, new Metadata(), CallOptions.DEFAULT, tracers);
    transport.shutdown();
    BaseClientStreamListener listener = new BaseClientStreamListener();
    stream.start(listener);

    assertEquals(Status.UNAVAILABLE.getCode(), listener.status.getCode());
  }

  private static class BaseClientStreamListener implements ClientStreamListener {
    private Status status;

    @Override
    public void messagesAvailable(MessageProducer producer) {}

    @Override
    public void onReady() {}

    @Override
    public void headersRead(Metadata headers) {}

    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      this.status = status;
    }
  }
}
