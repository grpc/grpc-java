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

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.util.Log;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.InlineMe;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.InternalManagedChannelProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.MethodDescriptor;
import io.grpc.internal.GrpcUtil;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Builds a {@link ManagedChannel} that, when provided with a {@link Context}, will automatically
 * monitor the Android device's network state to smoothly handle intermittent network failures.
 *
 * <p>Currently only compatible with gRPC's OkHttp transport, which must be available at runtime.
 *
 * <p>Requires the Android ACCESS_NETWORK_STATE permission.
 *
 * @since 1.12.0
 */
public final class AndroidChannelBuilder extends ForwardingChannelBuilder<AndroidChannelBuilder> {

  private static final String LOG_TAG = "AndroidChannelBuilder";

  @Nullable private static final ManagedChannelProvider OKHTTP_CHANNEL_PROVIDER = findOkHttp();

  private static ManagedChannelProvider findOkHttp() {
    Class<?> klassRaw;
    try {
      klassRaw = Class.forName("io.grpc.okhttp.OkHttpChannelProvider");
    } catch (ClassNotFoundException e) {
      Log.w(LOG_TAG, "Failed to find OkHttpChannelProvider", e);
      return null;
    }
    Class<? extends ManagedChannelProvider> klass;
    try {
      klass = klassRaw.asSubclass(ManagedChannelProvider.class);
    } catch (ClassCastException e) {
      Log.w(LOG_TAG, "Couldn't cast OkHttpChannelProvider to ManagedChannelProvider", e);
      return null;
    }
    ManagedChannelProvider provider;
    try {
      provider = klass.getConstructor().newInstance();
    } catch (Exception e) {
      Log.w(LOG_TAG, "Failed to construct OkHttpChannelProvider", e);
      return null;
    }
    if (!InternalManagedChannelProvider.isAvailable(provider)) {
      Log.w(LOG_TAG, "OkHttpChannelProvider.isAvailable() returned false");
      return null;
    }
    return provider;
  }

  private final ManagedChannelBuilder<?> delegateBuilder;

  @Nullable private Context context;

  /**
   * Creates a new builder with the given target string that will be resolved by
   * {@link io.grpc.NameResolver}.
   */
  public static AndroidChannelBuilder forTarget(String target) {
    return new AndroidChannelBuilder(target);
  }

