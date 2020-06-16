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

import static com.google.common.truth.Truth.assertThat;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link AddressFilter}. */
@RunWith(JUnit4.class)
public class AddressFilterTest {
  @Test
  public void filterAddresses() {
    Attributes.Key<String> key1 = Attributes.Key.create("key1");
    Attributes attributes1 = Attributes.newBuilder().set(key1, "value1").build();
    EquivalentAddressGroup eag0 = new EquivalentAddressGroup(new InetSocketAddress(8000));
    EquivalentAddressGroup eag1 =
        new EquivalentAddressGroup(new InetSocketAddress(8001), attributes1);
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(new InetSocketAddress(8002));
    EquivalentAddressGroup eag3 =
        new EquivalentAddressGroup(
            Arrays.<SocketAddress>asList(new InetSocketAddress(8003), new InetSocketAddress(8083)));
    eag0 = AddressFilter.setPathFilter(eag0, Arrays.asList("A", "C"));
    eag1 = AddressFilter.setPathFilter(eag1, Arrays.asList("A", "B"));
    eag2 = AddressFilter.setPathFilter(eag2, Arrays.asList("D", "C"));
    eag3 = AddressFilter.setPathFilter(eag3, Arrays.asList("A", "B"));

    List<EquivalentAddressGroup> addresses =
        AddressFilter.filter(Arrays.asList(eag0, eag1, eag2, eag3), "A");
    assertThat(addresses).hasSize(3);
    addresses = AddressFilter.filter(addresses, "B");
    assertThat(addresses).hasSize(2);
    EquivalentAddressGroup filteredAddress0 = addresses.get(0);
    EquivalentAddressGroup filteredAddress1 = addresses.get(1);
    assertThat(filteredAddress0.getAddresses()).containsExactlyElementsIn(eag1.getAddresses());
    assertThat(filteredAddress0.getAttributes().get(key1)).isEqualTo("value1");
    assertThat(filteredAddress1.getAddresses()).containsExactlyElementsIn(eag3.getAddresses());
  }
}
