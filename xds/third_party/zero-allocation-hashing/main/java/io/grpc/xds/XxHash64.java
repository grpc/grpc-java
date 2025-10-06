/*
 * Copyright 2021 Higher Frequency Trading http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Forked from zero-allocation-hashing-0.14 (https://github.com/OpenHFT/Zero-Allocation-Hashing).
 * Modified by the gRPC Authors
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.nio.ByteOrder;

/**
 * The XxHash is a fast, non-cryptographic, 64-bit hash function that has excellent avalanche
 * and 2-way bit independence properties.
 *
 * <p>This implementation is a simplified version adapted from
 * <a href="https://github.com/OpenHFT/Zero-Allocation-Hashing/blob/master/src/main/java/net/openhft/hashing/XxHash.java">
 * OpenHFT/Zero-Allocation-Hashing</a>.
 */
final class XxHash64 {
  static final XxHash64 INSTANCE = new XxHash64(0);

  // Primes if treated as unsigned
  private static final long P1 = -7046029288634856825L;
  private static final long P2 = -4417276706812531889L;
  private static final long P3 = 1609587929392839161L;
  private static final long P4 = -8796714831421723037L;
  private static final long P5 = 2870177450012600261L;

  private static final ByteOrder byteOrder = ByteOrder.nativeOrder();
  private final long seed;
  private final long voidHash;

  XxHash64(long seed) {
    this.seed = seed;
    this.voidHash = finalize(seed + P5);
  }

  long hashLong(long input) {
    input = byteOrder == ByteOrder.LITTLE_ENDIAN ? input : Long.reverseBytes(input);
    long hash = seed + P5 + 8;
    input *= P2;
    input = Long.rotateLeft(input, 31);
    input *= P1;
    hash ^= input;
    hash = Long.rotateLeft(hash, 27) * P1 + P4;
    return finalize(hash);
  }

  long hashInt(int input) {
    input = byteOrder == ByteOrder.LITTLE_ENDIAN ? input : Integer.reverseBytes(input);
    long hash = seed + P5 + 4;
    hash ^= (input & 0xFFFFFFFFL) * P1;
    hash = Long.rotateLeft(hash, 23) * P2 + P3;
    return finalize(hash);
  }

  long hashShort(short input) {
    input = byteOrder == ByteOrder.LITTLE_ENDIAN ? input : Short.reverseBytes(input);
    long hash = seed + P5 + 2;
    hash ^= (input & 0xFFL) * P5;
    hash = Long.rotateLeft(hash, 11) * P1;
    hash ^= ((input >> 8) & 0xFFL) * P5;
    hash = Long.rotateLeft(hash, 11) * P1;
    return finalize(hash);
  }

  long hashChar(char input) {
    return hashShort((short) input);
  }

  long hashByte(byte input) {
    long hash = seed + P5 + 1;
    hash ^= (input & 0xFF) * P5;
    hash = Long.rotateLeft(hash, 11) * P1;
    return finalize(hash);
  }

  long hashVoid() {
    return voidHash;
  }

  long hashAsciiString(String input) {
    ByteSupplier supplier = new AsciiStringByteSupplier(input);
    return hashBytes(supplier);
  }

  long hashBytes(byte[] bytes) {
    ByteSupplier supplier = new PlainByteSupplier(bytes);
    return hashBytes(supplier);
  }

  long hashBytes(byte[] bytes, int offset, int len) {
    ByteSupplier supplier = new PlainByteSupplier(bytes, offset, len);
    return hashBytes(supplier);
  }

