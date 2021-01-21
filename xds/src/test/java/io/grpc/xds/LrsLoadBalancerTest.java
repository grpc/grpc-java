/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.ClientLoadCounter.LoadRecordingStreamTracerFactory;
import io.grpc.xds.ClientLoadCounter.LoadRecordingSubchannelPicker;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link LrsLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class LrsLoadBalancerTest {
  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  private static final String CLUSTER_NAME = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = "cluster-foo:service-blade";
  private static final String LRS_SERVER_NAME = "trafficdirector.googleapis.com";
  private static final Locality TEST_LOCALITY =
      new Locality("test-region", "test-zone", "test-subzone");
  private final LoadRecorder loadRecorder = new LoadRecorder();
  private final Queue<LoadBalancer> childBalancers = new ArrayDeque<>();

  @Mock
  private Helper helper;
  private LrsLoadBalancer loadBalancer;

  @Before
  public void setUp() {
    loadBalancer = new LrsLoadBalancer(helper);
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
  }

  @Test
  public void subchannelPickerInterceptedWithLoadRecording() {
    List<EquivalentAddressGroup> backendAddrs = createResolvedBackendAddresses(2);
    deliverResolvedAddresses(backendAddrs, "round_robin");
    FakeLoadBalancer childBalancer = (FakeLoadBalancer) childBalancers.poll();
    NoopSubchannel subchannel = childBalancer.subchannels.values().iterator().next();
    deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(loadRecorder.recording).isTrue();
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(picker).isInstanceOf(LoadRecordingSubchannelPicker.class);
    PickResult result = picker.pickSubchannel(mock(PickSubchannelArgs.class));
    ClientStreamTracer.Factory tracerFactory = result.getStreamTracerFactory();
    assertThat(((LoadRecordingStreamTracerFactory) tracerFactory).getCounter())
        .isSameInstanceAs(loadRecorder.counter);
    loadBalancer.shutdown();
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(loadRecorder.recording).isFalse();
  }

  @Test
  public void updateChildPolicy() {
    List<EquivalentAddressGroup> backendAddrs = createResolvedBackendAddresses(2);
    deliverResolvedAddresses(backendAddrs, "round_robin");
    FakeLoadBalancer childBalancer = (FakeLoadBalancer) childBalancers.poll();
    assertThat(childBalancer.name).isEqualTo("round_robin");
    deliverResolvedAddresses(backendAddrs, "pick_first");
    assertThat(childBalancer.shutdown).isTrue();
    childBalancer = (FakeLoadBalancer) childBalancers.poll();
    assertThat(childBalancer.name).isEqualTo("pick_first");
    loadBalancer.shutdown();
    assertThat(childBalancer.shutdown).isTrue();
  }

  @Test
  public void errorPropagation() {
    loadBalancer.handleNameResolutionError(Status.UNKNOWN.withDescription("I failed"));
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper)
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status status =
        pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class)).getStatus();
    assertThat(status.getDescription()).contains("I failed");

    List<EquivalentAddressGroup> backendAddrs = createResolvedBackendAddresses(2);
    deliverResolvedAddresses(backendAddrs, "round_robin");
    // Error after child policy is created.
    loadBalancer.handleNameResolutionError(Status.UNKNOWN.withDescription("I failed"));
    verify(helper, times(2))
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    status = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class)).getStatus();
    assertThat(status.getDescription()).contains("I failed");
    assertThat(status.getDescription()).contains("handled by downstream balancer");
  }

  private void deliverResolvedAddresses(
      List<EquivalentAddressGroup> addresses, String childPolicy) {
    PolicySelection childPolicyConfig =
        new PolicySelection(new FakeLoadBalancerProvider(childPolicy), null);
    LrsConfig config =
        new LrsConfig(
            CLUSTER_NAME, EDS_SERVICE_NAME, LRS_SERVER_NAME, TEST_LOCALITY, childPolicyConfig);
    ResolvedAddresses resolvedAddresses =
        ResolvedAddresses.newBuilder()
            .setAddresses(addresses)
            .setAttributes(
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE, loadRecorder)
                    .build())
            .setLoadBalancingPolicyConfig(config)
            .build();
    loadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  private static List<EquivalentAddressGroup> createResolvedBackendAddresses(int n) {
    List<EquivalentAddressGroup> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      SocketAddress addr = new FakeSocketAddress("fake-address-" + i);
      list.add(new EquivalentAddressGroup(addr));
    }
    return list;
  }

  private static void deliverSubchannelState(
      final NoopSubchannel subchannel, ConnectivityState state) {
    SubchannelPicker picker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel);
      }
    };
    subchannel.helper.updateBalancingState(state, picker);
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;

    FakeLoadBalancerProvider(String policyName) {
      this.policyName = policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      LoadBalancer balancer = new FakeLoadBalancer(helper, policyName);
      childBalancers.add(balancer);
      return balancer;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 0;  // doesn't matter
    }

    @Override
    public String getPolicyName() {
      return policyName;
    }
  }

  private static final class FakeLoadBalancer extends LoadBalancer {
    private final Helper helper;
    private final String name;
    private boolean shutdown;
    private final Map<EquivalentAddressGroup, NoopSubchannel> subchannels = new HashMap<>();

    FakeLoadBalancer(Helper helper, String name) {
      this.helper = helper;
      this.name = name;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();
      for (EquivalentAddressGroup eag : addresses) {
        subchannels.put(eag, new NoopSubchannel(helper));
      }
    }

    @Override
    public void handleNameResolutionError(final Status error) {
      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          return PickResult.withError(error.augmentDescription("handled by downstream balancer"));
        }
      };
      helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, picker);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }
  }

  private static final class NoopSubchannel extends Subchannel {
    final Helper helper;

    NoopSubchannel(Helper helper) {
      this.helper = helper;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
    }

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }
  }

  private static final class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" + name;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof FakeSocketAddress) {
        FakeSocketAddress otherAddr = (FakeSocketAddress) other;
        return name.equals(otherAddr.name);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }

  private static final class LoadRecorder implements LoadStatsStore {
    private final ClientLoadCounter counter = new ClientLoadCounter();
    private boolean recording = false;

    @Override
    public ClusterStats generateLoadReport() {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public ClientLoadCounter addLocality(Locality locality) {
      assertThat(locality).isEqualTo(TEST_LOCALITY);
      recording = true;
      return counter;
    }

    @Override
    public void removeLocality(Locality locality) {
      assertThat(locality).isEqualTo(TEST_LOCALITY);
      recording = false;
    }

    @Override
    public void recordDroppedRequest(String category) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public void recordDroppedRequest() {
      throw new UnsupportedOperationException("should not be called");
    }
  }
}
