/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder;

import android.annotation.SuppressLint;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.annotation.CheckReturnValue;

/** Static factory methods for creating standard security policies. */
@CheckReturnValue
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class SecurityPolicies {

  private static final int MY_UID = Process.myUid();
  private static final int SHA_256_BYTES_LENGTH = 32;

  private SecurityPolicies() {}

  public static ServerSecurityPolicy serverInternalOnly() {
    return new ServerSecurityPolicy();
  }

  public static SecurityPolicy internalOnly() {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return uid == MY_UID
            ? Status.OK
            : Status.PERMISSION_DENIED.withDescription(
                "Rejected by (internal-only) security policy");
      }
    };
  }

  public static SecurityPolicy permissionDenied(String description) {
    Status denied = Status.PERMISSION_DENIED.withDescription(description);
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return denied;
      }
    };
  }

  /**
   * Creates {@link SecurityPolicy} which checks if the SHA-256 hash of the package signature
   * matches {@code requiredSignatureSha256Hash}.
   *
   * @param packageName the package name of the allowed package.
   * @param requiredSignatureSha256Hash the SHA-256 digest of the signature of the allowed package.
   * @throws NullPointerException if any of the inputs are {@code null}.
   * @throws IllegalArgumentException if {@code requiredSignatureSha256Hash} is not of length 32.
   */
  public static SecurityPolicy hasSignatureSha256Hash(
      PackageManager packageManager, String packageName, byte[] requiredSignatureSha256Hash) {
    return oneOfSignatureSha256Hash(
        packageManager, packageName, ImmutableList.of(requiredSignatureSha256Hash));
  }

  /**
   * Creates {@link SecurityPolicy} which checks if the SHA-256 hash of the package signature
   * matches any of {@code requiredSignatureSha256Hashes}.
   *
   * @param packageName the package name of the allowed package.
   * @param requiredSignatureSha256Hashes the SHA-256 digests of the signatures of the allowed
   *     package.
   * @throws NullPointerException if any of the inputs are {@code null}.
   * @throws IllegalArgumentException if {@code requiredSignatureSha256Hashes} is empty, or if any
   *     of the {@code requiredSignatureSha256Hashes} are not of length 32.
   */
  public static SecurityPolicy oneOfSignatureSha256Hash(
      PackageManager packageManager,
      String packageName,
      Collection<byte[]> requiredSignatureSha256Hashes) {
    Preconditions.checkNotNull(packageManager, "packageManager");
    Preconditions.checkNotNull(packageName, "packageName");
    Preconditions.checkNotNull(requiredSignatureSha256Hashes, "requiredSignatureSha256Hashes");
    Preconditions.checkArgument(!requiredSignatureSha256Hashes.isEmpty(),
        "requiredSignatureSha256Hashes");
    ImmutableList<byte[]> requiredSignatureSha256HashesImmutable =
        ImmutableList.copyOf(requiredSignatureSha256Hashes);

    for (byte[] requiredSignatureSha256Hash : requiredSignatureSha256HashesImmutable) {
      Preconditions.checkNotNull(requiredSignatureSha256Hash);
      Preconditions.checkArgument(requiredSignatureSha256Hash.length == SHA_256_BYTES_LENGTH);
    }

    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return checkUidSignatureSha256(
            packageManager, uid, packageName, requiredSignatureSha256HashesImmutable);
      }
    };
  }

  private static Status checkUidSignatureSha256(
      PackageManager packageManager,
      int uid,
      String packageName,
      ImmutableList<byte[]> requiredSignatureSha256Hashes) {
    String[] packages = packageManager.getPackagesForUid(uid);
    if (packages == null) {
      return Status.UNAUTHENTICATED.withDescription(
          "Rejected by (SHA-256 hash signature check) security policy");
    }
    boolean packageNameMatched = false;
    for (String pkg : packages) {
      if (!packageName.equals(pkg)) {
        continue;
      }
      packageNameMatched = true;
      if (checkPackageSignatureSha256(
          packageManager,
          pkg,
          requiredSignatureSha256Hashes)) {
        return Status.OK;
      }
    }
    return Status.PERMISSION_DENIED.withDescription(
        "Rejected by (SHA-256 hash signature check) security policy. Package name matched: "
            + packageNameMatched);
  }

  /**
   * Checks if the signature of {@code packageName} matches on of the given sha256 hashes.
   *
   * @param packageName the package to be checked
   * @param requiredSignatureSha256Hashes a list of hashes.
   * @return {@code true} if {@code packageName} has a signature matches one of the hashes.
   */
  @SuppressWarnings("deprecation") // For PackageInfo.signatures
  @SuppressLint("PackageManagerGetSignatures") // We only allow 1 signature.
  private static boolean checkPackageSignatureSha256(
      PackageManager packageManager,
      String packageName,
      ImmutableList<byte[]> requiredSignatureSha256Hashes) {
    PackageInfo packageInfo;
    try {
      if (Build.VERSION.SDK_INT >= 28) {
        packageInfo =
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (packageInfo.signingInfo == null) {
          return false;
        }
        Signature[] signatures =
            packageInfo.signingInfo.hasMultipleSigners()
                ? packageInfo.signingInfo.getApkContentsSigners()
                : packageInfo.signingInfo.getSigningCertificateHistory();

        for (Signature signature : signatures) {
          if (checkSignatureSha256HashesMatch(signature, requiredSignatureSha256Hashes)) {
            return true;
          }
        }
      } else {
        packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        if (packageInfo.signatures == null || packageInfo.signatures.length != 1) {
          // Reject multiply-signed apks because of b/13678484
          // (See PackageManagerGetSignatures supression above).
          return false;
        }

        if (checkSignatureSha256HashesMatch(
              packageInfo.signatures[0],
              requiredSignatureSha256Hashes)) {
          return true;
        }
      }
    } catch (NameNotFoundException nnfe) {
      return false;
    }
    return false;
  }

  /**
   * Checks if the SHA-256 hash of the {@code signature} matches one of the {@code
   * expectedSignatureSha256Hashes}.
   */
  private static boolean checkSignatureSha256HashesMatch(
      Signature signature, List<byte[]> expectedSignatureSha256Hashes) {
    byte[] signatureHash = getSha256Hash(signature);
    for (byte[] hash : expectedSignatureSha256Hashes) {
      if (Arrays.equals(hash, signatureHash)) {
        return true;
      }
    }
    return false;
  }

  /** Returns SHA-256 hash of the provided signature. */
  private static byte[] getSha256Hash(Signature signature) {
    return Hashing.sha256().hashBytes(signature.toByteArray()).asBytes();
  }
}
