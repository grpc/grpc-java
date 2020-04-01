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

package io.grpc.rls.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableMap;
import io.grpc.LoadBalancerProvider;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LbPolicyConfigurationTest {

  @Test
  public void childPolicyWrapper_refCounted() {
    String target = "target";
    ChildPolicyWrapper childPolicy = ChildPolicyWrapper.createOrGet(target);
    assertThat(ChildPolicyWrapper.childPolicyMap.keySet()).containsExactly(target);

    ChildPolicyWrapper childPolicy2 = ChildPolicyWrapper.createOrGet(target);
    assertThat(ChildPolicyWrapper.childPolicyMap.keySet()).containsExactly(target);
    assertThat(childPolicy2).isEqualTo(childPolicy);

    childPolicy2.release();
    assertThat(ChildPolicyWrapper.childPolicyMap.keySet()).containsExactly(target);

    childPolicy.release();
    assertThat(ChildPolicyWrapper.childPolicyMap).isEmpty();

    try {
      childPolicy.release();
      fail("should not be able to access already released policy");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("already released");
    }
  }

  @Test
  public void childLoadBalancingPolicy_effectiveChildPolicy() {
    LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
    ChildLoadBalancingPolicy childLbPolicy =
        new ChildLoadBalancingPolicy(
            "targetFieldName",
            ImmutableMap.<String, Object>of("foo", "bar"),
            mockProvider);

    assertThat(childLbPolicy.getEffectiveChildPolicy("target"))
        .containsExactly("foo", "bar", "targetFieldName", "target");
    assertThat(childLbPolicy.getEffectiveLbProvider()).isEqualTo(mockProvider);
  }
}
