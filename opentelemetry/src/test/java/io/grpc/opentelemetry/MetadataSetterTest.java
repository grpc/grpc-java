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
import static org.junit.Assert.assertTrue;

import io.grpc.Metadata;
import java.nio.charset.Charset;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetadataSetterTest {
  private final MetadataSetter metadataSetter = MetadataSetter.getInstance();

  @Test
  public void setGrpcTraceBin() {
    Metadata metadata = new Metadata();
    byte[] b = "generated".getBytes(Charset.defaultCharset());
    Metadata.Key<byte[]> grpc_trace_bin_key =
        Metadata.Key.of("grpc-trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadataSetter.set(metadata, "grpc-trace-bin", b);
    assertTrue(Arrays.equals(b, metadata.get(grpc_trace_bin_key)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void setOtherBinaryKey() {
    Metadata metadata = new Metadata();
    byte[] b = "generated".getBytes(Charset.defaultCharset());
    Metadata.Key<byte[]> other_key =
        Metadata.Key.of("for-test", Metadata.BINARY_BYTE_MARSHALLER);
    metadataSetter.set(metadata, other_key.name(), b);
  }

  @Test
  public void setText() {
    Metadata metadata = new Metadata();
    String v = "generated";
    Metadata.Key<String> textKey =
        Metadata.Key.of("text-key", Metadata.ASCII_STRING_MARSHALLER);
    metadataSetter.set(metadata, textKey.name(), v);
    assertEquals(metadata.get(textKey), v);
  }
}
