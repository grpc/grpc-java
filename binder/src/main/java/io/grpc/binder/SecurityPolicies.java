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
   * Creates a {@link SecurityPolicy} which checks if the package signature
   * matches {@code requiredSignature}.
   *
   * @param packageName the package name of the allowed package.
   * @param requiredSignature the allowed signature of the allowed package.
   * @throws NullPointerException if any of the inputs are {@code null}.
   */
  public static SecurityPolicy hasSignature(
      PackageManager packageManager, String packageName, Signature requiredSignature) {
    return oneOfSignatures(
        packageManager, packageName, ImmutableList.of(requiredSignature));
  }

  /**
   * Creates a {@link SecurityPolicy} which checks if the package signature
   * matches any of {@code requiredSignatures}.
   *
   * @param packageName the package name of the allowed package.
   * @param requiredSignatures the allowed signatures of the allowed package.
   * @throws NullPointerException if any of the inputs are {@code null}.
   * @throws IllegalArgumentException if {@code requiredSignatures} is empty.
   */
  public static SecurityPolicy oneOfSignatures(
      PackageManager packageManager,
      String packageName,
      Collection<Signature> requiredSignatures) {
    Preconditions.checkNotNull(packageManager, "packageManager");
    Preconditions.checkNotNull(packageName, "packageName");
    Preconditions.checkNotNull(requiredSignatures, "requiredSignatures");
    Preconditions.checkArgument(!requiredSignatures.isEmpty(),
        "requiredSignatures");
    ImmutableList<Signature> requiredSignaturesImmutable = ImmutableList.copyOf(requiredSignatures);

    for (Signature requiredSignature : requiredSignaturesImmutable) {
      Preconditions.checkNotNull(requiredSignature);
    }

    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return checkUidSignature(
            packageManager, uid, packageName, requiredSignaturesImmutable);
      }
    };
  }

  private static Status checkUidSignature(
      PackageManager packageManager,
      int uid,
      String packageName,
      ImmutableList<Signature> requiredSignatures) {
    String[] packages = packageManager.getPackagesForUid(uid);
    if (packages == null) {
      return Status.UNAUTHENTICATED.withDescription(
          "Rejected by signature check security policy");
    }
    boolean packageNameMatched = false;
    for (String pkg : packages) {
      if (!packageName.equals(pkg)) {
        continue;
      }
      packageNameMatched = true;
      if (checkPackageSignature(packageManager, pkg, requiredSignatures)) {
        return Status.OK;
      }
    }
    return Status.PERMISSION_DENIED.withDescription(
        "Rejected by signature check security policy. Package name matched: "
            + packageNameMatched);
  }

  /**
   * Checks if the signature of {@code packageName} matches one of the given signatures.
   *
   * @param packageName the package to be checked
   * @param requiredSignatures list of signatures.
   * @return {@code true} if {@code packageName} has a matching signature.
   */
  @SuppressWarnings("deprecation") // For PackageInfo.signatures
  @SuppressLint("PackageManagerGetSignatures") // We only allow 1 signature.
  private static boolean checkPackageSignature(
      PackageManager packageManager,
      String packageName,
      ImmutableList<Signature> requiredSignatures) {
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
          if (requiredSignatures.contains(signature)) {
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

        if (requiredSignatures.contains(packageInfo.signatures[0])) {
          return true;
        }
      }
    } catch (NameNotFoundException nnfe) {
      return false;
    }
    return false;
  }
}
