/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Common utility methods for tests.
 */
final class TestUtils {

  static class MockClientTransportInfo {
    /**
     * A mock transport created by the mock transport factory.
     */
    final ConnectionClientTransport transport;

    /**
     * The listener passed to the start() of the mock transport.
     */
    final ManagedClientTransport.Listener listener;

    MockClientTransportInfo(ConnectionClientTransport transport,
        ManagedClientTransport.Listener listener) {
      this.transport = transport;
      this.listener = listener;
    }
  }

  /**
   * Stub the given mock {@link ClientTransportFactory} by returning mock
   * {@link ManagedClientTransport}s which saves their listeners along with them. This method
   * returns a list of {@link MockClientTransportInfo}, each of which is a started mock transport
   * and its listener.
   */
  static BlockingQueue<MockClientTransportInfo> captureTransports(
      ClientTransportFactory mockTransportFactory) {
    return captureTransports(mockTransportFactory, null);
  }

  static BlockingQueue<MockClientTransportInfo> captureTransports(
      ClientTransportFactory mockTransportFactory, @Nullable final Runnable startRunnable) {
    final BlockingQueue<MockClientTransportInfo> captor =
        new LinkedBlockingQueue<MockClientTransportInfo>();

    doAnswer(new Answer<ConnectionClientTransport>() {
      @Override
      public ConnectionClientTransport answer(InvocationOnMock invocation) throws Throwable {
        final ConnectionClientTransport mockTransport = mock(ConnectionClientTransport.class);
        when(mockTransport.newStream(
                any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class)))
            .thenReturn(mock(ClientStream.class));
        // Save the listener
        doAnswer(new Answer<Runnable>() {
          @Override
          public Runnable answer(InvocationOnMock invocation) throws Throwable {
            captor.add(new MockClientTransportInfo(
                mockTransport, (ManagedClientTransport.Listener) invocation.getArguments()[0]));
            return startRunnable;
          }
        }).when(mockTransport).start(any(ManagedClientTransport.Listener.class));
        return mockTransport;
      }
    }).when(mockTransportFactory)
        .newClientTransport(any(SocketAddress.class), any(String.class), any(String.class));

    return captor;
  }

  public static class Methods {
    public static final ImmutableSet<MethodType> ALL =
        ImmutableSet.copyOf(MethodType.values());

    // UNARY and SERVER_STREAMING
    public static final ImmutableSet<MethodType> CLIENT_SENDS_ONE =
        ImmutableSet.copyOf(Collections2.filter(ALL, new Predicate<MethodType>() {
          @Override
          public boolean apply(MethodType input) {
            return input.clientSendsOneMessage();
          }
        }));

    // UNARY and CLIENT_STREAMING
    public static final ImmutableSet<MethodType> SERVER_SENDS_ONE =
        ImmutableSet.copyOf(Collections2.filter(ALL, new Predicate<MethodType>() {
          @Override
          public boolean apply(MethodType input) {
            return input.serverSendsOneMessage();
          }
        }));

    public static final Set<MethodType> CLIENT_SENDS_MULTI = Sets.difference(
        ALL, CLIENT_SENDS_ONE
    );

    public static final Set<MethodType> SERVER_SENDS_MULTI = Sets.difference(
        ALL, SERVER_SENDS_ONE
    );

    public static final Function<MethodType, Object[]> TO_PARAM_FN =
        new Function<MethodType, Object[]>() {
          @Nullable
          @Override
          public Object[] apply(@Nullable MethodType input) {
            return new Object[] {input};
          }
        };
  }

  private TestUtils() {
  }
}
