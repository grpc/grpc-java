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

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import io.grpc.Server;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

@RunWith(RobolectricTestRunner.class)
public final class BinderServerBuilderTest {

  private final IBinderReceiver binderReceiver = new IBinderReceiver();

  @Test
  public void shouldExposeListeningBinderUponBuild() {
    ServiceController<SomeService> controller = Robolectric.buildService(SomeService.class);
    SomeService service = controller.create().get();

    AndroidComponentAddress listenAddress = AndroidComponentAddress.forContext(service);
    Server server = BinderServerBuilder.forAddress(listenAddress, binderReceiver).build();
    try {
      assertThat(binderReceiver.get()).isNotNull();
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  public void shouldExposeSpecifiedListeningAddressUponBuild() throws IOException {
    AndroidComponentAddress listenAddress =
        AndroidComponentAddress.forBindIntent(
            new Intent()
                .setAction("some-action")
                .setComponent(new ComponentName("com.foo", "com.foo.SomeService")));
    Server server = BinderServerBuilder.forAddress(listenAddress, binderReceiver).build().start();
    try {
      assertThat(server.getListenSockets()).containsExactly(listenAddress);
    } finally {
      server.shutdownNow();
    }
  }

  private static class SomeService extends Service {
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
      return null;
    }
  }
}
