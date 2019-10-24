/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.ManagedChannel;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsClient.ConfigWatcher;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Tests for {@link XdsClientImpl}.
 */
public class XdsClientImplTest {

  private static final String HOSTNAME = "googleapis.foo.com";
  private static final int PORT = 8080;

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();
  private final Node node = Node.getDefaultInstance();

  private final Queue<StreamObserver<DiscoveryResponse>> responseObservers = new ArrayDeque<>();
  private final Queue<StreamObserver<DiscoveryRequest>> requestObservers = new ArrayDeque<>();

  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private ConfigWatcher configWatcher;

  private ManagedChannel channel;
  private XdsClientImpl xdsClient;

  @Before
  public void setUp() throws IOException {
    String serverName = InProcessServerBuilder.generateName();

    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        responseObservers.offer(responseObserver);
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver = mock(StreamObserver.class);
        requestObservers.offer(requestObserver);
        Answer<Void> closeRpc = new Answer<Void>() {
          @Override
          public Void answer(InvocationOnMock invocation) {
            responseObserver.onCompleted();
            return null;
          }
        };
        doAnswer(closeRpc).when(requestObserver).onCompleted();

        return requestObserver;
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(serviceImpl)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
    xdsClient =
        new XdsClientImpl(serverName, node, null, syncContext,
            fakeClock.getScheduledExecutorService(), backoffPolicyProvider,
            fakeClock.getStopwatchSupplier().get(), HOSTNAME, PORT, configWatcher);
    xdsClient.startDiscoveryRpc(channel);
  }

  @After
  public void tearDown() {
    xdsClient.shutdownDiscoveryRpc();
    channel.shutdown();
  }

  // Always test from the entire workflow: start with LDS, then RDS (if necessary), then CDS,
  // then EDS. Even if the test case covers only a specific resource type response handling.

  /**
   * Client sends back a NACK LDS request when receiving an LDS response that does not contain a
   * listener for the requested resource.
   */
  @Test
  public void nackLdsResponseWithoutMatchingResource() {

  }

  /**
   * Client resolves the virtual host config from an LDS response that contains a
   * RouteConfiguration message directly in-line for the requested resource. No RDS is needed.
   * Config is returned to the watching party.
   */
  @Test
  public void resolveVirtualHostInLdsResponse() {

  }

  /**
   * Client sends back a NACK RDS request when receiving an RDS response that does not contain a
   * route for the requested resource.
   */
  @Test
  public void nackRdsResponseWithoutMatchingResource() {

  }

  /**
   * Client resolves the virtual host config from an RDS response for the requested resource.
   * Config is returned to the watching party.
   */
  @Test
  public void resolveVirtualHostInRdsResponse() {

  }

  /**
   * Client cannot find the virtual host config in the RDS response for the requested resource.
   * An error is returned to the watching party.
   */
  @Test
  public void failToFindVirtualHostInRdsResponse() {

  }
  
  // TODO(chengyuanzhang): retry tests.
}