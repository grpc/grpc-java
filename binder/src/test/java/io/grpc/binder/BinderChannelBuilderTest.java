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

import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class BinderChannelBuilderTest {
  private final Context appContext = ApplicationProvider.getApplicationContext();
  private final AndroidComponentAddress addr = AndroidComponentAddress.forContext(appContext);

  @Test
  public void strictLifecycleManagementForbidsIdleTimers() {
    BinderChannelBuilder builder = BinderChannelBuilder.forAddress(addr, appContext);
    builder.strictLifecycleManagement();
    try {
      builder.idleTimeout(10, TimeUnit.SECONDS);
      fail();
    } catch (IllegalStateException ise) {
      // Expected.
    }
  }
}
