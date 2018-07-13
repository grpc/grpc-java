/*
 * Copyright 2018 The gRPC Authors
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

import static junit.framework.TestCase.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ProxySocketAddress}.
 */
@RunWith(JUnit4.class)
public class ProxySocketAddressTest {
  @Test
  public void getAddress() {
    SocketAddress addr = new SocketAddress() {};
    ProxySocketAddress psa = ProxySocketAddress.withoutProxy(addr);
    assertSame(addr, psa.getAddress());
  }

  @Test
  public void getProxyAddress() {
    InetSocketAddress addr = InetSocketAddress.createUnresolved("localhost", 456);
    InetSocketAddress proxyAddr = new InetSocketAddress("localhost", 123);
    ProxySocketAddress psa = ProxySocketAddress.withProxy(addr, proxyAddr, "user", "pass");
    assertSame(addr, psa.getAddress());
    assertSame(proxyAddr, psa.getProxyAddress());
    assertEquals("user", psa.getUsername());
    assertEquals("pass", psa.getPassword());
  }

  @Test
  public void getUsernameWithoutProxy() {
    ProxySocketAddress psa = ProxySocketAddress.withoutProxy(new SocketAddress() {});
    try {
      String ignored = psa.getUsername();
      fail("should not be reached");
    } catch (IllegalStateException expected) {

    }
  }

  @Test
  public void getPasswordWithoutProxy() {
    ProxySocketAddress psa = ProxySocketAddress.withoutProxy(new SocketAddress() {});
    try {
      String ignored = psa.getPassword();
      fail("should not be reached");
    } catch (IllegalStateException expected) {

    }
  }
}
