/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelStateListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link SubchannelPoolHelper}.
 */
@RunWith(JUnit4.class)
public class SubchannelPoolHelperTest {

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private Helper helper;

  @Mock
  private SubchannelPool subchannelPool;

  private final CreateSubchannelArgs args =
      CreateSubchannelArgs.newBuilder()
          .setAddresses(new EquivalentAddressGroup(new FakeSocketAddress("fake")))
          .setStateListener(mock(SubchannelStateListener.class))
          .build();

  @Test
  public void createSubchannel() {
    SubchannelPoolHelper subchannelPoolHelper = new SubchannelPoolHelper(helper, subchannelPool);

    subchannelPoolHelper.createSubchannel(args);

    verify(subchannelPool).takeOrCreateSubchannel(args);
  }

  @Test
  public void shutdown() {
    SubchannelPoolHelper subchannelPoolHelper = new SubchannelPoolHelper(helper, subchannelPool);

    subchannelPoolHelper.createSubchannel(args);
    subchannelPoolHelper.shutdown();

    verify(subchannelPool).clear();
  }

}
