/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link EquivalentAddressGroup}.
 */
@RunWith(JUnit4.class)
public class EquivalentAddressGroupTest {

  @Test
  public void toString_summarizesLargeAddressList() {
    int maxAddressesToString = maxAddressesToString();
    List<SocketAddress> addrs = new ArrayList<>();
    for (int i = 0; i <= maxAddressesToString; i++) {
      addrs.add(new FakeSocketAddress("addr" + i));
    }
    EquivalentAddressGroup eag = new EquivalentAddressGroup(addrs);

    StringBuilder expected = new StringBuilder();
    expected.append('[').append('[');
    for (int i = 0; i < maxAddressesToString; i++) {
      if (i > 0) {
        expected.append(", ");
      }
      expected.append(addrs.get(i));
    }
    expected.append(", ... 1 more]/{}]");
    assertThat(eag.toString()).isEqualTo(expected.toString());
  }

  @Test
  public void toString_doesNotSummarizeAtMaxAddressCount() {
    int maxAddressesToString = maxAddressesToString();
    List<SocketAddress> addrs = new ArrayList<>();
    for (int i = 0; i < maxAddressesToString; i++) {
      addrs.add(new FakeSocketAddress("addr" + i));
    }
    EquivalentAddressGroup eag = new EquivalentAddressGroup(addrs);

    String expected = "[" + addrs + "/{}]";
    assertThat(eag.toString()).isEqualTo(expected);
  }

  private static int maxAddressesToString() {
    try {
      Field field = EquivalentAddressGroup.class.getDeclaredField("MAX_ADDRESSES_TO_STRING");
      field.setAccessible(true);
      return (int) field.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new LinkageError("Unable to read MAX_ADDRESSES_TO_STRING", e);
    }
  }

  private static final class FakeSocketAddress extends SocketAddress {

    private final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
