/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ResolvedServerInfoTest {
  private static final Attributes.Key<String> FOO = Attributes.Key.of("foo");
  private static final Attributes ATTRS = Attributes.newBuilder().set(FOO, "bar").build();

  @Test
  public void accessors() {
    InetSocketAddress addr = InetSocketAddress.createUnresolved("foo", 123);

    ResolvedServerInfo server = new ResolvedServerInfo(addr, ATTRS);
    assertEquals(addr, server.getAddress());
    assertEquals(ATTRS, server.getAttributes());

    // unspecified attributes treated as empty
    server = new ResolvedServerInfo(addr);
    assertEquals(addr, server.getAddress());
    assertEquals(Attributes.EMPTY, server.getAttributes());
  }

  @Test public void cannotUseNullAddress() {
    try {
      new ResolvedServerInfo(null, ATTRS);
      fail("Should not have been allowd to create info with null address");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test public void equals_true() {
    ResolvedServerInfo server1 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 123),
        Attributes.newBuilder().set(FOO, "bar").build());
    ResolvedServerInfo server2 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 123),
        Attributes.newBuilder().set(FOO, "bar").build());

    // sanity checks that they're not same instances
    assertNotSame(server1.getAddress(), server2.getAddress());
    assertNotSame(server1.getAttributes(), server2.getAttributes());

    assertEquals(server1, server2);
    assertEquals(server1.hashCode(), server2.hashCode()); // hash code must be consistent

    // empty attributes
    server1 = new ResolvedServerInfo(InetSocketAddress.createUnresolved("foo", 123));
    server2 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 123), Attributes.EMPTY);
    assertEquals(server1, server2);
    assertEquals(server1.hashCode(), server2.hashCode());
  }

  @Test public void equals_falseDifferentAddresses() {
    ResolvedServerInfo server1 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 123),
        Attributes.newBuilder().set(FOO, "bar").build());
    ResolvedServerInfo server2 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 456),
        Attributes.newBuilder().set(FOO, "bar").build());

    assertNotEquals(server1, server2);
    // hash code could collide, but this assertion is safe because, in this example, they do not
    assertNotEquals(server1.hashCode(), server2.hashCode());
  }

  @Test public void equals_falseDifferentAttributes() {
    ResolvedServerInfo server1 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 123),
        Attributes.newBuilder().set(FOO, "bar").build());
    ResolvedServerInfo server2 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 123),
        Attributes.newBuilder().set(FOO, "baz").build());

    assertNotEquals(server1, server2);
    // hash code could collide, but these assertions are safe because, in these examples, they don't
    assertNotEquals(server1.hashCode(), server2.hashCode());

    // same values but extra key? still not equal
    server2 = new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 456),
        Attributes.newBuilder()
            .set(FOO, "bar")
            .set(Attributes.Key.of("fiz"), "buz")
            .build());

    assertNotEquals(server1, server2);
    assertNotEquals(server1.hashCode(), server2.hashCode());
  }
}
