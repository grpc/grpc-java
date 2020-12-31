/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.android;

import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.N;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.RuntimeEnvironment.getApiLevel;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.annotation.LooperMode.Mode.LEGACY;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkInfo;

@LooperMode(LEGACY)
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {AndroidChannelBuilderTest.ShadowDefaultNetworkListenerConnectivityManager.class})
@SuppressWarnings("deprecation")
public final class AndroidChannelBuilderTest {
  private final android.net.NetworkInfo WIFI_CONNECTED =
      ShadowNetworkInfo.newInstance(
          android.net.NetworkInfo.DetailedState.CONNECTED,
          ConnectivityManager.TYPE_WIFI,
          0,
          true,
          android.net.NetworkInfo.State.CONNECTED);
  private final android.net.NetworkInfo WIFI_DISCONNECTED =
      ShadowNetworkInfo.newInstance(
          android.net.NetworkInfo.DetailedState.DISCONNECTED,
          ConnectivityManager.TYPE_WIFI,
          0,
          true,
          android.net.NetworkInfo.State.DISCONNECTED);
  private final android.net.NetworkInfo MOBILE_CONNECTED =
      ShadowNetworkInfo.newInstance(
          android.net.NetworkInfo.DetailedState.CONNECTED,
          ConnectivityManager.TYPE_MOBILE,
          ConnectivityManager.TYPE_MOBILE_MMS,
          true,
          android.net.NetworkInfo.State.CONNECTED);
  private final android.net.NetworkInfo MOBILE_DISCONNECTED =
      ShadowNetworkInfo.newInstance(
          android.net.NetworkInfo.DetailedState.DISCONNECTED,
          ConnectivityManager.TYPE_MOBILE,
          ConnectivityManager.TYPE_MOBILE_MMS,
          true,
          android.net.NetworkInfo.State.DISCONNECTED);

  private ConnectivityManager connectivityManager;

  @Before
  public void setUp() {
    connectivityManager =
        (ConnectivityManager)
            ApplicationProvider
                .getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  @Test
  public void channelBuilderClassFoundReflectively() {
    // This should not throw with OkHttpChannelBuilder on the classpath
    AndroidChannelBuilder.forTarget("target");
  }

  @Test
  public void usingBuilderConstructor() {
    OkHttpChannelBuilder wrappedBuilder = OkHttpChannelBuilder.forTarget("target");
    AndroidChannelBuilder androidBuilder = AndroidChannelBuilder.usingBuilder(wrappedBuilder);
    assertThat(androidBuilder.delegate()).isSameInstanceAs(wrappedBuilder);
  }

  @Test
  @Config(sdk = 23)
  public void nullContextDoesNotThrow_api23() {
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel = new AndroidChannelBuilder.AndroidChannel(delegateChannel, null);

    // Network change and shutdown should be no-op for the channel without an Android Context
    shadowOf(connectivityManager).setActiveNetworkInfo(WIFI_CONNECTED);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    androidChannel.shutdown();

    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);
  }

