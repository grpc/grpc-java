/*
 * Copyright 2014 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertEquals;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import io.grpc.xds.shaded.com.github.xds.data.orca.v3.OrcaLoadReport;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for all GRPC transport tests.
 *
 * <p> New tests should avoid using Mockito to support running on AppEngine.</p>
 */
public abstract class AbstractXdsInteropTest extends AbstractInteropTest {

  protected static final String TEST_ORCA_LB_POLICY_NAME = "test_backend_metrics_load_balancer";
  private final LinkedBlockingQueue<OrcaLoadReport> savedLoadReports = new LinkedBlockingQueue<>();
  private final LinkedBlockingQueue<OrcaLoadReport> savedOobLoadReports =
      new LinkedBlockingQueue<>();

  /**
   *  Test backend metrics reporting: expect the test client LB policy to receive load reports.
   */
  public void testOrca() {
    blockingStub.emptyCall(EMPTY);
    assertEquals(savedLoadReports.poll(), OrcaLoadReport.newBuilder()
        .putRequestCost("queue", 2.0).build());
    assertThat(savedLoadReports.isEmpty()).isTrue();
    assertEquals(savedOobLoadReports.poll(), OrcaLoadReport.newBuilder()
        .putUtilization("util", 0.4875).build());
  }

  protected class CustomBackendMetricsLoadBalancerProvider extends LoadBalancerProvider {

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
      return new CustomBackendMetricsLoadBalancer(helper);
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 0;
    }

    @Override
    public String getPolicyName() {
      return TEST_ORCA_LB_POLICY_NAME;
    }
  }

  class CustomBackendMetricsLoadBalancer extends LoadBalancer {
    private final Helper helper;
    private Subchannel subchannel;

    public CustomBackendMetricsLoadBalancer(Helper helper) {
      this.helper = checkNotNull(helper, "helper");
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
      if (subchannel == null) {
        OrcaOobUtil.OrcaReportingHelperWrapper wrappedHelper =
            OrcaOobUtil.getInstance().newOrcaReportingHelperWrapper(helper,
                new OrcaOobUtil.OrcaOobReportListener() {
                  @Override
                  public void onLoadReport(OrcaLoadReport orcaLoadReport) {
                    savedOobLoadReports.add(orcaLoadReport);
                  }
                });
        wrappedHelper.setReportingConfig(OrcaOobUtil.OrcaReportingConfig.newBuilder()
            .setReportInterval(1, TimeUnit.SECONDS)
            .build());
        final Subchannel subchannel = wrappedHelper.asHelper().createSubchannel(
            CreateSubchannelArgs.newBuilder()
                .setAddresses(servers)
                .build());
        subchannel.start(new SubchannelStateListener() {
          @Override
          public void onSubchannelState(ConnectivityStateInfo stateInfo) {
            processSubchannelState(subchannel, stateInfo);
          }
        });
        this.subchannel = subchannel;

        helper.updateBalancingState(CONNECTING,
            new MayReportLoadPicker(PickResult.withSubchannel(subchannel)));
        subchannel.requestConnection();
      } else {
        subchannel.updateAddresses(servers);
      }
    }

    private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      ConnectivityState currentState = stateInfo.getState();
      if (currentState == SHUTDOWN) {
        return;
      }
      if (stateInfo.getState() == TRANSIENT_FAILURE || stateInfo.getState() == IDLE) {
        helper.refreshNameResolution();
      }

      SubchannelPicker picker;
      switch (currentState) {
        case IDLE:
          subchannel.requestConnection();
          picker = new MayReportLoadPicker(PickResult.withNoResult());
          break;
        case CONNECTING:
          picker = new MayReportLoadPicker(PickResult.withNoResult());
          break;
        case READY:
          picker = new MayReportLoadPicker(PickResult.withSubchannel(subchannel));
          break;
        case TRANSIENT_FAILURE:
          picker = new MayReportLoadPicker(PickResult.withError(stateInfo.getStatus()));
          break;
        default:
          throw new IllegalArgumentException("Unsupported state:" + currentState);
      }
      helper.updateBalancingState(currentState, picker);
    }

    @Override
    public void handleNameResolutionError(Status error) {
    }

    @Override
    public void shutdown() {
      if (subchannel != null) {
        subchannel.shutdown();
      }
    }

    private final class MayReportLoadPicker extends SubchannelPicker {
      private PickResult result;

      public MayReportLoadPicker(PickResult result) {
        this.result = checkNotNull(result, "result");
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        if (result.getSubchannel() == null) {
          return result;
        }
        return PickResult.withSubchannel(
            result.getSubchannel(),
            OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                new OrcaPerRequestUtil.OrcaPerRequestReportListener() {
                  @Override
                  public void onLoadReport(OrcaLoadReport orcaLoadReport) {
                    savedLoadReports.add(orcaLoadReport);
                  }
                }));
      }
    }
  }
}
