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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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
  public void testEquality() {
    new EqualsTester()
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent(ApiConstants.ACTION_BIND).setComponent(hostComponent)),
            AndroidComponentAddress.forComponent(hostComponent),
            AndroidComponentAddress.forContext(appContext),
            AndroidComponentAddress.forLocalComponent(appContext, appContext.getClass()),
            AndroidComponentAddress.forRemoteComponent(
                appContext.getPackageName(), appContext.getClass().getName()))
        .addEqualityGroup(
            AndroidComponentAddress.forRemoteComponent("appy.mcappface", ".McActivity"))
        .addEqualityGroup(AndroidComponentAddress.forLocalComponent(appContext, getClass()))
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent().setAction("custom-action").setComponent(hostComponent)))
        .addEqualityGroup(
            AndroidComponentAddress.forBindIntent(
                new Intent()
                    .setAction("custom-action")
                    .setType("some-type")
                    .setComponent(hostComponent)))
        .testEquals();
  }
}
