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

import com.google.common.io.BaseEncoding;
import io.grpc.Metadata.AsciiMarshaller;
import io.grpc.Metadata.BinaryStreamMarshaller;
import io.grpc.Metadata.Key;
import java.nio.charset.Charset;

/**
 * Internal {@link Metadata} accessor. This is intended for use by io.grpc.internal, and the
 * specifically supported transport packages. If you *really* think you need to use this, contact
 * the gRPC team first.
 */
@Internal
public final class InternalMetadata {

  /**
   * A specialized plain ASCII marshaller. Both input and output are assumed to be valid header
   * ASCII.
   *
   * <p>Extended here to break the dependency.
   */
  @Internal
  public interface TrustedAsciiMarshaller<T> extends Metadata.TrustedAsciiMarshaller<T> {}

  /**
   * Copy of StandardCharsets, which is only available on Java 1.7 and above.
   */
  @Internal
  public static final Charset US_ASCII = Charset.forName("US-ASCII");

  /**
   * An instance of base64 encoder that omits padding.
   */
  @Internal
  public static final BaseEncoding BASE64_ENCODING_OMIT_PADDING
      = Metadata.BASE64_ENCODING_OMIT_PADDING;

  @Internal
  public static <T> Key<T> keyOf(String name, TrustedAsciiMarshaller<T> marshaller) {
    boolean isPseudo = name != null && !name.isEmpty() && name.charAt(0) == ':';
    return Metadata.Key.of(name, isPseudo, marshaller);
  }

  @Internal
  public static <T> Key<T> keyOf(String name, AsciiMarshaller<T> marshaller) {
    boolean isPseudo = name != null && !name.isEmpty() && name.charAt(0) == ':';
    return Metadata.Key.of(name, isPseudo, marshaller);
  }

  @Internal
  public static Metadata newMetadata(byte[]... binaryValues) {
    return new Metadata(binaryValues);
  }

  @Internal
  public static Metadata newMetadata(int usedNames, byte[]... binaryValues) {
    return new Metadata(usedNames, binaryValues);
  }

  @Internal
  public static byte[][] serialize(Metadata md) {
    return md.serialize();
  }

  @Internal
  public static int headerCount(Metadata md) {
    return md.headerCount();
  }

  /**
   * Serializes all metadata entries, leaving some values as {@link InputStream}s.
   *
   * <p>Produces serialized names and values interleaved. result[i*2] are names, while
   * result[i*2+1] are values.
   *
   * <p>Names are byte arrays as described according to the {@link Metadata#serialize}
   * method. Values are either byte arrays or {@link InputStream}s.
   */
  @Internal
  public static Object[] serializePartial(Metadata md) {
    return md.serializePartial();
  }

  /**
   * Creates a holder for a pre-parsed value read by the transport.
   *
   * @param marshaller The {@link Metadata#BinaryStreamMarshaller} associated with this value.
   * @param value The value to store.
   * @return an object holding the pre-parsed value for this key.
   */
  @Internal
  public static <T> Object parsedValue(BinaryStreamMarshaller<T> marshaller, T value) {
    return new Metadata.LazyValue<>(marshaller, value);
  }

  /**
   * Creates a new {@link Metadata} instance from serialized data,
   * with some values pre-parsed. Metadata will mutate the passed in array.
   *
   * @param usedNames The number of names used.
   * @param namesAndValues An array of interleaved names and values,
   *     with each name (at even indices) represented as a byte array,
   *     and each value (at odd indices) represented as either a byte
   *     array or an object returned by the {@link #parsedValue}
   *     method.
   */
  @Internal
  public static Metadata newMetadataWithParsedValues(int usedNames, Object[] namesAndValues) {
    return new Metadata(usedNames, namesAndValues);
  }
}
