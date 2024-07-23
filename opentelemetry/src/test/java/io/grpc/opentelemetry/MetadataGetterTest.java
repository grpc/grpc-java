/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.grpc.Metadata;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Iterator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetadataGetterTest {
  private final MetadataGetter metadataGetter = MetadataGetter.getInstance();

  @Test
  public void getBinary() {
    Metadata metadata = new Metadata();
    byte[] b = "generated".getBytes(Charset.defaultCharset());
    Metadata.Key<byte[]> grpc_trace_bin_key =
        Metadata.Key.of("grpc-trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadata.put(grpc_trace_bin_key, b);
    assertTrue(Arrays.equals(b, metadataGetter.getBinary(metadata, "grpc-trace-bin")));
  }

  @Test
  public void getNullBinary() {
    assertNull(metadataGetter.getBinary(new Metadata(), "grpc-trace-bin"));
  }

  @Test
  public void getNullText() {
    assertNull(metadataGetter.get(new Metadata(), "a-key"));
  }

  @Test
  public void getText() {
    Metadata metadata = new Metadata();
    Metadata.Key<String> other_key =
        Metadata.Key.of("other", Metadata.ASCII_STRING_MARSHALLER);
    metadata.put(other_key, "header-value");
    assertEquals("header-value", metadataGetter.get(metadata, "other"));

    Iterator<String> iterator = metadataGetter.keys(metadata).iterator();
    assertTrue(iterator.hasNext());
    assertEquals("other", iterator.next());
    assertFalse(iterator.hasNext());
  }
}
