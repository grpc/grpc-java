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

package io.grpc.alts.internal;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.List;

/** Performs encryption and decryption with AES-GCM using JCE. All methods are thread-compatible. */
final class AltsChannelCrypter implements ChannelCrypterNetty {
  private static final int KEY_LENGTH = AesGcmHkdfAeadCrypter.getKeyLength();
  private static final int COUNTER_LENGTH = 12;
  // The counter will overflow after 2^64 operations and encryption/decryption will stop working.
  private static final int COUNTER_OVERFLOW_LENGTH = 8;
  private static final int TAG_LENGTH = 16;

  private final AeadCrypter aeadCrypter;

  private final byte[] outCounter = new byte[COUNTER_LENGTH];
  private final byte[] inCounter = new byte[COUNTER_LENGTH];
  private final byte[] oldCounter = new byte[COUNTER_LENGTH];

  AltsChannelCrypter(byte[] key, boolean isClient) {
    checkArgument(key.length == KEY_LENGTH);
    byte[] counter = isClient ? inCounter : outCounter;
    counter[counter.length - 1] = (byte) 0x80;
    this.aeadCrypter = new AesGcmHkdfAeadCrypter(key);
  }

  static int getKeyLength() {
    return KEY_LENGTH;
  }

  static int getCounterLength() {
    return COUNTER_LENGTH;
  }

  @Override
  public void encrypt(ByteBuf outBuf, List<ByteBuf> plainBufs) throws GeneralSecurityException {
    byte[] tempArr = new byte[outBuf.writableBytes()];

    // Copy plaintext into tempArr.
    {
      ByteBuf tempBuf = Unpooled.wrappedBuffer(tempArr, 0, tempArr.length - TAG_LENGTH);
      tempBuf.resetWriterIndex();
      for (ByteBuf plainBuf : plainBufs) {
        tempBuf.writeBytes(plainBuf);
      }
    }

    // Encrypt into tempArr.
    {
      ByteBuffer out = ByteBuffer.wrap(tempArr);
      ByteBuffer plain = ByteBuffer.wrap(tempArr, 0, tempArr.length - TAG_LENGTH);

      byte[] counter = incrementOutCounter();
      aeadCrypter.encrypt(out, plain, counter);
    }
    outBuf.writeBytes(tempArr);
  }

  @Override
  public void decrypt(ByteBuf outBuf, ByteBuf tagBuf, List<ByteBuf> ciphertextBufs)
      throws GeneralSecurityException {
    // There is enough space for the ciphertext including the tag in outBuf.
    byte[] tempArr = new byte[outBuf.writableBytes()];

    // Copy ciphertext and tag into tempArr.
    {
      ByteBuf tempBuf = Unpooled.wrappedBuffer(tempArr);
      tempBuf.resetWriterIndex();
      for (ByteBuf ciphertextBuf : ciphertextBufs) {
        tempBuf.writeBytes(ciphertextBuf);
      }
      tempBuf.writeBytes(tagBuf);
    }

    decryptInternal(outBuf, tempArr);
  }

  @Override
  public void decrypt(
      ByteBuf outBuf, ByteBuf ciphertextAndTagDirect) throws GeneralSecurityException {
    byte[] tempArr = new byte[ciphertextAndTagDirect.readableBytes()];

    // Copy ciphertext and tag into tempArr.
    {
      ByteBuf tempBuf = Unpooled.wrappedBuffer(tempArr);
      tempBuf.resetWriterIndex();
      tempBuf.writeBytes(ciphertextAndTagDirect);
    }

    decryptInternal(outBuf, tempArr);
  }

  private void decryptInternal(ByteBuf outBuf, byte[] tempArr) throws GeneralSecurityException {
    // Perform in-place decryption on tempArr.
    {
      ByteBuffer ciphertextAndTag = ByteBuffer.wrap(tempArr);
      ByteBuffer out = ByteBuffer.wrap(tempArr);
      byte[] counter = incrementInCounter();
      aeadCrypter.decrypt(out, ciphertextAndTag, counter);
    }

    outBuf.writeBytes(tempArr, 0, tempArr.length - TAG_LENGTH);
  }

  @Override
  public int getSuffixLength() {
    return TAG_LENGTH;
  }

  @Override
  public void destroy() {
    // no destroy required
  }

  /** Increments {@code counter}, store the unincremented value in {@code oldCounter}. */
  static void incrementCounter(byte[] counter, byte[] oldCounter) throws GeneralSecurityException {
    System.arraycopy(counter, 0, oldCounter, 0, counter.length);
    int i = 0;
    for (; i < COUNTER_OVERFLOW_LENGTH; i++) {
      counter[i]++;
      if (counter[i] != (byte) 0x00) {
        break;
      }
    }

    if (i == COUNTER_OVERFLOW_LENGTH) {
      // Restore old counter value to ensure that encrypt and decrypt keep failing.
      System.arraycopy(oldCounter, 0, counter, 0, counter.length);
      throw new GeneralSecurityException("Counter has overflowed.");
    }
  }

  /** Increments the input counter, returning the previous (unincremented) value. */
  private byte[] incrementInCounter() throws GeneralSecurityException {
    incrementCounter(inCounter, oldCounter);
    return oldCounter;
  }

  /** Increments the output counter, returning the previous (unincremented) value. */
  private byte[] incrementOutCounter() throws GeneralSecurityException {
    incrementCounter(outCounter, oldCounter);
    return oldCounter;
  }

  @VisibleForTesting
  void incrementInCounterForTesting(int n) throws GeneralSecurityException {
    for (int i = 0; i < n; i++) {
      incrementInCounter();
    }
  }

  @VisibleForTesting
  void incrementOutCounterForTesting(int n) throws GeneralSecurityException {
    for (int i = 0; i < n; i++) {
      incrementOutCounter();
    }
  }
}