  private long hashBytes(ByteSupplier supplier) {
    long hash;
    if (supplier.remaining() >= 32) {
      long v1 = seed + P1 + P2;
      long v2 = seed + P2;
      long v3 = seed;
      long v4 = seed - P1;

      do {
        v1 += supplier.next64() * P2;
        v1 = Long.rotateLeft(v1, 31);
        v1 *= P1;

        v2 += supplier.next64() * P2;
        v2 = Long.rotateLeft(v2, 31);
        v2 *= P1;

        v3 += supplier.next64() * P2;
        v3 = Long.rotateLeft(v3, 31);
        v3 *= P1;

        v4 += supplier.next64() * P2;
        v4 = Long.rotateLeft(v4, 31);
        v4 *= P1;
      } while (supplier.remaining() >= 32);

      hash = Long.rotateLeft(v1, 1)
          + Long.rotateLeft(v2, 7)
          + Long.rotateLeft(v3, 12)
          + Long.rotateLeft(v4, 18);

      v1 *= P2;
      v1 = Long.rotateLeft(v1, 31);
      v1 *= P1;
      hash ^= v1;
      hash = (hash * P1) + P4;

      v2 *= P2;
      v2 = Long.rotateLeft(v2, 31);
      v2 *= P1;
      hash ^= v2;
      hash = (hash * P1) + P4;

      v3 *= P2;
      v3 = Long.rotateLeft(v3, 31);
      v3 *= P1;
      hash ^= v3;
      hash = (hash * P1) + P4;

      v4 *= P2;
      v4 = Long.rotateLeft(v4, 31);
      v4 *= P1;
      hash ^= v4;
      hash = (hash * P1) + P4;
    } else {
      hash = seed + P5;
    }

    hash += supplier.bytes();

    while (supplier.remaining() >= 8) {
      long k1 = supplier.next64();
      k1 *= P2;
      k1 = Long.rotateLeft(k1, 31);
      k1 *= P1;
      hash ^= k1;
      hash = (Long.rotateLeft(hash, 27) * P1) + P4;
    }

    if (supplier.remaining() >= 4) { //treat as unsigned ints
      hash ^= (supplier.next32() & 0xFFFFFFFFL) * P1;
      hash = (Long.rotateLeft(hash, 23) * P2) + P3;
    }

    while (supplier.remaining() != 0) { //treat as unsigned bytes
      hash ^= (supplier.next8() & 0xFF) * P5;
      hash = Long.rotateLeft(hash, 11) * P1;
    }

    return finalize(hash);
  }

  private static long finalize(long hash) {
    hash ^= hash >>> 33;
    hash *= P2;
    hash ^= hash >>> 29;
    hash *= P3;
    hash ^= hash >>> 32;
    return hash;
  }

  private static class PlainByteSupplier extends ByteSupplier {
    private final byte[] src;
    private final int len;
    private int pos;
    private int remain;

    private PlainByteSupplier(byte[] src) {
      this(src, 0, src.length);
    }

    private PlainByteSupplier(byte[] src, int offset, int len) {
      this.src = checkNotNull(src, "src");
      checkArgument(offset <= src.length, "offset > src length");
      checkArgument(offset + len <= src.length, "offset + len > src length");
      this.len = len;
      pos = offset;
      remain = len;
    }

    @Override
    public long bytes() {
      return len;
    }

    @Override
    public long remaining() {
      return remain;
    }

    @Override
    public byte next8() {
      remain--;
      return src[pos++];
    }
  }

  private static class AsciiStringByteSupplier extends ByteSupplier {
    private final String str;
    private final int bytes;
    private int pos;

    private AsciiStringByteSupplier(String str) {
      this.str = checkNotNull(str, "str");
      this.bytes = str.length();
    }

    @Override
    public long bytes() {
      return bytes;
    }

    @Override
    public long remaining() {
      return (long) bytes - pos;
    }

    @Override
    public byte next8() {
      return (byte) str.charAt(pos++);
    }
  }

  private abstract static class ByteSupplier {

    public long next64() {
      return ((next32() & 0xFFFFFFFFL) | ((next32() & 0xFFFFFFFFL) << 32));
    }

    public int next32() {
      return ((next16() & 0xFFFF) | ((next16() & 0xFFFF) << 16));
    }

    char next16() {
      return (char) ((next8() & 0xFF) | ((next8() & 0xFF) << 8));
    }

    abstract byte next8();

    abstract long bytes();

    abstract long remaining();
  }
}
