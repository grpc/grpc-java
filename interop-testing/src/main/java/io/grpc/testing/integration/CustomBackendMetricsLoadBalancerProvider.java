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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.testing.integration.AbstractInteropTest.ORCA_RPC_REPORT_KEY;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.testing.integration.OrcaReport.TestOrcaReport;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import io.grpc.xds.shaded.com.github.xds.data.orca.v3.OrcaLoadReport;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Abstract base class for all GRPC transport tests.
 *
 * <p> New tests should avoid using Mockito to support running on AppEngine.</p>
 */
final class CustomBackendMetricsLoadBalancerProvider extends LoadBalancerProvider {

  static final String TEST_ORCA_LB_POLICY_NAME = "test_backend_metrics_load_balancer";
  @Nullable private final AtomicReference<TestOrcaReport> oobReportListenerRef;

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new CustomBackendMetricsLoadBalancer(helper);
  }

  public CustomBackendMetricsLoadBalancerProvider(
      AtomicReference<TestOrcaReport> oobReportListenerRef) {
    this.oobReportListenerRef = oobReportListenerRef;
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

  class CustomBackendMetricsLoadBalancer extends LoadBalancer {
    private final Helper helper;
    private Subchannel subchannel;

    public CustomBackendMetricsLoadBalancer(Helper helper) {
      this.helper = OrcaOobUtil.newOrcaReportingHelper(checkNotNull(helper, "helper"));
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
      if (subchannel == null) {
        final Subchannel subchannel = helper.createSubchannel(
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
        OrcaOobUtil.setListener(result.getSubchannel(), new OrcaOobUtil.OrcaOobReportListener() {
              @Override
              public void onLoadReport(OrcaLoadReport orcaLoadReport) {
                if (oobReportListenerRef != null) {
                  oobReportListenerRef
                      .set(TestOrcaReport.newBuilder()
                      .setCpuUtilization(orcaLoadReport.getCpuUtilization())
                      .setMemoryUtilization(orcaLoadReport.getMemUtilization())
                      .putAllRequestCost(orcaLoadReport.getRequestCostMap())
                      .putAllUtilization(orcaLoadReport.getUtilizationMap())
                      .build());
                }
              }
            },
            OrcaOobUtil.OrcaReportingConfig.newBuilder()
                .setReportInterval(1, TimeUnit.SECONDS)
                .build()
        );
        return PickResult.withSubchannel(
            result.getSubchannel(),
            OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                new OrcaPerRequestUtil.OrcaPerRequestReportListener() {
                  @Override
                  public void onLoadReport(OrcaLoadReport orcaLoadReport) {
                    AtomicReference<TestOrcaReport> reportRef =
                        args.getCallOptions().getOption(ORCA_RPC_REPORT_KEY);
                    reportRef.set(TestOrcaReport.newBuilder()
                        .setCpuUtilization(orcaLoadReport.getCpuUtilization())
                        .setMemoryUtilization(orcaLoadReport.getMemUtilization())
                        .putAllRequestCost(orcaLoadReport.getRequestCostMap())
                        .putAllUtilization(orcaLoadReport.getUtilizationMap())
                        .build());
                  }
                }));
      }
    }
  }
}
