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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.LoadBalancer.HAS_HEALTH_PRODUCER_LISTENER_KEY;
import static io.grpc.LoadBalancer.HEALTH_CONSUMER_LISTENER_ARG_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import java.net.InetSocketAddress;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class HealthProducerHelperTest {
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

  @Before
  public void setup() {
    when(mockHelper.createSubchannel(
        any(LoadBalancer.CreateSubchannelArgs.class))).thenReturn(mockSubchannel);
    when(mockSubchannel.getAttributes()).thenReturn(Attributes.EMPTY);
  }

  @Test
  public void helper_parentProducer() {
    LoadBalancer.Helper producerHelper = new HealthProducerHelper(mockHelper);
    HealthProducerHelper.HealthProducerSubchannel subchannel =
        (HealthProducerHelper.HealthProducerSubchannel) producerHelper.createSubchannel(
            LoadBalancer.CreateSubchannelArgs.newBuilder()
                .addOption(HEALTH_CONSUMER_LISTENER_ARG_KEY, healthConsumerListener)
                .setAddresses(new EquivalentAddressGroup(
                    Collections.singletonList(new InetSocketAddress(10))))
                .build());
    verify(mockHelper).createSubchannel(createSubchannelArgCaptor.capture());
    assertThat(mockSubchannel).isSameInstanceAs(subchannel.delegate());
    assertThat(subchannel.getAttributes().get(HAS_HEALTH_PRODUCER_LISTENER_KEY))
        .isEqualTo(Boolean.TRUE);
    assertThat(createSubchannelArgCaptor.getValue().getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY))
        .isEqualTo(healthConsumerListener);

    LoadBalancer.SubchannelStateListener listener =
        mock(LoadBalancer.SubchannelStateListener.class);
    subchannel.start(listener);
    ArgumentCaptor<LoadBalancer.SubchannelStateListener> listenerCaptor =
        ArgumentCaptor.forClass(LoadBalancer.SubchannelStateListener.class);
    verify(mockSubchannel).start(listenerCaptor.capture());
    listenerCaptor.getValue().onSubchannelState(
        ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    verify(healthConsumerListener)
        .onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    verify(listener)
        .onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
  }

  @Test
  public void helper_notParentProducer() {
    when(mockHelper.createSubchannel(any(LoadBalancer.CreateSubchannelArgs.class))).thenReturn(
        new HealthProducerHelper.HealthProducerSubchannel(
            mockSubchannel, mock(LoadBalancer.SubchannelStateListener.class)));
    LoadBalancer.Helper producerHelper = new HealthProducerHelper(mockHelper);
    HealthProducerHelper.HealthProducerSubchannel subchannel =
        (HealthProducerHelper.HealthProducerSubchannel) producerHelper.createSubchannel(
            LoadBalancer.CreateSubchannelArgs.newBuilder()
                .addOption(HEALTH_CONSUMER_LISTENER_ARG_KEY, healthConsumerListener)
                .setAddresses(new EquivalentAddressGroup(
                    Collections.singletonList(new InetSocketAddress(10))))
                .build());
    verify(mockHelper).createSubchannel(createSubchannelArgCaptor.capture());
    assertThat(mockSubchannel).isSameInstanceAs(subchannel.delegate());
    assertThat(subchannel.getAttributes().get(HAS_HEALTH_PRODUCER_LISTENER_KEY))
        .isEqualTo(Boolean.TRUE);
    LoadBalancer.SubchannelStateListener listener =
        mock(LoadBalancer.SubchannelStateListener.class);
    subchannel.start(listener);
    ArgumentCaptor<LoadBalancer.SubchannelStateListener> listenerCaptor =
        ArgumentCaptor.forClass(LoadBalancer.SubchannelStateListener.class);
    verify(mockSubchannel).start(listenerCaptor.capture());
    LoadBalancer.SubchannelStateListener parentListener = listenerCaptor.getValue();
    parentListener.onSubchannelState(
        ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    verify(listener).onSubchannelState(
        ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    parentListener.onSubchannelState(
        ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    verify(listener).onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    verifyNoInteractions(healthConsumerListener);
  }
}