  @Test
  @Config(sdk = 24)
  public void nullContextDoesNotThrow_api24() {
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_DISCONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel = new AndroidChannelBuilder.AndroidChannel(delegateChannel, null);

    // Network change and shutdown should be no-op for the channel without an Android Context
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_CONNECTED);
    androidChannel.shutdown();

    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);
  }

  @Test
  @Config(sdk = 23)
  public void networkChanges_api23() {
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel =
        new AndroidChannelBuilder.AndroidChannel(
            delegateChannel, ApplicationProvider.getApplicationContext());
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);

    // On API levels < 24, the broadcast receiver will invoke enterIdle() on the first
    // connectivity action broadcast regardless of previous connection status
    shadowOf(connectivityManager).setActiveNetworkInfo(WIFI_CONNECTED);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(delegateChannel.enterIdleCount).isEqualTo(1);

    // The broadcast receiver may fire when the active network status has not actually changed
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(delegateChannel.enterIdleCount).isEqualTo(1);

    // Drop the connection
    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(delegateChannel.enterIdleCount).isEqualTo(1);

    // Notify that a new but not connected network is available
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_DISCONNECTED);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(delegateChannel.enterIdleCount).isEqualTo(1);

    // Establish a connection
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_CONNECTED);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(delegateChannel.enterIdleCount).isEqualTo(2);

    // Disconnect, then shutdown the channel and verify that the broadcast receiver has been
    // unregistered
    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    androidChannel.shutdown();
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_CONNECTED);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));

    assertThat(delegateChannel.enterIdleCount).isEqualTo(2);
  }

  @Test
  @Config(sdk = 24)
  public void networkChanges_api24() {
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_DISCONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel =
        new AndroidChannelBuilder.AndroidChannel(
            delegateChannel, ApplicationProvider.getApplicationContext());
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);

    // Establish an initial network connection
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_CONNECTED);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(1);

    // Switch to another network to trigger enterIdle()
    shadowOf(connectivityManager).setActiveNetworkInfo(WIFI_CONNECTED);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(2);

    // Switch to an offline network and then to null
    shadowOf(connectivityManager).setActiveNetworkInfo(WIFI_DISCONNECTED);
    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(2);

    // Establish a connection
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_CONNECTED);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(3);

    // Disconnect, then shutdown the channel and verify that the callback has been unregistered
    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    androidChannel.shutdown();
    shadowOf(connectivityManager).setActiveNetworkInfo(MOBILE_CONNECTED);

    assertThat(delegateChannel.enterIdleCount).isEqualTo(3);
  }

  @Test
  @Config(sdk = 23)
  public void shutdownNowUnregistersBroadcastReceiver_api23() {
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel =
        new AndroidChannelBuilder.AndroidChannel(
            delegateChannel, ApplicationProvider.getApplicationContext());

    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    androidChannel.shutdownNow();
    shadowOf(connectivityManager).setActiveNetworkInfo(WIFI_CONNECTED);
    ApplicationProvider
        .getApplicationContext()
        .sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));

    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);
  }

  @Test
  @Config(sdk = 24)
  public void shutdownNowUnregistersNetworkCallback_api24() {
    shadowOf(connectivityManager).setActiveNetworkInfo(null);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel =
        new AndroidChannelBuilder.AndroidChannel(
            delegateChannel, ApplicationProvider.getApplicationContext());

    androidChannel.shutdownNow();
    shadowOf(connectivityManager).setActiveNetworkInfo(WIFI_CONNECTED);

    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);
  }

  /**
   * Extends Robolectric ShadowConnectivityManager to handle Android N's
   * registerDefaultNetworkCallback API.
   */
  @Implements(value = ConnectivityManager.class)
  public static class ShadowDefaultNetworkListenerConnectivityManager
      extends ShadowConnectivityManager {
    private HashSet<ConnectivityManager.NetworkCallback> defaultNetworkCallbacks = new HashSet<>();

    public ShadowDefaultNetworkListenerConnectivityManager() {
      super();
    }

    @Override
    public void setActiveNetworkInfo(android.net.NetworkInfo activeNetworkInfo) {
      if (getApiLevel() >= N) {
        android.net.NetworkInfo previousNetworkInfo = getActiveNetworkInfo();
        if (activeNetworkInfo != null && activeNetworkInfo.isConnected()) {
          notifyDefaultNetworkCallbacksOnAvailable(
              ShadowNetwork.newInstance(activeNetworkInfo.getType() /* use type as network ID */));
        } else if (previousNetworkInfo != null) {
          notifyDefaultNetworkCallbacksOnLost(
              ShadowNetwork.newInstance(
                  previousNetworkInfo.getType() /* use type as network ID */));
        }
      }
      super.setActiveNetworkInfo(activeNetworkInfo);
    }

    private void notifyDefaultNetworkCallbacksOnAvailable(Network network) {
      for (ConnectivityManager.NetworkCallback networkCallback : defaultNetworkCallbacks) {
        networkCallback.onAvailable(network);
      }
    }

    private void notifyDefaultNetworkCallbacksOnLost(Network network) {
      for (ConnectivityManager.NetworkCallback networkCallback : defaultNetworkCallbacks) {
        networkCallback.onLost(network);
      }
    }

    @Implementation(minSdk = N)
    @Override
    protected void registerDefaultNetworkCallback(
        ConnectivityManager.NetworkCallback networkCallback) {
      defaultNetworkCallbacks.add(networkCallback);
    }

    @Implementation(minSdk = LOLLIPOP)
    @Override
    public void unregisterNetworkCallback(ConnectivityManager.NetworkCallback networkCallback) {
      if (getApiLevel() >= N) {
        if (networkCallback != null || defaultNetworkCallbacks.contains(networkCallback)) {
          defaultNetworkCallbacks.remove(networkCallback);
        }
      }
      super.unregisterNetworkCallback(networkCallback);
    }
  }

  private static class TestChannel extends ManagedChannel {
    int enterIdleCount;

    @Override
    public ManagedChannel shutdown() {
      return null;
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return null;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return false;
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
        MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
      return null;
    }

    @Override
    public String authority() {
      return null;
    }

    @Override
    public void enterIdle() {
      enterIdleCount++;
    }
  }
}
