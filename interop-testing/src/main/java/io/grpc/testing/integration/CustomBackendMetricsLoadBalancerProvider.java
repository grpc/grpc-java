/*
 * Copyright 2022 The gRPC Authors
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

import static io.grpc.testing.integration.AbstractInteropTest.ORCA_OOB_REPORT_KEY;
import static io.grpc.testing.integration.AbstractInteropTest.ORCA_RPC_REPORT_KEY;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.services.MetricReport;
import io.grpc.testing.integration.Messages.TestOrcaReport;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements a test LB policy that receives ORCA load reports.
 */
final class CustomBackendMetricsLoadBalancerProvider extends LoadBalancerProvider {

  static final String TEST_ORCA_LB_POLICY_NAME = "test_backend_metrics_load_balancer";
  private volatile TestOrcaReport latestOobReport;

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

  private final class CustomBackendMetricsLoadBalancer extends ForwardingLoadBalancer {
    private LoadBalancer delegate;

    public CustomBackendMetricsLoadBalancer(Helper helper) {
      this.delegate = LoadBalancerRegistry.getDefaultRegistry()
          .getProvider("pick_first")
          .newLoadBalancer(new CustomBackendMetricsLoadBalancerHelper(helper));
    }

    @Override
    public LoadBalancer delegate() {
      return delegate;
    }

    private final class CustomBackendMetricsLoadBalancerHelper
        extends ForwardingLoadBalancerHelper {
      private final Helper orcaHelper;

      public CustomBackendMetricsLoadBalancerHelper(Helper helper) {
        this.orcaHelper = OrcaOobUtil.newOrcaReportingHelper(helper);
      }

      @Override
      public Subchannel createSubchannel(CreateSubchannelArgs args) {
        Subchannel subchannel = super.createSubchannel(args);
        OrcaOobUtil.setListener(subchannel, new OrcaOobUtil.OrcaOobReportListener() {
              @Override
              public void onLoadReport(MetricReport orcaLoadReport) {
                latestOobReport = fromCallMetricReport(orcaLoadReport);
              }
            },
            OrcaOobUtil.OrcaReportingConfig.newBuilder()
                .setReportInterval(1, TimeUnit.SECONDS)
                .build()
        );
        return subchannel;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        delegate().updateBalancingState(newState, new MayReportLoadPicker(newPicker));
      }

      @Override
      public Helper delegate() {
        return orcaHelper;
      }
    }

    private final class MayReportLoadPicker extends SubchannelPicker {
      private SubchannelPicker delegate;

      public MayReportLoadPicker(SubchannelPicker delegate) {
        this.delegate = delegate;
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        PickResult result = delegate.pickSubchannel(args);
        if (result.getSubchannel() == null) {
          return result;
        }
        AtomicReference<TestOrcaReport> reportRef =
            args.getCallOptions().getOption(ORCA_OOB_REPORT_KEY);
        if (reportRef != null) {
          reportRef.set(latestOobReport);
        }

        return PickResult.withSubchannel(
            result.getSubchannel(),
            OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                new OrcaPerRequestUtil.OrcaPerRequestReportListener() {
                  @Override
                  public void onLoadReport(MetricReport callMetricReport) {
                    AtomicReference<TestOrcaReport> reportRef =
                        args.getCallOptions().getOption(ORCA_RPC_REPORT_KEY);
                    if (reportRef != null) {
                      reportRef.set(fromCallMetricReport(callMetricReport));
                    }
                  }
                }));
      }
    }
  }

  private static TestOrcaReport fromCallMetricReport(MetricReport callMetricReport) {
    return TestOrcaReport.newBuilder()
        .setCpuUtilization(callMetricReport.getCpuUtilization())
        .setMemoryUtilization(callMetricReport.getMemoryUtilization())
        .putAllRequestCost(callMetricReport.getRequestCostMetrics())
        .putAllUtilization(callMetricReport.getUtilizationMetrics())
        .build();
  }
}
