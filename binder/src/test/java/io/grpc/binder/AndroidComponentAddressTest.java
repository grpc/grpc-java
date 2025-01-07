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

import static android.content.Intent.URI_ANDROID_APP_SCHEME;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.UserHandle;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.testing.EqualsTester;
import java.net.URISyntaxException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public final class AndroidComponentAddressTest {

  private final Context appContext = ApplicationProvider.getApplicationContext();
  private final ComponentName hostComponent = new ComponentName(appContext, appContext.getClass());

  @Test
  public void testAuthority() {
    AndroidComponentAddress addr = AndroidComponentAddress.forContext(appContext);
    assertThat(addr.getAuthority()).isEqualTo(appContext.getPackageName());
  }

  @Test
  public void testComponent() {
    AndroidComponentAddress addr = AndroidComponentAddress.forComponent(hostComponent);
    assertThat(addr.getComponent()).isSameInstanceAs(hostComponent);
  }

  @Test
  public void testTargetPackageNullComponentName() {
    AndroidComponentAddress addr =
        AndroidComponentAddress.forBindIntent(
            new Intent().setPackage("com.foo").setAction(ApiConstants.ACTION_BIND));
    assertThat(addr.getPackage()).isEqualTo("com.foo");
    assertThat(addr.getComponent()).isNull();
  }

  @Test
  public void testTargetPackageNonNullComponentName() {
    AndroidComponentAddress addr =
        AndroidComponentAddress.forBindIntent(
            new Intent()
                .setComponent(new ComponentName("com.foo", "com.foo.BarService"))
                .setPackage("com.foo")
                .setAction(ApiConstants.ACTION_BIND));
    assertThat(addr.getPackage()).isEqualTo("com.foo");
  }

  @Test
  public void testAsBindIntent() {
    Intent bindIntent =
        new Intent()
            .setAction("foo")
            .setComponent(new ComponentName("pkg", "cls"))
            .setData(Uri.EMPTY)
            .setType("sometype")
            .addCategory("some-category")
            .addCategory("another-category");
    AndroidComponentAddress addr = AndroidComponentAddress.forBindIntent(bindIntent);
    assertThat(addr.asBindIntent().filterEquals(bindIntent)).isTrue();
  }

  @Test
  public void testPostCreateIntentMutation() {
    Intent bindIntent = new Intent().setAction("foo-action").setComponent(hostComponent);
    AndroidComponentAddress addr = AndroidComponentAddress.forBindIntent(bindIntent);
    bindIntent.setAction("bar-action");
    assertThat(addr.asBindIntent().getAction()).isEqualTo("foo-action");
  }

  @Test
  public void testPostBuildIntentMutation() {
    Intent bindIntent = new Intent().setAction("foo-action").setComponent(hostComponent);
    AndroidComponentAddress addr =
        AndroidComponentAddress.newBuilder().setBindIntent(bindIntent).build();
    bindIntent.setAction("bar-action");
    assertThat(addr.asBindIntent().getAction()).isEqualTo("foo-action");
  }

  @Test
  public void testBuilderMissingRequired() {
    IllegalStateException ise =
        assertThrows(
            IllegalStateException.class,
            () -> AndroidComponentAddress.newBuilder().setTargetUser(newUserHandle(123)).build());
    assertThat(ise.getMessage()).contains("bindIntent");
  }

  @Test
  @Config(sdk = 30)
  public void testAsAndroidAppUriSdk30() throws URISyntaxException {
    AndroidComponentAddress addr =
        AndroidComponentAddress.forRemoteComponent("com.foo", "com.foo.Service");
    AndroidComponentAddress addrClone =
        AndroidComponentAddress.forBindIntent(
            Intent.parseUri(addr.asAndroidAppUri(), URI_ANDROID_APP_SCHEME));
    assertThat(addr).isEqualTo(addrClone);
  }

  @Test
  @Config(sdk = 29)
  public void testAsAndroidAppUriSdk29() throws URISyntaxException {
    AndroidComponentAddress addr =
        AndroidComponentAddress.forRemoteComponent("com.foo", "com.foo.Service");
    AndroidComponentAddress addrClone =
        AndroidComponentAddress.forBindIntent(
            Intent.parseUri(addr.asAndroidAppUri(), URI_ANDROID_APP_SCHEME));
    // Can't test for equality because URI_ANDROID_APP_SCHEME adds a (redundant) package filter.
    assertThat(addr.getComponent()).isEqualTo(addrClone.getComponent());
    assertThat(addr.getAuthority()).isEqualTo(addrClone.getAuthority());
  }

  @Test
  public void testEquality() {
    new EqualsTester()
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent(ApiConstants.ACTION_BIND).setComponent(hostComponent)),
            AndroidComponentAddress.forComponent(hostComponent),
            AndroidComponentAddress.forContext(appContext),
            AndroidComponentAddress.forLocalComponent(appContext, appContext.getClass()),
            AndroidComponentAddress.forRemoteComponent(
                appContext.getPackageName(), appContext.getClass().getName()),
            AndroidComponentAddress.newBuilder()
                .setBindIntentFromComponent(hostComponent)
                .setTargetUser(null)
                .build())
        .addEqualityGroup(
            AndroidComponentAddress.forRemoteComponent("appy.mcappface", ".McActivity"))
        .addEqualityGroup(AndroidComponentAddress.forLocalComponent(appContext, getClass()))
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent().setAction("custom-action").setComponent(hostComponent)),
            AndroidComponentAddress.newBuilder()
                .setBindIntent(new Intent().setAction("custom-action").setComponent(hostComponent))
                .setTargetUser(null)
                .build())
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent()
                    .setAction("custom-action")
                    .setType("some-type")
                    .setComponent(hostComponent)))
        .testEquals();
  }

  @Test
  public void testUnequalTargetUsers() {
    new EqualsTester()
        .addEqualityGroup(
            AndroidComponentAddress.newBuilder()
                .setBindIntentFromComponent(hostComponent)
                .setTargetUser(newUserHandle(10))
                .build(),
            AndroidComponentAddress.newBuilder()
                .setBindIntentFromComponent(hostComponent)
                .setTargetUser(newUserHandle(10))
                .build())
        .addEqualityGroup(
            AndroidComponentAddress.newBuilder()
                .setBindIntentFromComponent(hostComponent)
                .setTargetUser(newUserHandle(11))
                .build())
        .addEqualityGroup(
            AndroidComponentAddress.newBuilder()
                .setBindIntentFromComponent(hostComponent)
                .setTargetUser(null)
                .build())
        .testEquals();
  }

  @Test
  @Config(sdk = 30)
  public void testPackageFilterEquality30AndUp() {
    new EqualsTester()
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent().setAction("action").setComponent(new ComponentName("pkg", "cls"))),
            AndroidComponentAddress.forBindIntent(
                new Intent()
                    .setAction("action")
                    .setPackage("pkg")
                    .setComponent(new ComponentName("pkg", "cls"))))
        .testEquals();
  }

  @Test
  @Config(sdk = 29)
  public void testPackageFilterEqualityPre30() {
    new EqualsTester()
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent().setAction("action").setComponent(new ComponentName("pkg", "cls"))))
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent()
                    .setAction("action")
                    .setPackage("pkg")
                    .setComponent(new ComponentName("pkg", "cls"))))
        .testEquals();
  }

  private static UserHandle newUserHandle(int userId) {
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeInt(userId);
      parcel.setDataPosition(0);
      return new UserHandle(parcel);
    } finally {
      parcel.recycle();
    }
  }
}
