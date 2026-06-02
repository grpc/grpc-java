/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.client;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.InsecureChannelCredentials;
import io.grpc.MethodDescriptor;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.client.XdsClient.ResourceStore;
import io.grpc.xds.client.XdsClient.XdsResponseHandler;
import io.grpc.xds.client.XdsTransportFactory.EventHandler;
import io.grpc.xds.client.XdsTransportFactory.StreamingCall;
import io.grpc.xds.client.XdsTransportFactory.XdsTransport;
import java.util.Collections;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ControlPlaneClient}. */
@RunWith(JUnit4.class)
public class ControlPlaneClientTest {

  private static final String CDS_TYPE_URL = "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String EDS_TYPE_URL =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

  private final SynchronizationContext syncContext =
      new SynchronizationContext((t, e) -> {
        throw new AssertionError("Uncaught exception in sync context", e);
      });
  private final FakeClock fakeClock = new FakeClock();
  private final ServerInfo serverInfo =
      ServerInfo.create("eds-control-plane:8443", InsecureChannelCredentials.create());
  private final Node bootstrapNode = Node.newBuilder().setId("test-node").build();

  @Mock private XdsTransport xdsTransport;
  @Mock private StreamingCall<DiscoveryRequest, DiscoveryResponse> streamingCall;
  @Mock private XdsResponseHandler responseHandler;
  @Mock private ResourceStore resourceStore;
  @Mock private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock private MessagePrettyPrinter messagePrinter;
  @Mock private XdsResourceType<?> cdsType;
  @Mock private XdsResourceType<?> edsType;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ControlPlaneClient cpc;
  private ArgumentCaptor<EventHandler<DiscoveryResponse>> handlerCaptor;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    when(cdsType.typeUrl()).thenReturn(CDS_TYPE_URL);
    when(cdsType.typeName()).thenReturn("CDS");
    when(edsType.typeUrl()).thenReturn(EDS_TYPE_URL);
    when(edsType.typeName()).thenReturn("EDS");

    when(xdsTransport.<DiscoveryRequest, DiscoveryResponse>createStreamingCall(
            anyString(),
            any(MethodDescriptor.Marshaller.class),
            any(MethodDescriptor.Marshaller.class)))
        .thenReturn(streamingCall);
    when(streamingCall.isReady()).thenReturn(true);

    handlerCaptor = ArgumentCaptor.forClass(EventHandler.class);

