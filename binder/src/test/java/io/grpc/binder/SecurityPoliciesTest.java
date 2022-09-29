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

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Process;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import io.grpc.Status;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public final class SecurityPoliciesTest {

  private static final int MY_UID = Process.myUid();
  private static final int OTHER_UID = MY_UID + 1;
  private static final int OTHER_UID_SAME_SIGNATURE = MY_UID + 2;
  private static final int OTHER_UID_UNKNOWN = MY_UID + 4;

  private static final String PERMISSION_DENIED_REASONS = "some reasons";

  private static final Signature SIG1 = new Signature("1234");
  private static final Signature SIG2 = new Signature("4321");

  private static final String OTHER_UID_PACKAGE_NAME = "other.package";
  private static final String OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME = "other.package.samesignature";

  private Context appContext;
  private PackageManager packageManager;
  private DevicePolicyManager devicePolicyManager;

  private SecurityPolicy policy;

  @Before
  public void setUp() {
    appContext = ApplicationProvider.getApplicationContext();
    packageManager = appContext.getPackageManager();
    devicePolicyManager =
        (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
  }

  @SuppressWarnings("deprecation")
  private void installPackages(int uid, PackageInfo... packageInfo) {
    String[] packageNames = new String[packageInfo.length];
    for (int i = 0; i < packageInfo.length; i++) {
      shadowOf(packageManager).installPackage(packageInfo[i]);
      packageNames[i] = packageInfo[i].packageName;
    }
    shadowOf(packageManager).setPackagesForUid(uid, packageNames);
  }

  @Test
  public void testInternalOnly() throws Exception {
    policy = SecurityPolicies.internalOnly();

    assertThat(policy.checkAuthorization(MY_UID).getCode()).isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testPermissionDenied() throws Exception {
    policy = SecurityPolicies.permissionDenied(PERMISSION_DENIED_REASONS);
    assertThat(policy.checkAuthorization(MY_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(MY_UID).getDescription())
        .isEqualTo(PERMISSION_DENIED_REASONS);
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .isEqualTo(PERMISSION_DENIED_REASONS);
  }

  @Test
  public void testHasSignature_succeedsIfPackageNameAndSignaturesMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.hasSignature(packageManager, OTHER_UID_PACKAGE_NAME, SIG2);

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testHasSignature_failsIfPackageNameDoesNotMatch() throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME)
            .setSignatures(SIG1)
            .build();

    installPackages(OTHER_UID_SAME_SIGNATURE, info);

    policy = SecurityPolicies.hasSignature(packageManager, appContext.getPackageName(), SIG1);

    // THEN UID for package that has SIG1 but different package name will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID_SAME_SIGNATURE).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testHasSignature_failsIfSignatureDoesNotMatch() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.hasSignature(packageManager, OTHER_UID_PACKAGE_NAME, SIG1);

    // THEN UID for package that doesn't have SIG1 will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testOneOfSignatures_succeedsIfPackageNameAndSignaturesMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.oneOfSignatures(
            packageManager, OTHER_UID_PACKAGE_NAME, ImmutableList.of(SIG2));

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testOneOfSignature_failsIfAllSignaturesDoNotMatch() throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME)
            .setSignatures(SIG1)
            .build();

    installPackages(OTHER_UID_SAME_SIGNATURE, info);

    policy =
        SecurityPolicies.oneOfSignatures(
            packageManager,
            appContext.getPackageName(),
            ImmutableList.of(SIG1, new Signature("1314")));

    // THEN UID for package that has SIG1 but different package name will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID_SAME_SIGNATURE).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testOneOfSignature_succeedsIfPackageNameAndOneOfSignaturesMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.oneOfSignatures(
            packageManager, OTHER_UID_PACKAGE_NAME, ImmutableList.of(SIG1, SIG2));

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testHasSignature_failsIfUidUnknown() throws Exception {
    policy =
        SecurityPolicies.hasSignature(
            packageManager,
            appContext.getPackageName(),
            SIG1);

    assertThat(policy.checkAuthorization(OTHER_UID_UNKNOWN).getCode())
        .isEqualTo(Status.UNAUTHENTICATED.getCode());
  }

  @Test
  public void testHasPermissions_sharedUserId_succeedsIfAllPackageHavePermissions()
      throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(ACCESS_COARSE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .build();

    PackageInfo infoSamePerms =
        newBuilder()
            .setPackageName(OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(ACCESS_COARSE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .build();

    installPackages(OTHER_UID, info, infoSamePerms);

    policy =
        SecurityPolicies.hasPermissions(
            packageManager, ImmutableSet.of(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION));
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testHasPermissions_sharedUserId_failsIfOnePackageHasNoPermissions() throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .build();

    PackageInfo infoNoPerms =
        newBuilder()
            .setPackageName(OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, 0)
            .build();

    installPackages(OTHER_UID, info, infoNoPerms);

    policy = SecurityPolicies.hasPermissions(packageManager, ImmutableSet.of(ACCESS_FINE_LOCATION));
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .contains(ACCESS_FINE_LOCATION);
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .contains(OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME);
  }

  @Test
  public void testHasPermissions_succeedsIfPackageHasPermissions() throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(ACCESS_COARSE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(WRITE_EXTERNAL_STORAGE, 0)
            .build();

    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.hasPermissions(
            packageManager, ImmutableSet.of(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION));
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testHasPermissions_failsIfPackageDoesNotHaveOnePermission() throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(ACCESS_COARSE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(WRITE_EXTERNAL_STORAGE, 0)
            .build();

    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.hasPermissions(
            packageManager, ImmutableSet.of(ACCESS_FINE_LOCATION, WRITE_EXTERNAL_STORAGE));
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .contains(WRITE_EXTERNAL_STORAGE);
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .contains(OTHER_UID_PACKAGE_NAME);
  }

  @Test
  public void testHasPermissions_failsIfPackageDoesNotHavePermissions() throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_PACKAGE_NAME)
            .setPermission(ACCESS_FINE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(ACCESS_COARSE_LOCATION, REQUESTED_PERMISSION_GRANTED)
            .setPermission(WRITE_EXTERNAL_STORAGE, 0)
            .build();

    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.hasPermissions(packageManager, ImmutableSet.of(WRITE_EXTERNAL_STORAGE));
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .contains(WRITE_EXTERNAL_STORAGE);
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .contains(OTHER_UID_PACKAGE_NAME);
  }

  @Test
  @Config(sdk = 18)
  public void testIsDeviceOwner_succeedsForDeviceOwner() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);
    shadowOf(devicePolicyManager)
        .setDeviceOwner(new ComponentName(OTHER_UID_PACKAGE_NAME, "foo"));

    policy = SecurityPolicies.isDeviceOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  @Config(sdk = 18)
  public void testIsDeviceOwner_failsForNotDeviceOwner() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.isDeviceOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  @Config(sdk = 18)
  public void testIsDeviceOwner_failsWhenNoPackagesForUid() throws Exception {
    policy = SecurityPolicies.isDeviceOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
  }

  @Test
  @Config(sdk = 17)
  public void testIsDeviceOwner_failsForSdkLevelTooLow() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.isDeviceOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  @Config(sdk = 21)
  public void testIsProfileOwner_succeedsForProfileOwner() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);
    shadowOf(devicePolicyManager)
        .setProfileOwner(new ComponentName(OTHER_UID_PACKAGE_NAME, "foo"));

    policy = SecurityPolicies.isProfileOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  @Config(sdk = 21)
  public void testIsProfileOwner_failsForNotProfileOwner() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.isProfileOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  @Config(sdk = 21)
  public void testIsProfileOwner_failsWhenNoPackagesForUid() throws Exception {
    policy = SecurityPolicies.isProfileOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
  }

  @Test
  @Config(sdk = 19)
  public void testIsProfileOwner_failsForSdkLevelTooLow() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.isProfileOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  @Config(sdk = 30)
  public void testIsProfileOwnerOnOrgOwned_succeedsForProfileOwnerOnOrgOwned() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);
    shadowOf(devicePolicyManager)
        .setProfileOwner(new ComponentName(OTHER_UID_PACKAGE_NAME, "foo"));
    shadowOf(devicePolicyManager).setOrganizationOwnedDeviceWithManagedProfile(true);

    policy = SecurityPolicies.isProfileOwnerOnOrganizationOwnedDevice(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());

  }

  @Test
  @Config(sdk = 30)
  public void testIsProfileOwnerOnOrgOwned_failsForProfileOwnerOnNonOrgOwned() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);
    shadowOf(devicePolicyManager)
        .setProfileOwner(new ComponentName(OTHER_UID_PACKAGE_NAME, "foo"));
    shadowOf(devicePolicyManager).setOrganizationOwnedDeviceWithManagedProfile(false);

    policy = SecurityPolicies.isProfileOwnerOnOrganizationOwnedDevice(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  @Config(sdk = 21)
  public void testIsProfileOwnerOnOrgOwned_failsForNotProfileOwner() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.isProfileOwnerOnOrganizationOwnedDevice(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  @Config(sdk = 21)
  public void testIsProfileOwnerOnOrgOwned_failsWhenNoPackagesForUid() throws Exception {
    policy = SecurityPolicies.isProfileOwnerOnOrganizationOwnedDevice(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
  }

  @Test
  @Config(sdk = 29)
  public void testIsProfileOwnerOnOrgOwned_failsForSdkLevelTooLow() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();

    installPackages(OTHER_UID, info);

    policy = SecurityPolicies.isProfileOwner(appContext);

    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  private static PackageInfoBuilder newBuilder() {
    return new PackageInfoBuilder();
  }

  private static class PackageInfoBuilder {
    private String packageName;
    private Signature[] signatures;
    private final HashMap<String, Integer> permissions = new HashMap<>();

    public PackageInfoBuilder setPackageName(String packageName) {
      this.packageName = packageName;
      return this;
    }

    public PackageInfoBuilder setPermission(String permissionName, int permissionFlag) {
      this.permissions.put(permissionName, permissionFlag);
      return this;
    }

    public PackageInfoBuilder setSignatures(Signature... signatures) {
      this.signatures = signatures;
      return this;
    }

    public PackageInfo build() {
      checkState(this.packageName != null, "packageName is a mandatory field");

      PackageInfo packageInfo = new PackageInfo();

      packageInfo.packageName = this.packageName;

      if (this.signatures != null) {
        packageInfo.signatures = this.signatures;
      }

      if (!this.permissions.isEmpty()) {
        String[] requestedPermissions =
            this.permissions.keySet().toArray(new String[this.permissions.size()]);
        int[] requestedPermissionsFlags = new int[requestedPermissions.length];

        for (int i = 0; i < requestedPermissions.length; i++) {
          requestedPermissionsFlags[i] = this.permissions.get(requestedPermissions[i]);
        }

        packageInfo.requestedPermissions = requestedPermissions;
        packageInfo.requestedPermissionsFlags = requestedPermissionsFlags;
      }

      return packageInfo;
    }
  }

  @Test
  public void testAllOf_succeedsIfAllSecurityPoliciesAllowed() throws Exception {
    policy = SecurityPolicies.allOf(SecurityPolicies.internalOnly());

    assertThat(policy.checkAuthorization(MY_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testAllOf_failsIfOneSecurityPoliciesNotAllowed() throws Exception {
    policy =
        SecurityPolicies.allOf(
            SecurityPolicies.internalOnly(),
            SecurityPolicies.permissionDenied("Not allowed SecurityPolicy"));

    assertThat(policy.checkAuthorization(MY_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(MY_UID).getDescription())
        .contains("Not allowed SecurityPolicy");
  }

  @Test
  public void testAnyOf_succeedsIfAnySecurityPoliciesAllowed() throws Exception {
    RecordingPolicy recordingPolicy = new RecordingPolicy();
    policy = SecurityPolicies.anyOf(SecurityPolicies.internalOnly(), recordingPolicy);

    assertThat(policy.checkAuthorization(MY_UID).getCode()).isEqualTo(Status.OK.getCode());
    assertThat(recordingPolicy.numCalls.get()).isEqualTo(0);
  }

  @Test
  public void testAnyOf_failsIfNoSecurityPolicyIsAllowed() throws Exception {
    policy =
        SecurityPolicies.anyOf(
            new SecurityPolicy() {
              @Override
              public Status checkAuthorization(int uid) {
                return Status.PERMISSION_DENIED.withDescription("Not allowed: first");
              }
            },
            new SecurityPolicy() {
              @Override
              public Status checkAuthorization(int uid) {
                return Status.UNAUTHENTICATED.withDescription("Not allowed: second");
              }
            });

    assertThat(policy.checkAuthorization(MY_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(MY_UID).getDescription()).contains("Not allowed: first");
    assertThat(policy.checkAuthorization(MY_UID).getDescription()).contains("Not allowed: second");
  }

  private static final class RecordingPolicy extends SecurityPolicy {
    private final AtomicInteger numCalls = new AtomicInteger(0);

    @Override
    public Status checkAuthorization(int uid) {
      numCalls.incrementAndGet();
      return Status.OK;
    }
  }

  @Test
  public void testHasSignatureSha256Hash_succeedsIfPackageNameAndSignatureHashMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();
    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.hasSignatureSha256Hash(
            packageManager, OTHER_UID_PACKAGE_NAME, getSha256Hash(SIG2));

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testHasSignatureSha256Hash_failsIfPackageNameDoesNotMatch() throws Exception {
    PackageInfo info1 =
        newBuilder().setPackageName(appContext.getPackageName()).setSignatures(SIG1).build();
    installPackages(MY_UID, info1);

    PackageInfo info2 =
        newBuilder()
            .setPackageName(OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME)
            .setSignatures(SIG1)
            .build();
    installPackages(OTHER_UID_SAME_SIGNATURE, info2);

    policy =
        SecurityPolicies.hasSignatureSha256Hash(
            packageManager, appContext.getPackageName(), getSha256Hash(SIG1));

    // THEN UID for package that has SIG1 but different package name will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID_SAME_SIGNATURE).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testHasSignatureSha256Hash_failsIfSignatureHashDoesNotMatch() throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();
    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.hasSignatureSha256Hash(
            packageManager, OTHER_UID_PACKAGE_NAME, getSha256Hash(SIG1));

    // THEN UID for package that doesn't have SIG1 will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testOneOfSignatureSha256Hash_succeedsIfPackageNameAndSignatureHashMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();
    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.oneOfSignatureSha256Hash(
            packageManager, OTHER_UID_PACKAGE_NAME, ImmutableList.of(getSha256Hash(SIG2)));

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testOneOfSignatureSha256Hash_succeedsIfPackageNameAndOneOfSignatureHashesMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();
    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.oneOfSignatureSha256Hash(
            packageManager,
            OTHER_UID_PACKAGE_NAME,
            ImmutableList.of(getSha256Hash(SIG1), getSha256Hash(SIG2)));

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void
  testOneOfSignatureSha256Hash_failsIfPackageNameDoNotMatchAndOneOfSignatureHashesMatch()
      throws Exception {
    PackageInfo info =
        newBuilder().setPackageName(OTHER_UID_PACKAGE_NAME).setSignatures(SIG2).build();
    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.oneOfSignatureSha256Hash(
            packageManager,
            appContext.getPackageName(),
            ImmutableList.of(getSha256Hash(SIG1), getSha256Hash(SIG2)));

    // THEN UID for package that has SIG2 but different package name will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testOneOfSignatureSha256Hash_failsIfPackageNameMatchAndOneOfSignatureHashesNotMatch()
      throws Exception {
    PackageInfo info =
        newBuilder()
            .setPackageName(OTHER_UID_PACKAGE_NAME)
            .setSignatures(new Signature("1234"))
            .build();
    installPackages(OTHER_UID, info);

    policy =
        SecurityPolicies.oneOfSignatureSha256Hash(
            packageManager,
            appContext.getPackageName(),
            ImmutableList.of(getSha256Hash(SIG1), getSha256Hash(SIG2)));

    // THEN UID for package that doesn't have SIG1 or SIG2 will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  private static byte[] getSha256Hash(Signature signature) {
    return Hashing.sha256().hashBytes(signature.toByteArray()).asBytes();
  }
}
