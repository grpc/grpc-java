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

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import io.grpc.Status;
import io.grpc.binder.SecurityPolicy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class SecurityPoliciesTest {

  private static final int MY_UID = Process.myUid();
  private static final int OTHER_UID = MY_UID + 1;
  private static final int OTHER_UID_SAME_SIGNATURE = MY_UID + 2;
  private static final int OTHER_UID_NO_SIGNATURE = MY_UID + 3;
  private static final int OTHER_UID_UNKNOWN = MY_UID + 4;

  private static final String PERMISSION_DENIED_REASONS = "some reasons";

  private static final Signature SIG1 = new Signature("1234");
  private static final Signature SIG2 = new Signature("4321");

  private static final String OTHER_UID_PACKAGE_NAME = "other.package";
  private static final String OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME = "other.package.samesignature";
  private static final String OTHER_UID_NO_SIGNATURE_PACKAGE_NAME = "other.package.nosignature";

  private Context appContext;
  private PackageManager packageManager;

  private SecurityPolicy policy;

  @Before
  public void setUp() {
    appContext = ApplicationProvider.getApplicationContext();
    packageManager = appContext.getPackageManager();
    installPackage(MY_UID, appContext.getPackageName(), SIG1);
    installPackage(OTHER_UID, OTHER_UID_PACKAGE_NAME, SIG2);
    installPackage(OTHER_UID_SAME_SIGNATURE, OTHER_UID_SAME_SIGNATURE_PACKAGE_NAME, SIG1);
    installPackage(OTHER_UID_NO_SIGNATURE, OTHER_UID_NO_SIGNATURE_PACKAGE_NAME);
  }

  @SuppressWarnings("deprecation")
  private void installPackage(int uid, String packageName, Signature... signatures) {
    PackageInfo info = new PackageInfo();
    info.packageName = packageName;
    info.signatures = signatures;
    shadowOf(packageManager).installPackage(info);
    shadowOf(packageManager).setPackagesForUid(uid, packageName);
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
    policy = SecurityPolicies.hasSignature(packageManager, OTHER_UID_PACKAGE_NAME, SIG2);

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testHasSignature_failsIfPackageNameDoesNotMatch() throws Exception {
    policy = SecurityPolicies.hasSignature(packageManager, appContext.getPackageName(), SIG1);

    // THEN UID for package that has SIG1 but different package name will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID_SAME_SIGNATURE).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testHasSignature_failsIfSignatureDoesNotMatch() throws Exception {
    policy = SecurityPolicies.hasSignature(packageManager, OTHER_UID_PACKAGE_NAME, SIG1);

    // THEN UID for package that doesn't have SIG1 will not be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testOneOfSignatures_succeedsIfPackageNameAndSignaturesMatch()
      throws Exception {
    policy =
        SecurityPolicies.oneOfSignatures(
            packageManager, OTHER_UID_PACKAGE_NAME, ImmutableList.of(SIG2));

    // THEN UID for package that has SIG2 will be authorized
    assertThat(policy.checkAuthorization(OTHER_UID).getCode()).isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testOneOfSignature_failsIfAllSignaturesDoNotMatch() throws Exception {
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
    policy =
        SecurityPolicies.oneOfSignatures(
            packageManager,
            OTHER_UID_PACKAGE_NAME,
            ImmutableList.of(SIG1, SIG2));

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
}