    cpc = new ControlPlaneClient(
        xdsTransport,
        serverInfo,
        bootstrapNode,
        responseHandler,
        resourceStore,
        fakeClock.getScheduledExecutorService(),
        syncContext,
        backoffPolicyProvider,
        () -> Stopwatch.createUnstarted(fakeClock.getTicker()),
        messagePrinter);
  }

  /**
   * Reproduces the bug where, when an ADS stream is opened to an authority-specific server (e.g.
   * an EDS-only control plane), {@code sendDiscoveryRequests} previously emitted an empty
   * DiscoveryRequest for every globally-subscribed resource type — including types this server
   * does not handle. Authority-specific servers may reject those requests with UNIMPLEMENTED and
   * tear down the stream, blocking the legitimate request that follows.
   *
   * <p>Asserts that the empty CDS request is suppressed and only the EDS request (which has
   * resources for this server) goes on the wire.
   */
  @Test
  public void streamReady_skipsEmptyDiscoveryRequestForUnsubscribedType() {
    // CDS is globally subscribed (e.g. against a different authority) but has no resources on
    // this server. EDS has one resource on this server.
    Map<String, XdsResourceType<?>> subscribedTypes =
        ImmutableMap.of(CDS_TYPE_URL, cdsType, EDS_TYPE_URL, edsType);
    when(resourceStore.getSubscribedResourceTypesWithTypeUrl()).thenReturn(subscribedTypes);
    when(resourceStore.getSubscribedResources(serverInfo, cdsType)).thenReturn(null);
    when(resourceStore.getSubscribedResources(serverInfo, edsType))
        .thenReturn(ImmutableList.of("foo-endpoint"));

    // Triggers stream creation and registers the EventHandler.
    syncContext.execute(cpc::sendDiscoveryRequests);
    verify(streamingCall).start(handlerCaptor.capture());

    // Drive the stream into the connected state. onReady() flips sentInitialRequest=true and
    // re-invokes sendDiscoveryRequests, which iterates the globally-subscribed types.
    handlerCaptor.getValue().onReady();

    // EDS request was sent with the one resource for this server.
    ArgumentCaptor<DiscoveryRequest> sent = ArgumentCaptor.forClass(DiscoveryRequest.class);
    verify(streamingCall, atLeastOnce()).sendMessage(sent.capture());
    ImmutableSet<String> sentTypes = sent.getAllValues().stream()
        .map(DiscoveryRequest::getTypeUrl)
        .collect(ImmutableSet.toImmutableSet());
    assertThat(sentTypes).contains(EDS_TYPE_URL);
    assertThat(sentTypes).doesNotContain(CDS_TYPE_URL);

    // Confirm the EDS request actually carried the resource name.
    DiscoveryRequest edsReq = sent.getAllValues().stream()
        .filter(r -> r.getTypeUrl().equals(EDS_TYPE_URL))
        .findFirst()
        .orElseThrow(() -> new AssertionError("EDS request not sent"));
    assertThat(edsReq.getResourceNamesList()).containsExactly("foo-endpoint");
  }

  /**
   * If a server has resources for every globally-subscribed type, the empty-skip guard is a
   * no-op: a DiscoveryRequest is sent for every type. This guards against the skip becoming
   * over-eager and dropping legitimate subscriptions.
   */
  @Test
  public void streamReady_sendsRequestForAllTypesWhenAllHaveResources() {
    Map<String, XdsResourceType<?>> subscribedTypes =
        ImmutableMap.of(CDS_TYPE_URL, cdsType, EDS_TYPE_URL, edsType);
    when(resourceStore.getSubscribedResourceTypesWithTypeUrl()).thenReturn(subscribedTypes);
    when(resourceStore.getSubscribedResources(serverInfo, cdsType))
        .thenReturn(ImmutableList.of("foo-cluster"));
    when(resourceStore.getSubscribedResources(serverInfo, edsType))
        .thenReturn(ImmutableList.of("foo-endpoint"));

    syncContext.execute(cpc::sendDiscoveryRequests);
    verify(streamingCall).start(handlerCaptor.capture());
    handlerCaptor.getValue().onReady();

    ArgumentCaptor<DiscoveryRequest> sent = ArgumentCaptor.forClass(DiscoveryRequest.class);
    verify(streamingCall, times(2)).sendMessage(sent.capture());
    ImmutableSet<String> sentTypes = sent.getAllValues().stream()
        .map(DiscoveryRequest::getTypeUrl)
        .collect(ImmutableSet.toImmutableSet());
    assertThat(sentTypes).containsExactly(CDS_TYPE_URL, EDS_TYPE_URL);
  }

  /**
   * If only one type has a subscription on this server, no request is sent for the unsubscribed
   * type. This is the canonical multi-authority federation case (e.g. fabric authority owns CDS,
   * eds-control-plane owns EDS — the eds-control-plane stream should only see EDS).
   */
  @Test
  public void streamReady_skipsTypeWithNoSubscription() {
    Map<String, XdsResourceType<?>> subscribedTypes =
        ImmutableMap.of(CDS_TYPE_URL, cdsType, EDS_TYPE_URL, edsType);
    when(resourceStore.getSubscribedResourceTypesWithTypeUrl()).thenReturn(subscribedTypes);
    when(resourceStore.getSubscribedResources(serverInfo, cdsType)).thenReturn(null);
    when(resourceStore.getSubscribedResources(serverInfo, edsType))
        .thenReturn(ImmutableList.of("foo-endpoint"));

    syncContext.execute(cpc::sendDiscoveryRequests);
    verify(streamingCall).start(handlerCaptor.capture());
    handlerCaptor.getValue().onReady();

    verify(streamingCall, never()).sendMessage(
        argThatTypeUrlIs(CDS_TYPE_URL));
    verify(streamingCall).sendMessage(argThatTypeUrlIs(EDS_TYPE_URL));
  }

  /**
   * Per the ResourceStore contract in XdsClient.java, an empty collection from
   * getSubscribedResources indicates a wildcard subscription. The skip-on-empty guard must not
   * suppress wildcard requests on initial stream ready — the server needs the empty-resource-list
   * DiscoveryRequest to start streaming, and the watcher's missing-resource timers must start.
   */
  @Test
  public void streamReady_sendsWildcardRequestAndStartsTimers() {
    Map<String, XdsResourceType<?>> subscribedTypes = ImmutableMap.of(CDS_TYPE_URL, cdsType);
    when(resourceStore.getSubscribedResourceTypesWithTypeUrl()).thenReturn(subscribedTypes);
    // Empty collection == wildcard subscription per the ResourceStore contract.
    when(resourceStore.getSubscribedResources(serverInfo, cdsType))
        .thenReturn(Collections.emptyList());

    syncContext.execute(cpc::sendDiscoveryRequests);
    verify(streamingCall).start(handlerCaptor.capture());
    handlerCaptor.getValue().onReady();

    ArgumentCaptor<DiscoveryRequest> sent = ArgumentCaptor.forClass(DiscoveryRequest.class);
    verify(streamingCall, atLeastOnce()).sendMessage(sent.capture());
    DiscoveryRequest cdsReq = sent.getAllValues().stream()
        .filter(r -> r.getTypeUrl().equals(CDS_TYPE_URL))
        .findFirst()
        .orElseThrow(() -> new AssertionError("CDS wildcard request not sent"));
    assertThat(cdsReq.getResourceNamesList()).isEmpty();

    verify(resourceStore).startMissingResourceTimers(Collections.emptyList(), cdsType);
  }

  /**
   * If a watch is canceled after the initial DiscoveryRequest goes out but before any response
   * is ACKed, the empty unsubscribe must still be sent — otherwise the server keeps the stale
   * subscription until the stream resets. The skip guard must gate on per-stream send history,
   * not on the {@code versions} map (which is only populated on ACK).
   */
  @Test
  public void cancelBeforeAck_sendsEmptyUnsubscribe() {
    Map<String, XdsResourceType<?>> subscribedTypes = ImmutableMap.of(CDS_TYPE_URL, cdsType);
    when(resourceStore.getSubscribedResourceTypesWithTypeUrl()).thenReturn(subscribedTypes);
    when(resourceStore.getSubscribedResources(serverInfo, cdsType))
        .thenReturn(ImmutableList.of("foo-cluster"));

    syncContext.execute(cpc::sendDiscoveryRequests);
    verify(streamingCall).start(handlerCaptor.capture());
    handlerCaptor.getValue().onReady();

    // Initial DiscoveryRequest with the resource went out. No DiscoveryResponse has been ACKed.
    verify(streamingCall).sendMessage(argThatTypeUrlIs(CDS_TYPE_URL));

    // Cancel the watch before any response arrives: store now reports no subscription.
    when(resourceStore.getSubscribedResources(serverInfo, cdsType)).thenReturn(null);
    syncContext.execute(() -> cpc.adjustResourceSubscription(cdsType));

    ArgumentCaptor<DiscoveryRequest> sent = ArgumentCaptor.forClass(DiscoveryRequest.class);
    verify(streamingCall, times(2)).sendMessage(sent.capture());
    DiscoveryRequest unsub = sent.getAllValues().get(1);
    assertThat(unsub.getTypeUrl()).isEqualTo(CDS_TYPE_URL);
    assertThat(unsub.getResourceNamesList()).isEmpty();
  }

  private static DiscoveryRequest argThatTypeUrlIs(String typeUrl) {
    return argThat(req -> req != null && typeUrl.equals(req.getTypeUrl()));
  }
}