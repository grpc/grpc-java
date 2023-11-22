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
 * Utility functions used by health producer systems to build health notification chain, via
 * {@link LoadBalancer.CreateSubchannelArgs}.
 * The leaf health consumer is pick first. Each health producer uses this helper.
 * The health producers should make state listener a pass-through and manipulate the
 * {@link LoadBalancer.CreateSubchannelArgs} for health notifications.
 * The root health producer in the chain will fan out the subchannel state change to both
 * state listener and health listener.
 */
@Internal
public class HealthProducerUtil {

  /**
   * A new {@link LoadBalancer.Helper} that detects health listener parent and fans out the
   * subchannel state change to both state listener and health listener.
   * <p>Example usage:
   * <pre>{@code
   * class HealthProducerLB {
   *   private final LoadBalancer.Helper helper;
   *   public HealthProducer(Helper helper) {
   *     this.helper = new MyHelper(HealthCheckUtil.HealthCheckHelper(helper));
   *   }
   *   class MyHelper implements LoadBalancer.Helper {
   *     public void CreateSubchannel(CreateSubchannelArgs args) {
   *       SubchannelStateListener originalListener =
   *         args.getAttributes(HEALTH_CHECK_CONSUMER_LISTENER);
   *       if (hcListener != null) {
   *         // Implement a health listener that producers health check information.
   *         SubchannelStateListener myListener = MyHealthListener(originalListener);
   *         args = args.toBuilder.setOption(HEALTH_CHECK_CONSUMER_LISTENER, myListener);
   *       }
   *       return super.createSubchannel(args);
   *     }
   *   }
   *  }
   * }</pre>
   */
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

  /**
   * The parent subchannel in the health check producer LB chain. It duplicates subchannel state to
   * both the state listener and health listener.
   */
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
