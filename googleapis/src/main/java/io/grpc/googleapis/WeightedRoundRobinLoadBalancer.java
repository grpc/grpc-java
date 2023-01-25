/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.googleapis;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.services.MetricReport;
import io.grpc.util.AbstractRoundRobinLoadBalancer;
import io.grpc.util.ForwardingSubchannel;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.orca.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.HashSet;
import java.util.List;

/**
 * A {@link AbstractRoundRobinLoadBalancer} that provides round-robin load-balancing over the {@link
 * EquivalentAddressGroup}s from the {@link NameResolver}.
 */
final class WeightedRoundRobinLoadBalancer extends AbstractRoundRobinLoadBalancer {
  private final Helper orcaOobHelper;
  private WeightedRoundRobinLoadBalancerConfig config;

  WeightedRoundRobinLoadBalancer(Helper helper) {
    super(helper);
    this.orcaOobHelper = OrcaOobUtil.newOrcaReportingHelper(helper);
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    config =
        (WeightedRoundRobinLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

    return false;
  }

  @Override
  protected Subchannel createSubchannel(CreateSubchannelArgs args) {
    // add oob listener
    if (config.enableOobLoadReport) {
      new WeightedRoundRobinSubchannel(orcaOobHelper.createSubchannel(args));
    }
    return helper.createSubchannel(args);
  }

  @Override
  protected RoundRobinPicker createReadyPicker(List<Subchannel> activeSubchannelList,
                                               int startIndex) {
    return new WeightedRoundRobinPicker(activeSubchannelList, startIndex);
  }

  static final class WeightedRoundRobinSubchannel extends ForwardingSubchannel {
    private Subchannel delegate;
    OrcaOobReportListener oobListener = new OrcaOobReportListener() {
      @Override
      public void onLoadReport(MetricReport report) {
        updateWeight(report);
      }
    };
    OrcaPerRequestReportListener perRpcListener = new OrcaPerRequestReportListener() {
      @Override
      public void onLoadReport(MetricReport report) {
        updateWeight(report);
      }
    };
    long lastUpdated;
    long nonEmptySince;
    long weight;

    public WeightedRoundRobinSubchannel(Subchannel delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    static void updateWeight(MetricReport report) {
      long newWeight = report.getCpuUtilization() == 0 ? 0 :

    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }
  }

  @VisibleForTesting
  final class WeightedRoundRobinPicker extends RoundRobinPicker {

    private final List<Subchannel> list; // non-empty
    @SuppressWarnings("unused")
    private volatile int index;

    WeightedRoundRobinPicker(List<Subchannel> list, int startIndex) {
      Preconditions.checkArgument(!list.isEmpty(), "empty list");
      this.list = list;
      this.index = startIndex - 1;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      // Subchannel subchannel = ; // A WeightedSubchannel
      // if per-request:
      // PickResult.withSubchannel(
      //   subchannel,
      //   OrcaPerRequestReportUtil.getInstance()
      //   .newOrcaClientStreamTracerFactory(subchannel.listener));
      // else
      if (config.enableOobLoadReport) {
        return null;
      }
      return PickResult.withSubchannel(nextSubchannel());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(WeightedRoundRobinPicker.class)
          .add("list", list).toString();
    }

    private Subchannel nextSubchannel() {
      return null;
    }

    @VisibleForTesting
    List<Subchannel> getList() {
      return list;
    }

    @Override
    public boolean isEquivalentTo(RoundRobinPicker picker) {
      if (!(picker instanceof WeightedRoundRobinPicker)) {
        return false;
      }
      WeightedRoundRobinPicker other = (WeightedRoundRobinPicker) picker;
      // the lists cannot contain duplicate subchannels
      return other == this
          || (list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list));
    }
  }

  static final class WeightedRoundRobinLoadBalancerConfig {
    final Long blackoutPeriodNanos;
    final Long weightExpirationPeriodNanos;
    final Boolean enableOobLoadReport;
    final Long oobReportingPeriodNanos;
    final Long weightUpdatePeriodNanos;


    private WeightedRoundRobinLoadBalancerConfig(Long blackoutPeriodNanos,
                                                 Long weightExpirationPeriodNanos,
                                                 Boolean enableOobLoadReport,
                                                 Long oobReportingPeriodNanos,
                                                 Long weightUpdatePeriodNanos) {
      this.blackoutPeriodNanos = blackoutPeriodNanos;
      this.weightExpirationPeriodNanos = weightExpirationPeriodNanos;
      this.enableOobLoadReport = enableOobLoadReport;
      this.oobReportingPeriodNanos = oobReportingPeriodNanos;
      this.weightUpdatePeriodNanos = weightUpdatePeriodNanos;
    }

    static class Builder {
      Long blackoutPeriodNanos = 10_000_000_000L; // 10s
      Long weightExpirationPeriodNanos = 180_000_000_000L; //3min
      Boolean enableOobLoadReport = false;
      Long oobReportingPeriodNanos = 10_000_000_000L; // 10s
      Long weightUpdatePeriodNanos = 1_000_000_000L; // 10s

      Builder setBlackoutPeriodNanos(Long blackoutPeriodNanos) {
        checkArgument(blackoutPeriodNanos != null);
        this.blackoutPeriodNanos = blackoutPeriodNanos;
        return this;
      }

      Builder setWeightExpirationPeriodNanos(Long weightExpirationPeriodNanos) {
        checkArgument(weightExpirationPeriodNanos != null);
        this.weightExpirationPeriodNanos = weightExpirationPeriodNanos;
        return this;
      }

      Builder setEnableOobLoadReport(Boolean enableOobLoadReport) {
        checkArgument(enableOobLoadReport != null);
        this.enableOobLoadReport = enableOobLoadReport;
        return this;
      }

      Builder setOobReportingPeriodNanos(Long oobReportingPeriodNanos) {
        checkArgument(oobReportingPeriodNanos != null);
        this.oobReportingPeriodNanos = oobReportingPeriodNanos;
        return this;
      }

      Builder setWeightUpdatePeriodNanos(Long weightUpdatePeriodNanos) {
        checkArgument(weightUpdatePeriodNanos != null);
        this.weightUpdatePeriodNanos = weightUpdatePeriodNanos;
        return this;
      }

      WeightedRoundRobinLoadBalancerConfig build() {
        return new WeightedRoundRobinLoadBalancerConfig(blackoutPeriodNanos,
            weightExpirationPeriodNanos, enableOobLoadReport, oobReportingPeriodNanos,
            weightUpdatePeriodNanos);
      }
    }






  }


}
