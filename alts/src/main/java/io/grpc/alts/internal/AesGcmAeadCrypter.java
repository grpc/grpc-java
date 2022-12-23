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
import io.grpc.internal.ConscryptLoader;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES128-GCM implementation of {@link AeadCrypter} that uses default JCE provider. */
final class AesGcmAeadCrypter implements AeadCrypter {
  private static final Logger logger = Logger.getLogger(AesGcmAeadCrypter.class.getName());
  private static final int KEY_LENGTH = 16;
  private static final int TAG_LENGTH = 16;
  static final int NONCE_LENGTH = 12;

  private static final String AES = "AES";
  private static final String AES_GCM = AES + "/GCM/NoPadding";
  // Conscrypt if available, otherwise null. Conscrypt is much faster than Java 8's JSSE
  private static final Provider CONSCRYPT = getConscrypt();

  private final byte[] key;
  private final Cipher cipher;

  AesGcmAeadCrypter(byte[] key) throws GeneralSecurityException {
    checkArgument(key.length == KEY_LENGTH);
    this.key = key;
    if (CONSCRYPT != null) {
      cipher = Cipher.getInstance(AES_GCM, CONSCRYPT);
    } else {
      cipher = Cipher.getInstance(AES_GCM);
    }
  }

  private int encryptAad(
      ByteBuffer ciphertext, ByteBuffer plaintext, @Nullable ByteBuffer aad, byte[] nonce)
      throws GeneralSecurityException {
    checkArgument(nonce.length == NONCE_LENGTH);
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(this.key, AES),
        new GCMParameterSpec(TAG_LENGTH * 8, nonce));
    if (aad != null) {
      cipher.updateAAD(aad);
    }
    return cipher.doFinal(plaintext, ciphertext);
  }

  private void decryptAad(
      ByteBuffer plaintext, ByteBuffer ciphertext, @Nullable ByteBuffer aad, byte[] nonce)
      throws GeneralSecurityException {
    checkArgument(nonce.length == NONCE_LENGTH);
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(this.key, AES),
        new GCMParameterSpec(TAG_LENGTH * 8, nonce));
    if (aad != null) {
      cipher.updateAAD(aad);
    }
    cipher.doFinal(ciphertext, plaintext);
  }

  @Override
  public void encrypt(ByteBuffer ciphertext, ByteBuffer plaintext, byte[] nonce)
      throws GeneralSecurityException {
    encryptAad(ciphertext, plaintext, null, nonce);
  }

  @Override
  public void encrypt(ByteBuffer ciphertext, ByteBuffer plaintext, ByteBuffer aad, byte[] nonce)
      throws GeneralSecurityException {
    encryptAad(ciphertext, plaintext, aad, nonce);
  }

  @Override
  public void decrypt(ByteBuffer plaintext, ByteBuffer ciphertext, byte[] nonce)
      throws GeneralSecurityException {
    decryptAad(plaintext, ciphertext, null, nonce);
  }

  @Override
  public void decrypt(ByteBuffer plaintext, ByteBuffer ciphertext, ByteBuffer aad, byte[] nonce)
      throws GeneralSecurityException {
    decryptAad(plaintext, ciphertext, aad, nonce);
  }

  static int getKeyLength() {
    return KEY_LENGTH;
  }

  @VisibleForTesting
  static Provider getConscrypt() {
    if (!ConscryptLoader.isPresent()) {
      return null;
    }
    // Conscrypt 2.1.0 or later is required. If an older version is used, it will fail with these
    // sorts of errors:
    //     "The underlying Cipher implementation does not support this method"
    //     "error:1e000067:Cipher functions:OPENSSL_internal:BUFFER_TOO_SMALL"
    //
    // While we could use Conscrypt.version() to check compatibility, that is _very_ verbose via
    // reflection. In practice, old conscrypts are probably not much of a problem.
    Provider provider;
    try {
      provider = ConscryptLoader.newProvider();
    } catch (Throwable t) {
      logger.log(Level.INFO, "Could not load Conscrypt. Will use slower JDK implementation", t);
      return null;
    }
    try {
      Cipher.getInstance(AES_GCM, provider);
    } catch (SecurityException t) {
      // Pre-Java 7u121/Java 8u111 fails with SecurityException:
      //   JCE cannot authenticate the provider Conscrypt
      //
      // This is because Conscrypt uses a newer (more secure) signing CA than the earlier Java
      // supported. https://www.oracle.com/technetwork/java/javase/8u111-relnotes-3124969.html
      // https://www.oracle.com/technetwork/java/javase/documentation/javase7supportreleasenotes-1601161.html#R170_121
      //
      // Use WARNING instead of INFO in this case because it is unlikely to be a supported
      // environment. In the other cases we might be on Java 9+; it seems unlikely in this case.
      // Note that on Java 7, we're likely to crash later because GCM is unsupported.
      logger.log(
          Level.WARNING,
          "Could not load Conscrypt. Will try slower JDK implementation. This may be because the "
          + "JDK is older than Java 7 update 121 or Java 8 update 111. If so, please update",
          t);
      return null;
    } catch (Throwable t) {
      logger.log(Level.INFO, "Could not load Conscrypt. Will use slower JDK implementation", t);
      return null;
    }
    return provider;
  }
}
