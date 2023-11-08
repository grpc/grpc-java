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

import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.LoadBalancer.HEALTH_CONSUMER_LISTENER_ARG_KEY;
import static io.grpc.LoadBalancer.HEALTH_PRODUCER_LISTENER_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static com.google.common.truth.Truth.assertThat;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class HealthProducerUtilTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private LoadBalancer.Helper mockHelper;

  @Mock
  private LoadBalancer.Subchannel mockSubchannel;

  @Mock
  private LoadBalancer.SubchannelStateListener healthConsumerListener;

  @Captor
  ArgumentCaptor<LoadBalancer.CreateSubchannelArgs> createSubchannelArgCaptor;

  private static final String HEALTH_PRODUCER_NAME = "producer";

  @Before
  public void setup() {
    when(mockHelper.createSubchannel(
        any(LoadBalancer.CreateSubchannelArgs.class))).thenReturn(mockSubchannel);
    when(mockSubchannel.getAttributes()).thenReturn(Attributes.EMPTY);
  }

  @Test
  public void healthStatusNotification() {
    LoadBalancer.Helper producerHelper = new HealthProducerUtil.HealthProducerHelper(
        mockHelper, HEALTH_PRODUCER_NAME);
    HealthProducerUtil.HealthProducerSubchannel subchannel =
        (HealthProducerUtil.HealthProducerSubchannel) producerHelper.createSubchannel(
        LoadBalancer.CreateSubchannelArgs.newBuilder()
            .addOption(HEALTH_CONSUMER_LISTENER_ARG_KEY, healthConsumerListener)
            .setAddresses(new EquivalentAddressGroup(
                Collections.singletonList(new InetSocketAddress(10))))
            .build());
    verify(mockHelper).createSubchannel(createSubchannelArgCaptor.capture());

    assertThat(mockSubchannel).isSameInstanceAs(subchannel.delegate());
    HealthProducerUtil.HealthCheckProducerListener producerListener =
        subchannel.getHealthListener();
    assertThat(subchannel.getAttributes().get(HEALTH_PRODUCER_LISTENER_KEY))
        .isEqualTo(producerListener);
    assertThat(createSubchannelArgCaptor.getValue().getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY))
        .isEqualTo(producerListener);

    producerListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verifyNoInteractions(healthConsumerListener);

    // de-dup null
    producerListener.thisHealthState(null);
    verifyNoMoreInteractions(healthConsumerListener);

    // upper stream is healthy, pass through this health status
    producerListener.thisHealthState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    verify(healthConsumerListener).onSubchannelState(
        eq(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE))
    );
    producerListener.thisHealthState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(healthConsumerListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));

    // reset
    producerListener.thisHealthState(null);
    verify(healthConsumerListener).onSubchannelState(null);

    // upper stream is unhealthy, always report unhealthy
    producerListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(
        Status.DATA_LOSS.withDescription("ejected")));
    verifyNoMoreInteractions(healthConsumerListener);
    producerListener.thisHealthState(ConnectivityStateInfo.forNonError(READY));
    verify(healthConsumerListener, times(2)).onSubchannelState(eq(
        ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE)));
    producerListener.thisHealthState(ConnectivityStateInfo.forTransientFailure(
        Status.UNAVAILABLE.withDescription("unhealthy")));

    verify(healthConsumerListener).onSubchannelState(
        unavailableStateWithMsg("unhealthy"));
    producerListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verifyNoMoreInteractions(healthConsumerListener);
    producerListener.thisHealthState(ConnectivityStateInfo.forNonError(READY));
    producerListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verify(healthConsumerListener).onSubchannelState(eq(
        ConnectivityStateInfo.forNonError(READY)));
  }

  private ConnectivityStateInfo unavailableStateWithMsg(final String expectedMsg) {
    return MockitoHamcrest.argThat(
        new org.hamcrest.BaseMatcher<ConnectivityStateInfo>() {
          @Override
          public boolean matches(Object item) {
            if (!(item instanceof ConnectivityStateInfo)) {
              return false;
            }
            ConnectivityStateInfo info = (ConnectivityStateInfo) item;
            if (!info.getState().equals(TRANSIENT_FAILURE)) {
              return false;
            }
            Status error = info.getStatus();
            if (!error.getCode().equals(Status.Code.UNAVAILABLE)) {
              return false;
            }
            if (!expectedMsg.equals(error.getDescription())) {
              return false;
            }
            return true;
          }

          @Override
          public void describeTo(org.hamcrest.Description desc) {
            desc.appendText("Matches unavailable state with msg='" + expectedMsg + "'");
          }
        });
  }
}
