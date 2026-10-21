/*
 * Copyright 2021 The gRPC Authors
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

import static android.os.Looper.getMainLooper;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.robolectric.Shadows.shadowOf;

import androidx.lifecycle.LifecycleService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

@RunWith(RobolectricTestRunner.class)
public final class LifecycleOnDestroyHelperTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ServiceController<MyService> sourceController;
  private MyService sourceService;

  @Mock ManagedChannel mockChannel;
  @Mock Server mockServer;

  @Before
  public void setup() {
    sourceController = Robolectric.buildService(MyService.class);
    sourceService = sourceController.create().get();
  }

  @Test
  public void shouldShutdownChannelUponSourceDestruction() {
    LifecycleOnDestroyHelper.shutdownUponDestruction(sourceService.getLifecycle(), mockChannel);
    shadowOf(getMainLooper()).idle();
    verifyNoInteractions(mockChannel);

    sourceController.destroy();
    shadowOf(getMainLooper()).idle();
    verify(mockChannel).shutdownNow();
  }

  @Test
  public void shouldShutdownChannelForInitiallyDestroyedSource() {
    sourceController.destroy();
    shadowOf(getMainLooper()).idle();

    LifecycleOnDestroyHelper.shutdownUponDestruction(sourceService.getLifecycle(), mockChannel);
    verify(mockChannel).shutdownNow();
  }

  @Test
  public void shouldShutdownServerUponServiceDestruction() {
    LifecycleOnDestroyHelper.shutdownUponDestruction(sourceService.getLifecycle(), mockServer);
    shadowOf(getMainLooper()).idle();
    verifyNoInteractions(mockServer);

    sourceController.destroy();
    shadowOf(getMainLooper()).idle();
    verify(mockServer).shutdownNow();
  }

  @Test
  public void shouldShutdownServerForInitiallyDestroyedSource() {
    sourceController.destroy();
    shadowOf(getMainLooper()).idle();

    LifecycleOnDestroyHelper.shutdownUponDestruction(sourceService.getLifecycle(), mockServer);
    verify(mockServer).shutdownNow();
  }

  private static class MyService extends LifecycleService {}
}
