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

import static android.content.Context.BIND_ABOVE_CLIENT;
import static android.content.Context.BIND_ADJUST_WITH_ACTIVITY;
import static android.content.Context.BIND_ALLOW_OOM_MANAGEMENT;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_IMPORTANT;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;
import static android.content.Context.BIND_NOT_FOREGROUND;
import static android.content.Context.BIND_NOT_PERCEPTIBLE;
import static android.content.Context.BIND_WAIVE_PRIORITY;
import static java.lang.Integer.toHexString;

import android.os.Build;
import androidx.annotation.RequiresApi;
import io.grpc.ExperimentalApi;

/**
 * An immutable set of flags affecting the behavior of {@link android.content.Context#bindService}.
 *
 * <p>Only flags suitable for use with gRPC/binderchannel are available to manipulate here. The
 * javadoc for each setter discusses each supported flag's semantics at the gRPC layer.
 */
public final class BindServiceFlags {
  /**
   * A set of default flags suitable for most applications.
   *
   * <p>The {@link Builder#setAutoCreate} flag is guaranteed to be set.
   */
  public static final BindServiceFlags DEFAULTS = newBuilder().setAutoCreate(true).build();

  private final int flags;

  private BindServiceFlags(int flags) {
    this.flags = flags;
  }

  /**
   * Returns the sum of all set flags as an int suitable for passing to ({@link
   * android.content.Context#bindService}.
   */
  public int toInteger() {
    return flags;
  }

  /**
   * Returns a new instance of {@link Builder} with *no* flags set.
   *
   * <p>Callers should start with {@code DEFAULTS.toBuilder()} instead.
   */
  static Builder newBuilder() {
    return new Builder(0);
  }

  /** Returns a new instance of {@link Builder} with the same set of flags as {@code this}. */
  public Builder toBuilder() {
    return new Builder(flags);
  }

  /** Builds an instance of {@link BindServiceFlags}. */
  public static final class Builder {
    private int flags;

    private Builder(int flags) {
      this.flags = flags;
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_ABOVE_CLIENT} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    public Builder setAboveClient(boolean newValue) {
      return setFlag(BIND_ABOVE_CLIENT, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_ADJUST_WITH_ACTIVITY} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    public Builder setAdjustWithActivity(boolean newValue) {
      return setFlag(BIND_ADJUST_WITH_ACTIVITY, newValue);
    }

    // TODO(b/274061424): Reference official constant and add RequiresApi declaration in place of
    // informal Javadoc warning when U is final.
    /**
     * Sets or clears the {@code android.content.Context#BIND_ALLOW_ACTIVITY_STARTS} flag.
     *
     * <p>This method allows for testing and development on Android U developer previews. Before
     * releasing production code which depends on this flag, verify that either the
     * {@code BIND_ALLOW_ACTIVITY_STARTS} flag has not changed from 0x200 during SDK development,
     * or wait for this method to be updated to point to the final flag and made non-experimental.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    @ExperimentalApi("To be finalized after Android U SDK finalization")
    public Builder setAllowActivityStarts(boolean newValue) {
      // https://developer.android.com/reference/android/content/Context#BIND_ALLOW_ACTIVITY_STARTS
      return setFlag(0x200, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_ALLOW_OOM_MANAGEMENT} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    public Builder setAllowOomManagement(boolean newValue) {
      return setFlag(BIND_ALLOW_OOM_MANAGEMENT, newValue);
    }

    /**
     * Controls whether sending a call over the associated {@link io.grpc.Channel} will cause the
     * target {@link android.app.Service} to be created and whether in-flight calls will keep it in
     * existence absent any other binding in the system.
     *
     * <p>If false, RPCs will not succeed until the remote Service comes into existence for some
     * other reason (if ever). See also {@link io.grpc.CallOptions#withWaitForReady()}.
     *
     * <p>See {@link android.content.Context#BIND_AUTO_CREATE} for more.
     *
     * @return this, for fluent construction
     */
    public Builder setAutoCreate(boolean newValue) {
      return setFlag(BIND_AUTO_CREATE, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_IMPORTANT} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    public Builder setImportant(boolean newValue) {
      return setFlag(BIND_IMPORTANT, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_INCLUDE_CAPABILITIES} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    @RequiresApi(api = 29)
    public Builder setIncludeCapabilities(boolean newValue) {
      return setFlag(BIND_INCLUDE_CAPABILITIES, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_NOT_FOREGROUND} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    public Builder setNotForeground(boolean newValue) {
      return setFlag(BIND_NOT_FOREGROUND, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_NOT_PERCEPTIBLE} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    @RequiresApi(api = 29)
    public Builder setNotPerceptible(boolean newValue) {
      return setFlag(BIND_NOT_PERCEPTIBLE, newValue);
    }

    /**
     * Sets or clears the {@link android.content.Context#BIND_WAIVE_PRIORITY} flag.
     *
     * <p>This flag has no additional meaning at the gRPC layer. See the Android docs for more.
     *
     * @return this, for fluent construction
     */
    public Builder setWaivePriority(boolean newValue) {
      return setFlag(BIND_WAIVE_PRIORITY, newValue);
    }

    /**
     * Returns a new instance of {@link BindServiceFlags} that reflects the state of this builder.
     */
    public BindServiceFlags build() {
      return new BindServiceFlags(flags);
    }

    private Builder setFlag(int flag, boolean newValue) {
      if (newValue) {
        flags |= flag;
      } else {
        flags &= ~flag;
      }
      return this;
    }
  }

  @Override
  public String toString() {
    return "BindServiceFlags{" + toHexString(flags) + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    BindServiceFlags that = (BindServiceFlags) o;
    return flags == that.flags;
  }

  @Override
  public int hashCode() {
    return flags;
  }
}
