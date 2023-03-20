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
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Process;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.errorprone.annotations.CheckReturnValue;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/** Static factory methods for creating standard security policies. */
@CheckReturnValue
public final class SecurityPolicies {

  private static final int MY_UID = Process.myUid();
  private static final int SHA_256_BYTES_LENGTH = 32;

  private SecurityPolicies() {}

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public static ServerSecurityPolicy serverInternalOnly() {
    return new ServerSecurityPolicy();
  }

  /**
   * Creates a default {@link SecurityPolicy} that allows access only to callers with the same UID
   * as the current process.
   */
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

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
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
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public static SecurityPolicy hasSignature(
      PackageManager packageManager, String packageName, Signature requiredSignature) {
    return oneOfSignatures(
        packageManager, packageName, ImmutableList.of(requiredSignature));
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
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public static SecurityPolicy hasSignatureSha256Hash(
      PackageManager packageManager, String packageName, byte[] requiredSignatureSha256Hash) {
    return oneOfSignatureSha256Hash(
        packageManager, packageName, ImmutableList.of(requiredSignatureSha256Hash));
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
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
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
      List<byte[]> requiredSignatureSha256Hashes) {
    Preconditions.checkNotNull(packageManager);
    Preconditions.checkNotNull(packageName);
    Preconditions.checkNotNull(requiredSignatureSha256Hashes);
    Preconditions.checkArgument(!requiredSignatureSha256Hashes.isEmpty());

    ImmutableList.Builder<byte[]> immutableListBuilder = ImmutableList.builder();
    for (byte[] requiredSignatureSha256Hash : requiredSignatureSha256Hashes) {
      Preconditions.checkNotNull(requiredSignatureSha256Hash);
      Preconditions.checkArgument(requiredSignatureSha256Hash.length == SHA_256_BYTES_LENGTH);
      immutableListBuilder.add(
          Arrays.copyOf(requiredSignatureSha256Hash, requiredSignatureSha256Hash.length));
    }
    ImmutableList<byte[]> requiredSignaturesHashesImmutable = immutableListBuilder.build();

    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return checkUidSha256Signature(
            packageManager, uid, packageName, requiredSignaturesHashesImmutable);
      }
    };
  }

  /**
   * Creates {@link SecurityPolicy} which checks if the app is a device owner app. See
   * {@link DevicePolicyManager}.
   */
  public static SecurityPolicy isDeviceOwner(Context applicationContext) {
    DevicePolicyManager devicePolicyManager =
        (DevicePolicyManager) applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    return anyPackageWithUidSatisfies(
        applicationContext,
        pkg -> VERSION.SDK_INT >= 18 && devicePolicyManager.isDeviceOwnerApp(pkg),
        "Rejected by device owner policy. No packages found for UID.",
        "Rejected by device owner policy");
  }

  /**
   * Creates {@link SecurityPolicy} which checks if the app is a profile owner app. See
   * {@link DevicePolicyManager}.
   */
  public static SecurityPolicy isProfileOwner(Context applicationContext) {
    DevicePolicyManager devicePolicyManager =
        (DevicePolicyManager) applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    return anyPackageWithUidSatisfies(
        applicationContext,
        pkg -> VERSION.SDK_INT >= 21 && devicePolicyManager.isProfileOwnerApp(pkg),
        "Rejected by profile owner policy. No packages found for UID.",
        "Rejected by profile owner policy");
  }

  /**
   * Creates {@link SecurityPolicy} which checks if the app is a profile owner app on an
   * organization-owned device. See {@link DevicePolicyManager}.
   */
  public static SecurityPolicy isProfileOwnerOnOrganizationOwnedDevice(Context applicationContext) {
    DevicePolicyManager devicePolicyManager =
        (DevicePolicyManager) applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    return anyPackageWithUidSatisfies(
        applicationContext,
        pkg -> VERSION.SDK_INT >= 30
            && devicePolicyManager.isProfileOwnerApp(pkg)
            && devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile(),
        "Rejected by profile owner on organization-owned device policy. No packages found for UID.",
        "Rejected by profile owner on organization-owned device policy");
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
      if (checkPackageSignature(packageManager, pkg, requiredSignatures::contains)) {
        return Status.OK;
      }
    }
    return Status.PERMISSION_DENIED.withDescription(
        "Rejected by signature check security policy. Package name matched: "
            + packageNameMatched);
  }

  private static Status checkUidSha256Signature(
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
      if (checkPackageSignature(
          packageManager,
          pkg,
          (signature) ->
              checkSignatureSha256HashesMatch(signature, requiredSignatureSha256Hashes))) {
        return Status.OK;
      }
    }
    return Status.PERMISSION_DENIED.withDescription(
        "Rejected by (SHA-256 hash signature check) security policy. Package name matched: "
            + packageNameMatched);
  }

  /**
   * Checks if the signature of {@code packageName} matches one of the given signatures.
   *
   * @param packageName the package to be checked
   * @param signatureCheckFunction {@link Predicate} that takes a signature and verifies if it
   * satisfies any signature constraints
   * return {@code true} if {@code packageName} has a signature that satisfies {@code
   * signatureCheckFunction}.
   */
  @SuppressWarnings("deprecation") // For PackageInfo.signatures
  @SuppressLint("PackageManagerGetSignatures") // We only allow 1 signature.
  private static boolean checkPackageSignature(
      PackageManager packageManager,
      String packageName,
      Predicate<Signature> signatureCheckFunction) {
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
          if (signatureCheckFunction.apply(signature)) {
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

        if (signatureCheckFunction.apply(packageInfo.signatures[0])) {
          return true;
        }
      }
    } catch (NameNotFoundException nnfe) {
      return false;
    }
    return false;
  }

  /**
   * Creates a {@link SecurityPolicy} that allows access if and only if *all* of the specified
   * {@code securityPolicies} allow access.
   *
   * @param securityPolicies the security policies that all must allow access.
   * @throws NullPointerException if any of the inputs are {@code null}.
   * @throws IllegalArgumentException if {@code securityPolicies} is empty.
   */
  public static SecurityPolicy allOf(SecurityPolicy... securityPolicies) {
    Preconditions.checkNotNull(securityPolicies, "securityPolicies");
    Preconditions.checkArgument(securityPolicies.length > 0, "securityPolicies must not be empty");

    return allOfSecurityPolicy(securityPolicies);
  }

  private static SecurityPolicy allOfSecurityPolicy(SecurityPolicy... securityPolicies) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        for (SecurityPolicy policy : securityPolicies) {
          Status checkAuth = policy.checkAuthorization(uid);
          if (!checkAuth.isOk()) {
            return checkAuth;
          }
        }

        return Status.OK;
      }
    };
  }

  /**
   * Creates a {@link SecurityPolicy} that allows access if *any* of the specified {@code
   * securityPolicies} allow access.
   *
   * <p>Policies will be checked in the order that they are passed. If a policy allows access,
   * subsequent policies will not be checked.
   *
   * <p>If all policies deny access, the {@link io.grpc.Status} returned by {@code
   * checkAuthorization} will included the concatenated descriptions of the failed policies and
   * attach any additional causes as suppressed throwables. The status code will be that of the
   * first failed policy.
   *
   * @param securityPolicies the security policies that will be checked.
   * @throws NullPointerException if any of the inputs are {@code null}.
   * @throws IllegalArgumentException if {@code securityPolicies} is empty.
   */
  public static SecurityPolicy anyOf(SecurityPolicy... securityPolicies) {
    Preconditions.checkNotNull(securityPolicies, "securityPolicies");
    Preconditions.checkArgument(securityPolicies.length > 0, "securityPolicies must not be empty");

    return anyOfSecurityPolicy(securityPolicies);
  }

  private static SecurityPolicy anyOfSecurityPolicy(SecurityPolicy... securityPolicies) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        List<Status> failed = new ArrayList<>();
        for (SecurityPolicy policy : securityPolicies) {
          Status checkAuth = policy.checkAuthorization(uid);
          if (checkAuth.isOk()) {
            return checkAuth;
          }
          failed.add(checkAuth);
        }

        Iterator<Status> iter = failed.iterator();
        Status toReturn = iter.next();
        while (iter.hasNext()) {
          Status append = iter.next();
          toReturn = toReturn.augmentDescription(append.getDescription());
          if (append.getCause() != null) {
            if (toReturn.getCause() != null) {
              toReturn.getCause().addSuppressed(append.getCause());
            } else {
              toReturn = toReturn.withCause(append.getCause());
            }
          }
        }
        return toReturn;
      }
    };
  }

  /**
   * Creates a {@link SecurityPolicy} which checks if the caller has all of the given permissions
   * from {@code permissions}.
   *
   * @param permissions all permissions that the calling package needs to have
   * @throws NullPointerException if any of the inputs are {@code null}
   * @throws IllegalArgumentException if {@code permissions} is empty
   */
  public static SecurityPolicy hasPermissions(
      PackageManager packageManager, ImmutableSet<String> permissions) {
    Preconditions.checkNotNull(packageManager, "packageManager");
    Preconditions.checkNotNull(permissions, "permissions");
    Preconditions.checkArgument(!permissions.isEmpty(), "permissions");
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return checkPermissions(uid, packageManager, permissions);
      }
    };
  }

  private static Status checkPermissions(
      int uid, PackageManager packageManager, ImmutableSet<String> permissions) {
    String[] packages = packageManager.getPackagesForUid(uid);
    if (packages == null || packages.length == 0) {
      return Status.UNAUTHENTICATED.withDescription(
          "Rejected by permission check security policy. No packages found for uid");
    }
    for (String pkg : packages) {
      for (String permission : permissions) {
        if (packageManager.checkPermission(permission, pkg) != PackageManager.PERMISSION_GRANTED) {
          return Status.PERMISSION_DENIED.withDescription(
              "Rejected by permission check security policy. "
                  + pkg
                  + " does not have permission "
                  + permission);
        }
      }
    }

    return Status.OK;
  }

  private static SecurityPolicy anyPackageWithUidSatisfies(
      Context applicationContext,
      Predicate<String> condition,
      String errorMessageForNoPackages,
      String errorMessageForDenied) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        String[] packages = applicationContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
          return Status.UNAUTHENTICATED.withDescription(errorMessageForNoPackages);
        }

        for (String pkg : packages) {
          if (condition.apply(pkg)) {
            return Status.OK;
          }
        }
        return Status.PERMISSION_DENIED.withDescription(errorMessageForDenied);
      }
    };
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
