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
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import com.google.common.base.Objects;
import io.grpc.ExperimentalApi;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * The target of an Android {@link android.app.Service} binding.
 *
 * <p>Consists of an explicit {@link Intent} that identifies an {@link android.os.IBinder} returned
 * by some Service's {@link android.app.Service#onBind(Intent)} method. You can specify that Service
 * by {@link ComponentName} or let Android resolve it using the Intent's other fields (package,
 * action, data URI, type and category set). See <a
 * href="https://developer.android.com/guide/components/bound-services">Bound Services Overview</a>
 * and <a href="https://developer.android.com/guide/components/intents-filters">Intents and Intent
 * Filters</a> for more.
 *
 * <p>For convenience in the common case where a {@link android.app.Service} exposes just one {@link
 * android.os.IBinder} IPC interface, we provide default values for the binding {@link Intent}
 * fields, namely, an action of {@link ApiConstants#ACTION_BIND}, an empty category set and null
 * type and data URI.
 *
 * <p>Optionally contains a {@link UserHandle} that must be considered wherever the {@link Intent}
 * is evaluated.
 *
 * <p>{@link #equals(Object)} uses {@link Intent#filterEquals(Intent)} semantics to compare Intents.
 */
public final class AndroidComponentAddress extends SocketAddress {
  private static final long serialVersionUID = 0L;

  private final Intent bindIntent; // "Explicit", having either a component or package restriction.

  @Nullable
  private final UserHandle targetUser; // null means the same user that hosts this process.

  protected AndroidComponentAddress(Intent bindIntent, @Nullable UserHandle targetUser) {
    checkArgument(
        bindIntent.getComponent() != null || bindIntent.getPackage() != null,
        "'bindIntent' must be explicit. Specify either a package or ComponentName.");
    this.bindIntent = bindIntent;
    this.targetUser = targetUser;
  }

  /**
   * Creates an address for the given {@link android.app.Service} instance with the default binding
   * {@link Intent}.
   */
  public static AndroidComponentAddress forContext(Context context) {
    return forLocalComponent(context, context.getClass());
  }

  /**
   * Creates an address referencing a {@link android.app.Service} hosted by this application and
   * using the default binding {@link Intent}.
   */
  public static AndroidComponentAddress forLocalComponent(Context context, Class<?> cls) {
    return forComponent(new ComponentName(context, cls));
  }

  /**
   * Creates an address referencing a {@link android.app.Service} in another application and using
   * the default binding {@link Intent}.
   *
   * @param applicationPackage The package name of the application containing the server.
   * @param serviceClassName The full class name of the Android Service to bind to.
   */
  public static AndroidComponentAddress forRemoteComponent(
      String applicationPackage, String serviceClassName) {
    return forComponent(new ComponentName(applicationPackage, serviceClassName));
  }

  /**
   * Creates a new address that uses the "filter matching" fields of <code>intent</code> as the
   * binding {@link Intent}.
   *
   * <p><code>intent</code> must be "explicit", i.e. having either a target component ({@link
   * Intent#getComponent()}) or package restriction ({@link Intent#getPackage()}). See <a
   * href="https://developer.android.com/guide/components/intents-filters">Intents and Intent
   * Filters</a> for more.
   *
   * <p>A multi-tenant {@link android.app.Service} can call this from its {@link
   * android.app.Service#onBind(Intent)} method to locate an appropriate {@link io.grpc.Server} by
   * listening address.
   *
   * @throws IllegalArgumentException if 'intent' isn't "explicit"
   */
  public static AndroidComponentAddress forBindIntent(Intent intent) {
    return new AndroidComponentAddress(intent.cloneFilter(), null);
  }

  /**
   * Creates an address referencing the specified {@link android.app.Service} component and using
   * the default binding {@link Intent}.
   */
  public static AndroidComponentAddress forComponent(ComponentName component) {
    return new AndroidComponentAddress(
        new Intent(ApiConstants.ACTION_BIND).setComponent(component), null);
  }

  /**
   * Returns the Authority which is the package name of the target app.
   *
   * <p>See {@link android.content.ComponentName} and {@link Intent#getPackage()}.
   */
  public String getAuthority() {
    return getPackage();
  }

  /**
   * Returns the package target of the wrapped {@link Intent}, either from its package restriction
   * or, if not present, its fully qualified {@link ComponentName}.
   */
  public String getPackage() {
    if (bindIntent.getPackage() != null) {
      return bindIntent.getPackage();
    } else {
      return bindIntent.getComponent().getPackageName();
    }
  }

  /** Returns the {@link ComponentName} of this binding {@link Intent}, or null if one isn't set. */
  @Nullable
  public ComponentName getComponent() {
    return bindIntent.getComponent();
  }

  /**
   * Returns this address as an explicit {@link Intent} suitable for passing to {@link
   * Context#bindService}.
   *
   * <p>NB: The returned Intent does not specify a target Android user. If {@link #getTargetUser()}
   * is non-null, {@link Context#bindServiceAsUser} should be called instead.
   */
  public Intent asBindIntent() {
    return bindIntent.cloneFilter(); // Intent is mutable so return a copy.
  }

  /**
   * Returns this address as an "android-app://" uri.
   *
   * <p>See {@link Intent#URI_ANDROID_APP_SCHEME} for details.
   */
  public String asAndroidAppUri() {
    Intent intentForUri = bindIntent;
    if (intentForUri.getPackage() == null) {
      // URI_ANDROID_APP_SCHEME requires an "explicit package name" which isn't set by any of our
      // factory methods. Oddly, a ComponentName is not enough.
      intentForUri = intentForUri.cloneFilter().setPackage(getComponent().getPackageName());
    }
    return intentForUri.toUri(URI_ANDROID_APP_SCHEME);
  }

  @Override
  public int hashCode() {
    Intent intentForHashCode = bindIntent;
    // Clear a (usually redundant) package filter to work around an Android >= 31 bug where certain
    // Intents compare filterEquals() but have different filterHashCode() values. It's always safe
    // to include fewer fields in the hashCode() computation.
    if (intentForHashCode.getPackage() != null) {
      intentForHashCode = intentForHashCode.cloneFilter().setPackage(null);
    }
    return intentForHashCode.filterHashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof AndroidComponentAddress) {
      AndroidComponentAddress that = (AndroidComponentAddress) obj;
      return bindIntent.filterEquals(that.bindIntent)
          && Objects.equal(this.targetUser, that.targetUser);
    }
    return false;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("AndroidComponentAddress[");
    if (targetUser != null) {
      builder.append(targetUser);
      builder.append("@");
    }
    builder.append(bindIntent);
    builder.append("]");
    return builder.toString();
  }

  /**
   * Identifies the Android user in which the bind Intent will be evaluated.
   *
   * <p>Returns the {@link UserHandle}, or null which means that the Android user hosting the
   * current process will be used.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
  @Nullable
  public UserHandle getTargetUser() {
    return targetUser;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Fluently builds instances of {@link AndroidComponentAddress}. */
  public static class Builder {
    Intent bindIntent;
    UserHandle targetUser;

    /**
     * Sets the binding {@link Intent} to one having the "filter matching" fields of 'intent'.
     *
     * <p>'intent' must be "explicit", i.e. having either a target component ({@link
     * Intent#getComponent()}) or package restriction ({@link Intent#getPackage()}).
     */
    public Builder setBindIntent(Intent intent) {
      this.bindIntent = intent.cloneFilter();
      return this;
    }

    /**
     * Sets the binding {@link Intent} to one with the specified 'component' and default values for
     * all other fields, for convenience.
     */
    public Builder setBindIntentFromComponent(ComponentName component) {
      this.bindIntent = new Intent(ApiConstants.ACTION_BIND).setComponent(component);
      return this;
    }

    /** See {@link AndroidComponentAddress#getTargetUser()}. */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
    public Builder setTargetUser(@Nullable UserHandle targetUser) {
      this.targetUser = targetUser;
      return this;
    }

    public AndroidComponentAddress build() {
      // We clone any incoming mutable intent in the setter, not here. AndroidComponentAddress
      // itself is immutable so multiple instances built from here can safely share 'bindIntent'.
      checkState(bindIntent != null, "Required property 'bindIntent' unset");
      return new AndroidComponentAddress(bindIntent, targetUser);
    }
  }
}
