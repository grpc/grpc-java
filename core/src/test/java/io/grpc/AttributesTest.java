/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.grpc.Attributes.Key;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

/** Unit tests for {@link Attributes}. */
@RunWith(JUnit4.class)
public class AttributesTest {
  private static Key<String> YOLO_KEY = Key.<String>of("yolo");

  @Test
  public void buildAttributes() {
    Attributes attrs = Attributes.newBuilder().set(YOLO_KEY, "To be, or not to be?").build();
    assertSame("To be, or not to be?", attrs.get(YOLO_KEY));
    assertEquals(1, attrs.keys().size());
  }

  @Test
  public void duplicates() {
    Attributes attrs = Attributes.newBuilder()
            .set(YOLO_KEY, "To be?")
            .set(YOLO_KEY, "Or not to be?")
            .set(Attributes.Key.of("yolo"), "I'm not a duplicate")
            .build();
    assertSame("Or not to be?", attrs.get(YOLO_KEY));
    assertEquals(2, attrs.keys().size());
  }

  @Test
  public void empty() {
    assertEquals(0, Attributes.EMPTY.keys().size());
  }

  @Test
  public void filterIn() {
    Key<String> key1 = Key.<String>of("key1");
    Key<String> key2 = Key.<String>of("key2");
    Key<Object> key3 = Key.<Object>of("key3");
    Key<Object> key4 = Key.<Object>of("key4");
    Attributes attrs = Attributes.newBuilder()
        .set(key1, "val1").set(key2, "val2").set(key3, "val3").set(key4, "val4")
        .build();

    @SuppressWarnings("unchecked") // type params are inhomogeneous but doesn't matter
    Attributes newAttrs = attrs.filterIn(Arrays.asList(key2, key3));

    assertNull(newAttrs.get(key1));
    assertEquals("val2", newAttrs.get(key2));
    assertEquals("val3", newAttrs.get(key3));
    assertNull(newAttrs.get(key4));
  }
}
