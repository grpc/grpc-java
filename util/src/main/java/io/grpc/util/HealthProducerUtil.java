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

import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Internal;
import io.grpc.LoadBalancer;

/**
 * Utility function used by health producer systems to build health notification chain.
 * The leaf health consumer is pick first. Each health producer using this helper.
 * The most root health producer in the chain will fan out the subchannel state change to both
 * state listener and health listener.
 */
@Internal
public class HealthProducerUtil {
  public static final class HealthProducerHelper extends ForwardingLoadBalancerHelper {
    private final LoadBalancer.Helper delegate;

    public HealthProducerHelper(LoadBalancer.Helper helper) {
      this.delegate = checkNotNull(helper, "helper");
    }

    @Override
    public LoadBalancer.Subchannel createSubchannel(LoadBalancer.CreateSubchannelArgs args) {
      LoadBalancer.SubchannelStateListener healthConsumerListener =
          args.getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY);
      LoadBalancer.Subchannel delegateSubchannel = super.createSubchannel(args);
      boolean alreadyParent = (delegateSubchannel.getAttributes() == null
          || delegateSubchannel.getAttributes().get(HAS_HEALTH_PRODUCER_LISTENER_KEY) == null)
          && healthConsumerListener != null;
      if (!alreadyParent) {
        return delegateSubchannel;
      }
      return new HealthProducerSubchannel(delegateSubchannel, healthConsumerListener);
    }

    @Override
    protected LoadBalancer.Helper delegate() {
      return delegate;
    }
  }

  public static final class HealthProducerSubchannel extends ForwardingSubchannel {
    private final LoadBalancer.Subchannel delegate;
    private final LoadBalancer.SubchannelStateListener healthListener;

    HealthProducerSubchannel(LoadBalancer.Subchannel delegate,
                             LoadBalancer.SubchannelStateListener healthListener) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.healthListener = healthListener;
    }

    @Override
    public LoadBalancer.Subchannel delegate() {
      return delegate;
    }

    @Override
    public void start(LoadBalancer.SubchannelStateListener listener) {
      delegate.start(new LoadBalancer.SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          listener.onSubchannelState(newState);
          healthListener.onSubchannelState(newState);
        }
      });
    }

    @Override
    public Attributes getAttributes() {
      return super.getAttributes().toBuilder().set(HAS_HEALTH_PRODUCER_LISTENER_KEY, Boolean.TRUE)
            .build();
    }
  }
}
