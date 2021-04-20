/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds;

import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.LoadBalancerProvider;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PriorityLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class PriorityLoadBalancerProviderTest {
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule public final ExpectedException thrown = ExpectedException.none();

  @SuppressWarnings("ExpectedExceptionChecker")
  @Test
  public void priorityLbConfig_emptyPriorities() {
    Map<String, PriorityChildConfig> childConfigs =
        ImmutableMap.of(
            "p0",
            new PriorityChildConfig(
                new PolicySelection(mock(LoadBalancerProvider.class), null), true));
    List<String> priorities = ImmutableList.of();

    thrown.expect(IllegalArgumentException.class);
    new PriorityLbConfig(childConfigs, priorities);
  }

  @SuppressWarnings("ExpectedExceptionChecker")
  @Test
  public void priorityLbConfig_missingChildConfig() {
    Map<String, PriorityChildConfig> childConfigs =
        ImmutableMap.of(
            "p1",
            new PriorityChildConfig(
                new PolicySelection(mock(LoadBalancerProvider.class), null), true));
    List<String> priorities = ImmutableList.of("p0", "p1");

    thrown.expect(IllegalArgumentException.class);
    new PriorityLbConfig(childConfigs, priorities);
  }
}