  /**
   * Creates a new builder with the given host and port.
   */
  public static AndroidChannelBuilder forAddress(String name, int port) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port));
  }

  /**
   * Creates a new builder, which delegates to the given ManagedChannelBuilder.
   *
   * @deprecated Use {@link #usingBuilder(ManagedChannelBuilder)} instead.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6043")
  @Deprecated
  @InlineMe(
      replacement = "AndroidChannelBuilder.usingBuilder(builder)",
      imports = "io.grpc.android.AndroidChannelBuilder")
  public static AndroidChannelBuilder fromBuilder(ManagedChannelBuilder<?> builder) {
    return usingBuilder(builder);
  }

  /**
   * Creates a new builder, which delegates to the given ManagedChannelBuilder.
   *
   * <p>The provided {@code builder} becomes "owned" by AndroidChannelBuilder. The caller should
   * not modify the provided builder and AndroidChannelBuilder may modify it. That implies reusing
   * the provided builder to build another channel may result with unexpected configurations. That
   * usage should be discouraged.
   *
   * @since 1.24.0
   */
  public static AndroidChannelBuilder usingBuilder(ManagedChannelBuilder<?> builder) {
    return new AndroidChannelBuilder(builder);
  }

  private AndroidChannelBuilder(String target) {
    if (OKHTTP_CHANNEL_PROVIDER == null) {
      throw new UnsupportedOperationException("Unable to load OkHttpChannelProvider");
    }
    delegateBuilder =
        InternalManagedChannelProvider.builderForTarget(OKHTTP_CHANNEL_PROVIDER, target);
  }

  private AndroidChannelBuilder(ManagedChannelBuilder<?> delegateBuilder) {
    this.delegateBuilder = Preconditions.checkNotNull(delegateBuilder, "delegateBuilder");
  }

  /**
   * Enables automatic monitoring of the device's network state.
   */
  public AndroidChannelBuilder context(Context context) {
    this.context = context;
    return this;
  }

  @Override
  @SuppressWarnings("deprecation") // Not extending ForwardingChannelBuilder2 to preserve ABI.
  protected ManagedChannelBuilder<?> delegate() {
    return delegateBuilder;
  }

  /**
   * Builds a channel with current configurations.
   */
  @Override
  public ManagedChannel build() {
    return new AndroidChannel(delegateBuilder.build(), context);
  }

  /**
   * Wraps an OkHttp channel and handles invoking the appropriate methods (e.g., {@link
   * ManagedChannel#enterIdle}) when the device network state changes.
   */
  @VisibleForTesting
  static final class AndroidChannel extends ManagedChannel {

    private final ManagedChannel delegate;

    @Nullable private final Context context;
    @Nullable private final ConnectivityManager connectivityManager;

    private final Object lock = new Object();

    @GuardedBy("lock")
    private Runnable unregisterRunnable;

    @VisibleForTesting
    AndroidChannel(final ManagedChannel delegate, @Nullable Context context) {
      this.delegate = delegate;
      this.context = context;

      if (context != null) {
        connectivityManager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
          configureNetworkMonitoring();
        } catch (SecurityException e) {
          Log.w(
              LOG_TAG,
              "Failed to configure network monitoring. Does app have ACCESS_NETWORK_STATE"
                  + " permission?",
              e);
        }
      } else {
        connectivityManager = null;
      }
    }

    @GuardedBy("lock")
    private void configureNetworkMonitoring() {
      // Android N added the registerDefaultNetworkCallback API to listen to changes in the device's
      // default network. For earlier Android API levels, use the BroadcastReceiver API.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connectivityManager != null) {
        final DefaultNetworkCallback defaultNetworkCallback = new DefaultNetworkCallback();
        connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback);
        unregisterRunnable =
            new Runnable() {
              @TargetApi(Build.VERSION_CODES.LOLLIPOP)
              @Override
              public void run() {
                connectivityManager.unregisterNetworkCallback(defaultNetworkCallback);
              }
            };
      } else {
        final NetworkReceiver networkReceiver = new NetworkReceiver();
        @SuppressWarnings("deprecation")
        IntentFilter networkIntentFilter =
            new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkReceiver, networkIntentFilter);
        unregisterRunnable =
            new Runnable() {
              @TargetApi(Build.VERSION_CODES.LOLLIPOP)
              @Override
              public void run() {
                context.unregisterReceiver(networkReceiver);
              }
            };
      }
    }

    private void unregisterNetworkListener() {
      synchronized (lock) {
        if (unregisterRunnable != null) {
          unregisterRunnable.run();
          unregisterRunnable = null;
        }
      }
    }

    @Override
    public ManagedChannel shutdown() {
      unregisterNetworkListener();
      return delegate.shutdown();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public ManagedChannel shutdownNow() {
      unregisterNetworkListener();
      return delegate.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
        MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
      return delegate.newCall(methodDescriptor, callOptions);
    }

    @Override
    public String authority() {
      return delegate.authority();
    }

    @Override
    public ConnectivityState getState(boolean requestConnection) {
      return delegate.getState(requestConnection);
    }

    @Override
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
      delegate.notifyWhenStateChanged(source, callback);
    }

    @Override
    public void resetConnectBackoff() {
      delegate.resetConnectBackoff();
    }

    @Override
    public void enterIdle() {
      delegate.enterIdle();
    }

    /** Respond to changes in the default network. Only used on API levels 24+. */
    @TargetApi(Build.VERSION_CODES.N)
    private class DefaultNetworkCallback extends ConnectivityManager.NetworkCallback {
      @Override
      public void onAvailable(Network network) {
        delegate.enterIdle();
      }

      @Override
      public void onBlockedStatusChanged(Network network, boolean blocked) {
        if (!blocked) {
          delegate.enterIdle();
        }
      }
    }

    /** Respond to network changes. Only used on API levels < 24. */
    private class NetworkReceiver extends BroadcastReceiver {
      private boolean isConnected = false;

      @SuppressWarnings("deprecation")
      @Override
      public void onReceive(Context context, Intent intent) {
        ConnectivityManager conn =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo networkInfo = conn.getActiveNetworkInfo();
        boolean wasConnected = isConnected;
        isConnected = networkInfo != null && networkInfo.isConnected();
        if (isConnected && !wasConnected) {
          delegate.enterIdle();
        }
      }
    }
  }
}
