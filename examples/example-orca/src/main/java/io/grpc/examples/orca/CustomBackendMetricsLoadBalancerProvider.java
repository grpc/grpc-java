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

package io.grpc.examples.orca;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.services.MetricReport;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import java.util.concurrent.TimeUnit;

/**
 * Implements a test LB policy that receives ORCA load reports.
 * The load balancer mostly delegates to {@link io.grpc.internal.PickFirstLoadBalancerProvider},
 * in addition, it installs {@link OrcaOobUtil.OrcaOobReportListener} and
 * {@link OrcaPerRequestUtil.OrcaPerRequestReportListener} to be notified with backend metrics.
 */
final class CustomBackendMetricsLoadBalancerProvider extends LoadBalancerProvider {

  static final String EXAMPLE_LOAD_BALANCER = "example_backend_metrics_load_balancer";

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
    return 5;
  }

  @Override
  public String getPolicyName() {
    return EXAMPLE_LOAD_BALANCER;
  }

  private final class CustomBackendMetricsLoadBalancer extends ForwardingLoadBalancer {
    private LoadBalancer delegate;

    public CustomBackendMetricsLoadBalancer(LoadBalancer.Helper helper) {
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
      private final LoadBalancer.Helper orcaHelper;

      public CustomBackendMetricsLoadBalancerHelper(LoadBalancer.Helper helper) {
        this.orcaHelper = OrcaOobUtil.newOrcaReportingHelper(helper);
      }

      @Override
      public LoadBalancer.Subchannel createSubchannel(LoadBalancer.CreateSubchannelArgs args) {
        LoadBalancer.Subchannel subchannel = super.createSubchannel(args);
        // Installs ORCA OOB metrics reporting listener and configures to receive report every 1s.
        // The interval can not be smaller than server minimum report interval configuration,
        // otherwise it is treated as server minimum report interval.
        OrcaOobUtil.setListener(subchannel, new OrcaOobUtil.OrcaOobReportListener() {
              @Override
              public void onLoadReport(MetricReport orcaLoadReport) {
                System.out.println("Example load balancer received OOB metrics report:\n"
                    + orcaLoadReport);
              }
            },
            OrcaOobUtil.OrcaReportingConfig.newBuilder()
                .setReportInterval(1, TimeUnit.SECONDS)
                .build()
        );
        return subchannel;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, LoadBalancer.SubchannelPicker newPicker) {
        delegate().updateBalancingState(newState, new MayReportLoadPicker(newPicker));
      }

      @Override
      public LoadBalancer.Helper delegate() {
        return orcaHelper;
      }
    }

    private final class MayReportLoadPicker extends LoadBalancer.SubchannelPicker {
      private LoadBalancer.SubchannelPicker delegate;

      public MayReportLoadPicker(LoadBalancer.SubchannelPicker delegate) {
        this.delegate = delegate;
      }

      @Override
      public LoadBalancer.PickResult pickSubchannel(LoadBalancer.PickSubchannelArgs args) {
        LoadBalancer.PickResult result = delegate.pickSubchannel(args);
        if (result.getSubchannel() == null) {
          return result;
        }
        // Installs ORCA per-query metrics reporting listener.
        final OrcaPerRequestUtil.OrcaPerRequestReportListener orcaListener =
            new OrcaPerRequestUtil.OrcaPerRequestReportListener() {
          @Override
          public void onLoadReport(MetricReport orcaLoadReport) {
            System.out.println("Example load balancer received per-rpc metrics report:\n"
                + orcaLoadReport);
          }
        };
        if (result.getStreamTracerFactory() == null) {
          return LoadBalancer.PickResult.withSubchannel(
              result.getSubchannel(),
              OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(orcaListener));
        } else {
            return LoadBalancer.PickResult.withSubchannel(
                result.getSubchannel(),
                OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                    result.getStreamTracerFactory(), orcaListener));
        }
      }
    }
  }
}
