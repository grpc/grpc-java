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

import java.lang.reflect.Method;
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

  private static Provider getConscrypt() {
    // This is equivalent to if (Conscrypt.isAvailable()) return Conscrypt.newProvider();

    // Conscrypt 2.1.0 or later is required. If an older version is used, it will fail with these
    // sorts of errors:
    //     "The underlying Cipher implementation does not support this method"
    //     "error:1e000067:Cipher functions:OPENSSL_internal:BUFFER_TOO_SMALL"
    //
    // While we could use Conscrypt.version() to check compatibility, that is _very_ verbose via
    // reflection. In practice, old conscrypts are probably not much of a problem.
    Class<?> conscryptClass;
    try {
      conscryptClass = Class.forName("org.conscrypt.Conscrypt");
    } catch (ClassNotFoundException ex) {
      logger.log(Level.FINE, "Could not find Conscrypt", ex);
      return null;
    }
    Method method;
    try {
      method = conscryptClass.getMethod("newProvider");
    } catch (SecurityException ex) {
      logger.log(Level.FINE, "Could not find Conscrypt factory method", ex);
      return null;
    } catch (NoSuchMethodException ex) {
      logger.log(Level.WARNING, "Could not find Conscrypt factory method", ex);
      return null;
    }
    Object provider;
    try {
      provider = method.invoke(null);
    } catch (IllegalAccessException ex) {
      logger.log(Level.WARNING, "Could not call Conscrypt factory method", ex);
      return null;
    } catch (Throwable ex) {
      // This is probably an InvocationTargetException, which means something's wrong with the JNI
      // loading. Maybe the platform is not supported. We could have used Conscrypt.isAvailable(),
      // but it just catches Throwable as well
      logger.log(Level.WARNING, "Failed calling Conscrypt factory method", ex);
      return null;
    }
    if (!(provider instanceof Provider)) {
      logger.log(
          Level.WARNING, "Could not load Conscrypt. Returned provider was not a Provider: {0}",
          provider.getClass().getName());
      return null;
    }
    return (Provider) provider;
  }
}
