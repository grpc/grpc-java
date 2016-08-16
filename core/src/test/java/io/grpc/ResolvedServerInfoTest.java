package io.grpc;

import io.grpc.Attributes.Key;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class ResolvedServerInfoTest {
  private static final Key<String> FOO = Key.of("foo");
  private static final Attributes ATTRS = Attributes.newBuilder().set(FOO, "bar").build();

  @Test public void accessors() {
    InetSocketAddress addr = InetSocketAddress.createUnresolved("foo", 123);

    ResolvedServerInfo server = new ResolvedServerInfo(addr, ATTRS);
    assertEquals(addr, server.getAddress());
    assertEquals(ATTRS, server.getAttributes());

    // null attributes treated as empty
    server = new ResolvedServerInfo(addr, null);
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
    assertFalse(server1.getAddress() == server2.getAddress());
    assertFalse(server1.getAttributes() == server2.getAttributes());

    assertEquals(server1, server2);
    assertEquals(server1.hashCode(), server2.hashCode()); // hash code must be consistent

    // empty attributes
    server1 = new ResolvedServerInfo(InetSocketAddress.createUnresolved("foo", 123), null);
    server2 = new ResolvedServerInfo(InetSocketAddress.createUnresolved("foo", 123), null);
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
            .set(Key.of("fiz"), "buz")
            .build());

    assertNotEquals(server1, server2);
    assertNotEquals(server1.hashCode(), server2.hashCode());
  }

  @Test public void testHashCode() {
    checkHashCode(new ResolvedServerInfo(InetSocketAddress.createUnresolved("foo", 123), ATTRS));
    checkHashCode(new ResolvedServerInfo(InetSocketAddress.createUnresolved("foo", 123), null));
    checkHashCode(new ResolvedServerInfo(
        InetSocketAddress.createUnresolved("foo", 456),
        Attributes.newBuilder()
            .set(FOO, "bar")
            .set(Key.of("fiz"), "buz")
            .build()));
  }

  private void checkHashCode(ResolvedServerInfo server) {
    // spec says "address.hashCode() * 31 + hash(attributes)" as if attributes were a map
    SocketAddress address = server.getAddress();
    Map<Object, Object> attributes = new HashMap<Object, Object>();
    for (Key<?> k : server.getAttributes().keys()) {
      attributes.put(k, server.getAttributes().get(k));
    }

    int expectedHashCode = address.hashCode() * 31 + attributes.hashCode();
    assertEquals(expectedHashCode, server.hashCode());
  }
}
