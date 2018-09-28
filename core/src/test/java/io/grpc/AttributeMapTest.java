/*
 * Copyright 2016 The gRPC Authors
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AttributeMap}. */
@RunWith(JUnit4.class)
public class AttributeMapTest {
  private static interface TransAttr {}
  private static interface TlsTransAttr extends TransAttr {}

  private static final AttributeMap.Key<TransAttr, String> ADDR_KEY =
      AttributeMap.Key.define("addr");

  private static final AttributeMap.Key<TransAttr, String> HOST_KEY =
      AttributeMap.Key.define("host");

  private static final AttributeMap.Key<TlsTransAttr, String> TLS_KEY =
      AttributeMap.Key.define("tls");

  @Test
  public void buildAttributes() {
    AttributeMap<TransAttr> attrs =
        AttributeMap.<TransAttr>newBuilder().set(ADDR_KEY, "8.8.8.8").build();
    assertSame("8.8.8.8", attrs.get(ADDR_KEY));
    assertThat(attrs.keysForTest()).hasSize(1);
  }

  @Test
  public void duplicates() {
    AttributeMap<TransAttr> attrs = AttributeMap.<TransAttr>newBuilder()
        .set(ADDR_KEY, "8.8.8.8")
        .set(ADDR_KEY, "8.8.8.7")
        .set(HOST_KEY, "google.com")
        .build();
    assertThat(attrs.get(ADDR_KEY)).isEqualTo("8.8.8.7");
    assertThat(attrs.keysForTest()).hasSize(2);
  }

  @Test
  public void keyHirarchy() {
    // Because TlsTransAttr extends TransAttr, an AttributeMap<TransAttr> can contain
    // both Key<TransAttr, ?> and Key<TlsTransAttr, ?>
    AttributeMap<TransAttr> attrs = AttributeMap.<TransAttr>newBuilder()
        .set(ADDR_KEY, "8.8.8.8")
        .set(ADDR_KEY, "8.8.8.7")
        .set(TLS_KEY, "gibberish")
        .build();
    assertThat(attrs.get(ADDR_KEY)).isEqualTo("8.8.8.7");
    assertThat(attrs.get(TLS_KEY)).isEqualTo("gibberish");
    assertThat(attrs.keysForTest()).hasSize(2);

    AttributeMap<TlsTransAttr> tlsAttrs = AttributeMap.<TlsTransAttr>newBuilder()
        .set(TLS_KEY, "gibberish again")
        // .set(ADDR_KEY, "8.8.8.8")  // won't compile
        .build();

    attrs = attrs.toBuilder().setAll(tlsAttrs).build();
    assertThat(attrs.get(TLS_KEY)).isEqualTo("gibberish again");

    // tlsAttrs = tlsAttrs.toBuilder().setAll(attrs).build();  // won't compile
  }

  @Test
  public void toBuilder() {
    AttributeMap<TransAttr> attrs = AttributeMap.<TransAttr>newBuilder()
        .set(ADDR_KEY, "8.8.8.8")
        .build()
        .toBuilder()
        .set(ADDR_KEY, "8.8.8.7")
        .set(HOST_KEY, "I'm not a duplicate")
        .build();
    assertThat(attrs.get(ADDR_KEY)).isEqualTo("8.8.8.7");
    assertThat(attrs.keysForTest()).hasSize(2);
  }

  @Test
  public void empty() {
    assertThat(AttributeMap.getEmptyInstance().keysForTest()).isEmpty();
  }

  @Test
  public void valueEquality() {
    class EqualObject {
      @Override public boolean equals(Object o) {
        return o instanceof EqualObject;
      }

      @Override public int hashCode() {
        return 42;
      }
    }

    AttributeMap.Key<TransAttr, EqualObject> key = AttributeMap.Key.define("ints");
    EqualObject v1 = new EqualObject();
    EqualObject v2 = new EqualObject();

    assertNotSame(v1, v2);
    assertEquals(v1, v2);

    AttributeMap<TransAttr> attr1 = AttributeMap.<TransAttr>newBuilder().set(key, v1).build();
    AttributeMap<TransAttr> attr2 = AttributeMap.<TransAttr>newBuilder().set(key, v2).build();

    assertEquals(attr1, attr2);
    assertEquals(attr1.hashCode(), attr2.hashCode());
  }
}
