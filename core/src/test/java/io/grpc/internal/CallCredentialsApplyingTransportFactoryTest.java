/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.testing.TestMethodDescriptors;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CallCredentialsApplyingTransportFactory}. */
@RunWith(JUnit4.class)
public class CallCredentialsApplyingTransportFactoryTest {
  @Test
  public void transportFactoryWithNewChannelCreds() {
    ClientStream mockStream = mock(ClientStream.class);
    ConnectionClientTransport mockTransport = mock(ConnectionClientTransport.class);
    ClientTransportFactory delegateWithNewCreds = mock(ClientTransportFactory.class);
    ClientTransportFactory delegate = mock(ClientTransportFactory.class);
    doReturn(delegateWithNewCreds).when(delegate)
        .withNewChannelCredentials(any(ChannelCredentials.class));
    doReturn(mockTransport).when(delegateWithNewCreds)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    doReturn(mockStream).when(mockTransport)
        .newStream(any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));


    final Metadata.Key<String> credKey = Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER);
    CallCredentials callCredentials = new CallCredentials() {
      @Override
      public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
        Metadata metadata = new Metadata();
        metadata.put(credKey, "token");
        applier.apply(metadata);
      }

      @Override
      public void thisUsesUnstableApi() {} // Ack.
    };

    Executor appExecutor = MoreExecutors.directExecutor();
    CallCredentialsApplyingTransportFactory factory = new CallCredentialsApplyingTransportFactory(
        delegate, callCredentials, appExecutor);

    ClientTransportFactory factoryWithNewCreds =
        factory.withNewChannelCredentials(mock(ChannelCredentials.class));
    assertThat(factoryWithNewCreds).isInstanceOf(CallCredentialsApplyingTransportFactory.class);
    CallCredentialsApplyingTransportFactory newCallCredsApplyingFactory =
         (CallCredentialsApplyingTransportFactory) factoryWithNewCreds;

    ClientTransport newClientTransport = newCallCredsApplyingFactory.newClientTransport(
        new InetSocketAddress(0), new ClientTransportOptions(), mock(ChannelLogger.class));
    Metadata originalMetadata = new Metadata();
    ClientStream newStream = newClientTransport.newStream(
        TestMethodDescriptors.voidMethod(), originalMetadata, CallOptions.DEFAULT);
    assertThat(newStream).isSameInstanceAs(mockStream);
    assertThat(originalMetadata.get(credKey)).isEqualTo("token");
  }
}
