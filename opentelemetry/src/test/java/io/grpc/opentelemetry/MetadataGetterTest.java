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

import static io.grpc.InternalMetadata.BASE64_ENCODING_OMIT_PADDING;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.grpc.Metadata;
import java.nio.charset.Charset;
import java.util.Iterator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetadataGetterTest {
  private final MetadataGetter metadataGetter = MetadataGetter.getInstance();

  @Test
  public void getBinaryGrpcTraceBin() {
    Metadata metadata = new Metadata();
    byte[] b = "generated".getBytes(Charset.defaultCharset());
    Metadata.Key<byte[]> grpc_trace_bin_key =
        Metadata.Key.of("grpc-trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadata.put(grpc_trace_bin_key, b);
    assertArrayEquals(b, metadataGetter.getBinary(metadata, "grpc-trace-bin"));
  }

  @Test
  public void getBinaryEmptyMetadata() {
    assertNull(metadataGetter.getBinary(new Metadata(), "grpc-trace-bin"));
  }

  @Test
  public void getBinaryNotGrpcTraceBin() {
    Metadata metadata = new Metadata();
    byte[] b = "generated".getBytes(Charset.defaultCharset());
    Metadata.Key<byte[]> grpc_trace_bin_key =
        Metadata.Key.of("another-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadata.put(grpc_trace_bin_key, b);
    assertNull(metadataGetter.getBinary(metadata, "another-bin"));
  }

  @Test
  public void getTextEmptyMetadata() {
    assertNull(metadataGetter.get(new Metadata(), "a-key"));
  }

  @Test
  public void getTextBinHeader() {
    assertNull(metadataGetter.get(new Metadata(), "a-key-bin"));
  }

  @Test
  public void getTestGrpcTraceBin() {
    Metadata metadata = new Metadata();
    byte[] b = "generated".getBytes(Charset.defaultCharset());
    Metadata.Key<byte[]> grpc_trace_bin_key =
        Metadata.Key.of("grpc-trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadata.put(grpc_trace_bin_key, b);
    assertEquals(BASE64_ENCODING_OMIT_PADDING.encode(b),
        metadataGetter.get(metadata, "grpc-trace-bin"));
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
