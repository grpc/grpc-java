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

package io.grpc.rls;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.ResolvedAddresses;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ChildLbResolvedAddressFactoryTest {

  @Test
  public void create() {
    List<EquivalentAddressGroup> addrs = new ArrayList<>();
    addrs.add(new EquivalentAddressGroup(mock(SocketAddress.class)));
    Attributes attr = Attributes.newBuilder().build();
    ChildLbResolvedAddressFactory factory = new ChildLbResolvedAddressFactory(addrs, attr);
    Object config1 = new Object();
    
    ResolvedAddresses resolvedAddress = factory.create(config1);
    
    assertThat(resolvedAddress.getAddresses()).isEqualTo(addrs);
    assertThat(resolvedAddress.getAttributes()).isEqualTo(attr);
    assertThat(resolvedAddress.getLoadBalancingPolicyConfig()).isEqualTo(config1);

    Object config2 = "different object";
    
    resolvedAddress = factory.create(config2);

    assertThat(resolvedAddress.getAddresses()).isEqualTo(addrs);
    assertThat(resolvedAddress.getAttributes()).isEqualTo(attr);
    assertThat(resolvedAddress.getLoadBalancingPolicyConfig()).isEqualTo(config2);
  }
}
