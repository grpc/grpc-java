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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.grpc.xds.internal.sds.TlsContextManager;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CdsLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class CdsLoadBalancerTest {
  private static final String AUTHORITY = "api.google.com";
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private final FakeXdsClient xdsClient = new FakeXdsClient();
  private final TlsContextManager tlsContextManager = new FakeTlsContextManager();
  private LoadBalancer.Helper helper = new FakeLbHelper();
  private int xdsClientRefs;
  private ConnectivityState currentState;
  private SubchannelPicker currentPicker;
  private CdsLoadBalancer loadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    LoadBalancerRegistry registry = new LoadBalancerRegistry();
    registry.register(new FakeLoadBalancerProvider(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME));
    registry.register(new FakeLoadBalancerProvider(XdsLbPolicies.EDS_POLICY_NAME));
    registry.register(new FakeLoadBalancerProvider("round_robin"));
    ObjectPool<XdsClient> xdsClientPool = new ObjectPool<XdsClient>() {
      @Override
      public XdsClient getObject() {
        xdsClientRefs++;
        return xdsClient;
      }

      @Override
      public XdsClient returnObject(Object object) {
        assertThat(xdsClientRefs).isGreaterThan(0);
        xdsClientRefs--;
        return null;
      }
    };
    loadBalancer = new CdsLoadBalancer(helper, registry, tlsContextManager);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setLoadBalancingPolicyConfig(new CdsConfig(CLUSTER))
            .setAttributes(
                Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build())
            .build());
    assertThat(xdsClient.watcher).isNotNull();
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    assertThat(xdsClient.watcher).isNull();
    assertThat(xdsClientRefs).isEqualTo(0);
  }


  @Test
  public void receiveFirstClusterResourceInfo() {
    xdsClient.deliverClusterInfo(null, null);
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(XdsLbPolicies.EDS_POLICY_NAME);
    assertThat(childBalancer.config).isNotNull();
    EdsConfig edsConfig = (EdsConfig) childBalancer.config;
    assertThat(edsConfig.clusterName).isEqualTo(CLUSTER);
    assertThat(edsConfig.edsServiceName).isNull();
    assertThat(edsConfig.lrsServerName).isNull();
    assertThat(edsConfig.localityPickingPolicy.getProvider().getPolicyName())
        .isEqualTo(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);  // hardcoded to weighted-target
    assertThat(edsConfig.endpointPickingPolicy.getProvider().getPolicyName())
        .isEqualTo("round_robin");
  }

  @Test
  public void clusterResourceNeverExist() {
    xdsClient.deliverResourceNotFound();
    assertThat(childBalancers).isEmpty();
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource " + CLUSTER + " is unavailable");
  }

  @Test
  public void clusterResourceRemoved() {
    xdsClient.deliverClusterInfo(null, null);
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.shutdown).isFalse();

    xdsClient.deliverResourceNotFound();
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource " + CLUSTER + " is unavailable");
  }

  @Test
  public void clusterResourceUpdated() {
    xdsClient.deliverClusterInfo(null, null);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    EdsConfig edsConfig = (EdsConfig) childBalancer.config;
    assertThat(edsConfig.clusterName).isEqualTo(CLUSTER);
    assertThat(edsConfig.edsServiceName).isNull();
    assertThat(edsConfig.lrsServerName).isNull();
    assertThat(edsConfig.localityPickingPolicy.getProvider().getPolicyName())
        .isEqualTo(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);  // hardcoded to weighted-target
    assertThat(edsConfig.endpointPickingPolicy.getProvider().getPolicyName())
        .isEqualTo("round_robin");

    String edsService = "service-bar.googleapis.com";
    String loadReportServer = "lrs-server.googleapis.com";
    xdsClient.deliverClusterInfo(edsService, loadReportServer);
    assertThat(childBalancers).containsExactly(childBalancer);
    edsConfig = (EdsConfig) childBalancer.config;
    assertThat(edsConfig.clusterName).isEqualTo(CLUSTER);
    assertThat(edsConfig.edsServiceName).isEqualTo(edsService);
    assertThat(edsConfig.lrsServerName).isEqualTo(loadReportServer);
    assertThat(edsConfig.localityPickingPolicy.getProvider().getPolicyName())
        .isEqualTo(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);  // hardcoded to weighted-target
    assertThat(edsConfig.endpointPickingPolicy.getProvider().getPolicyName())
        .isEqualTo("round_robin");
  }

  @Test
  public void receiveClusterResourceInfoWithUpstreamTlsContext() {
    loadBalancer.setXdsSecurity(true);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.CLIENT_KEY_FILE,
            CommonTlsContextTestsUtil.CLIENT_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    xdsClient.deliverClusterInfo(null, null, upstreamTlsContext);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    List<EquivalentAddressGroup> addresses = createEndpointAddresses(2);
    CreateSubchannelArgs args =
        CreateSubchannelArgs.newBuilder()
            .setAddresses(addresses)
            .build();
    Subchannel subchannel = childBalancer.helper.createSubchannel(args);
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      SslContextProviderSupplier supplier =
          eag.getAttributes().get(XdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      assertThat(supplier.getUpstreamTlsContext()).isEqualTo(upstreamTlsContext);
    }

    xdsClient.deliverClusterInfo(null, null);
    subchannel = childBalancer.helper.createSubchannel(args);
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      assertThat(eag.getAttributes().get(XdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER))
          .isNull();
    }

    upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.BAD_CLIENT_KEY_FILE,
            CommonTlsContextTestsUtil.BAD_CLIENT_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    xdsClient.deliverClusterInfo(null, null, upstreamTlsContext);
    subchannel = childBalancer.helper.createSubchannel(args);
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      SslContextProviderSupplier supplier =
          eag.getAttributes().get(XdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      assertThat(supplier.getUpstreamTlsContext()).isEqualTo(upstreamTlsContext);
    }
  }

  @Test
  public void subchannelStatePropagateFromDownstreamToUpstream() {
    xdsClient.deliverClusterInfo(null, null);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    List<EquivalentAddressGroup> addresses = createEndpointAddresses(2);
    CreateSubchannelArgs args =
        CreateSubchannelArgs.newBuilder()
            .setAddresses(addresses)
            .build();
    Subchannel subchannel = childBalancer.helper.createSubchannel(args);
    childBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    assertThat(currentPicker.pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isSameInstanceAs(subchannel);
  }

  @Test
  public void clusterDiscoveryError_beforeChildPolicyInstantiated_propagateToUpstream() {
    xdsClient.deliverError(Status.UNAUTHENTICATED.withDescription("permission denied"));
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAUTHENTICATED);
    assertThat(result.getStatus().getDescription()).isEqualTo("permission denied");
  }

  @Test
  public void clusterDiscoveryError_afterChildPolicyInstantiated_keepUsingCurrentCluster() {
    xdsClient.deliverClusterInfo(null, null);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    xdsClient.deliverError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(currentState).isNull();
    assertThat(currentPicker).isNull();
    assertThat(childBalancer.shutdown).isFalse();
  }

  @Test
  public void nameResolutionError_beforeChildPolicyInstantiated_returnErrorPickerToUpstream() {
    loadBalancer.handleNameResolutionError(
        Status.UNIMPLEMENTED.withDescription("not found"));
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNIMPLEMENTED);
    assertThat(result.getStatus().getDescription()).isEqualTo("not found");
  }

  @Test
  public void nameResolutionError_afterChildPolicyInstantiated_propagateToDownstream() {
    xdsClient.deliverClusterInfo(null, null);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    loadBalancer.handleNameResolutionError(
        Status.UNAVAILABLE.withDescription("cannot reach server"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("cannot reach server");
  }

  private static List<EquivalentAddressGroup> createEndpointAddresses(int n) {
    List<EquivalentAddressGroup> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      list.add(new EquivalentAddressGroup(mock(SocketAddress.class)));
    }
    return list;
  }

  private final class FakeXdsClient extends XdsClient {
    private CdsResourceWatcher watcher;

    @Override
    void watchCdsResource(String resourceName, CdsResourceWatcher watcher) {
      assertThat(resourceName).isEqualTo(CLUSTER);
      this.watcher = watcher;
    }

    @Override
    void cancelCdsResourceWatch(String resourceName, CdsResourceWatcher watcher) {
      assertThat(resourceName).isEqualTo(CLUSTER);
      assertThat(watcher).isSameInstanceAs(this.watcher);
      this.watcher = null;
    }

    @Override
    void shutdown() {
      // no-op
    }

    void deliverClusterInfo(
        @Nullable final String edsServiceName, @Nullable final String lrsServerName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          watcher.onChanged(
              CdsUpdate.newBuilder()
                  .setClusterName(CLUSTER)
                  .setEdsServiceName(edsServiceName)
                  .setLbPolicy("round_robin")  // only supported policy
                  .setLrsServerName(lrsServerName)
                  .build());
        }
      });
    }

    void deliverClusterInfo(
        @Nullable final String edsServiceName, @Nullable final String lrsServerName,
        final UpstreamTlsContext tlsContext) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          watcher.onChanged(
              CdsUpdate.newBuilder()
                  .setClusterName(CLUSTER)
                  .setEdsServiceName(edsServiceName)
                  .setLbPolicy("round_robin")  // only supported policy
                  .setLrsServerName(lrsServerName)
                  .setUpstreamTlsContext(tlsContext)
                  .build());
        }
      });
    }

    void deliverResourceNotFound() {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          watcher.onResourceDoesNotExist(CLUSTER);
        }
      });
    }

    void deliverError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          watcher.onError(error);
        }
      });
    }
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;

    FakeLoadBalancerProvider(String policyName) {
      this.policyName = policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeLoadBalancer balancer = new FakeLoadBalancer(policyName, helper);
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

  private final class FakeLoadBalancer extends LoadBalancer {
    private final String name;
    private final Helper helper;
    private Object config;
    private Status upstreamError;
    private boolean shutdown;

    FakeLoadBalancer(String name, Helper helper) {
      this.name = name;
      this.helper = helper;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
    }

    @Override
    public void handleNameResolutionError(Status error) {
      upstreamError = error;
    }

    @Override
    public void shutdown() {
      shutdown = true;
      childBalancers.remove(this);
    }

    void deliverSubchannelState(final Subchannel subchannel, ConnectivityState state) {
      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          return PickResult.withSubchannel(subchannel);
        }
      };
      helper.updateBalancingState(state, picker);
    }
  }

  private final class FakeLbHelper extends LoadBalancer.Helper {

    @Override
    public void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      return new FakeSubchannel(args.getAddresses());
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Deprecated
    @Override
    public NameResolver.Factory getNameResolverFactory() {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public String getAuthority() {
      return AUTHORITY;
    }
  }

  private static final class FakeSubchannel extends Subchannel {
    private final List<EquivalentAddressGroup> eags;

    private FakeSubchannel(List<EquivalentAddressGroup> eags) {
      this.eags = eags;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }
  }

  private static final class FakeTlsContextManager implements TlsContextManager {

    @Override
    public SslContextProvider findOrCreateClientSslContextProvider(
        UpstreamTlsContext upstreamTlsContext) {
      SslContextProvider sslContextProvider = mock(SslContextProvider.class);
      when(sslContextProvider.getUpstreamTlsContext()).thenReturn(upstreamTlsContext);
      return sslContextProvider;
    }

    @Override
    public SslContextProvider releaseClientSslContextProvider(
        SslContextProvider sslContextProvider) {
      // no-op
      return null;
    }

    @Override
    public SslContextProvider findOrCreateServerSslContextProvider(
        DownstreamTlsContext downstreamTlsContext) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public SslContextProvider releaseServerSslContextProvider(
        SslContextProvider sslContextProvider) {
      throw new UnsupportedOperationException("should not be called");
    }
  }
}
