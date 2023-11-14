/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.LoadBalancer.HAS_HEALTH_PRODUCER_LISTENER_KEY;
import static io.grpc.LoadBalancer.HEALTH_CONSUMER_LISTENER_ARG_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Utility function used by health producer systems to build health notification chain in a
 * generic way. The leaf health consumer is pick first. Each health producer using this helper
 * will get a {@link HealthCheckProducerListener} from a
 * {@link HealthProducerSubchannel}. They should call
 * {@link HealthCheckProducerListener#thisHealthState} to update health status.
 * The most root health producer in the chain will automatically notify its immediately child an
 * initial healthy status to kick the notification chain off.
 */
@Internal
public class HealthProducerUtil {
  private static final Logger log = Logger.getLogger(HealthProducerUtil.class.getName());

  public static final class HealthProducerHelper extends ForwardingLoadBalancerHelper {
    private final LoadBalancer.Helper delegate;
    private final String name;

    public HealthProducerHelper(LoadBalancer.Helper helper, String healthProducerName) {
      this.delegate = checkNotNull(helper, "helper");
      this.name = checkNotNull(healthProducerName, "healthProducerName");
    }

    @Override
    public LoadBalancer.Subchannel createSubchannel(LoadBalancer.CreateSubchannelArgs args) {
      LoadBalancer.SubchannelStateListener healthConsumerListener =
          args.getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY);
      HealthCheckProducerListener healthProducerListener = null;
      if (healthConsumerListener != null) {
        healthProducerListener = new HealthCheckProducerListener(
            healthConsumerListener, name);
        args = args.toBuilder().addOption(HEALTH_CONSUMER_LISTENER_ARG_KEY, healthProducerListener)
            .build();
      }
      LoadBalancer.Subchannel delegateSubchannel = super.createSubchannel(args);
      if (delegateSubchannel.getAttributes() != null
          && delegateSubchannel.getAttributes().get(HAS_HEALTH_PRODUCER_LISTENER_KEY)
          == null && healthProducerListener != null) {
        healthProducerListener.onSubchannelState(
            ConnectivityStateInfo.forNonError(ConnectivityState.READY));
      }
      return new HealthProducerSubchannel(delegateSubchannel, healthProducerListener);
    }

    @Override
    protected LoadBalancer.Helper delegate() {
      return delegate;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("delegate", delegate())
          .add("name", name)
          .toString();
    }
  }

  public static final class HealthProducerSubchannel extends ForwardingSubchannel {
    private final LoadBalancer.Subchannel delegate;
    @Nullable private final HealthCheckProducerListener healthListener;

    HealthProducerSubchannel(LoadBalancer.Subchannel delegate,
                             @Nullable HealthCheckProducerListener healthListener) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.healthListener = healthListener;
    }

    public HealthCheckProducerListener getHealthListener() {
      return healthListener;
    }

    @Override
    public LoadBalancer.Subchannel delegate() {
      return delegate;
    }

    @Override
    public Attributes getAttributes() {
      if (healthListener != null) {
        return super.getAttributes().toBuilder().set(HAS_HEALTH_PRODUCER_LISTENER_KEY, Boolean.TRUE)
            .build();
      }
      return super.getAttributes();
    }
  }

  /**
   * Used by a health producer system to construct the subchannel health notification chain and
   * notify aggregated health status with cached health status.
   * A parent health producer system then will call {@link #onSubchannelState} to notify health
   * status change, and {@link #thisHealthState} is used by the current child health producer.
   * Notification waits on both parent and child health status present. Users may reset health
   * status to prevent stale health status to be consumed.
   * */
  public static final class HealthCheckProducerListener
      implements LoadBalancer.SubchannelStateListener {

    private ConnectivityStateInfo upperStreamHealthStatus = null;
    private ConnectivityStateInfo thisHealthState = null;
    private ConnectivityStateInfo concludedHealthStatus = null;
    private final String healthProducerName;
    private LoadBalancer.SubchannelStateListener delegate;

    public HealthCheckProducerListener(LoadBalancer.SubchannelStateListener delegate,
                                       String thisHealthProducerName) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.healthProducerName = checkNotNull(thisHealthProducerName, "thisHealthProducerName");
    }

    @Override
    public void onSubchannelState(ConnectivityStateInfo newState) {
      upperStreamHealthStatus = newState;
      notifyHealth();
    }

    @VisibleForTesting
    ConnectivityStateInfo getUpperStreamHealthStatus() {
      return upperStreamHealthStatus;
    }

    @VisibleForTesting
    ConnectivityStateInfo getThisHealthState() {
      return thisHealthState;
    }

    public void thisHealthState(ConnectivityStateInfo newState) {
      thisHealthState = newState;
      notifyHealth();
    }

    private void notifyHealth() {
      ConnectivityStateInfo next = concludedHealthStatus;
      if (upperStreamHealthStatus == null || thisHealthState == null) {
        next = null;
      } else if (ConnectivityState.READY == upperStreamHealthStatus.getState()) {
        next = thisHealthState;
      } else if (ConnectivityState.TRANSIENT_FAILURE == upperStreamHealthStatus.getState()) {
        // todo: include producer name and upper stream health status in description
        next = ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE.withDescription(
            thisHealthState.getStatus().getDescription()));
      }
      if (!Objects.equal(concludedHealthStatus, next)) {
        concludedHealthStatus = next;
        log.log(Level.FINE, "Health producer " + healthProducerName + ":" + next);
        delegate.onSubchannelState(next);
      }
    }
  }
}
