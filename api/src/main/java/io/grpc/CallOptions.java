/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * The collection of runtime options for a new RPC call.
 *
 * <p>A field that is not set is {@code null}.
 */
@Immutable
@CheckReturnValue
public final class CallOptions {
  /**
   * A blank {@code CallOptions} that all fields are not set.
   */
  public static final CallOptions DEFAULT;

  static {
    Builder b = new Builder();
    b.customOptions = new Object[0][2];
    b.streamTracerFactories = Collections.emptyList();
    DEFAULT = b.build();
  }

  @Nullable
  private final Deadline deadline;

  @Nullable
  private final Executor executor;

  @Nullable
  private final String authority;

  @Nullable
  private final CallCredentials credentials;

  @Nullable
  private final String compressorName;

  private final Object[][] customOptions;

  private final List<ClientStreamTracer.Factory> streamTracerFactories;

  /**
   * Opposite to fail fast.
   */
  @Nullable
  private final Boolean waitForReady;

  @Nullable
  private final Integer maxInboundMessageSize;
  @Nullable
  private final Integer maxOutboundMessageSize;

  private CallOptions(Builder builder) {
    this.deadline = builder.deadline;
    this.executor = builder.executor;
    this.authority = builder.authority;
    this.credentials = builder.credentials;
    this.compressorName = builder.compressorName;
    this.customOptions = builder.customOptions;
    this.streamTracerFactories = builder.streamTracerFactories;
    this.waitForReady = builder.waitForReady;
    this.maxInboundMessageSize = builder.maxInboundMessageSize;
    this.maxOutboundMessageSize = builder.maxOutboundMessageSize;
  }

  static class Builder {
    Deadline deadline;
    Executor executor;
    String authority;
    CallCredentials credentials;
    String compressorName;
    Object[][] customOptions;
    // Unmodifiable list
    List<ClientStreamTracer.Factory> streamTracerFactories;
    Boolean waitForReady;
    Integer maxInboundMessageSize;
    Integer maxOutboundMessageSize;

    private CallOptions build() {
      return new CallOptions(this);
    }
  }

  /**
   * Override the HTTP/2 authority the channel claims to be connecting to. <em>This is not
   * generally safe.</em> Overriding allows advanced users to re-use a single Channel for multiple
   * services, even if those services are hosted on different domain names. That assumes the
   * server is virtually hosting multiple domains and is guaranteed to continue doing so. It is
   * rare for a service provider to make such a guarantee. <em>At this time, there is no security
   * verification of the overridden value, such as making sure the authority matches the server's
   * TLS certificate.</em>
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1767")
  public CallOptions withAuthority(@Nullable String authority) {
    Builder builder = toBuilder(this);
    builder.authority = authority;
    return builder.build();
  }

  /**
   * Returns a new {@code CallOptions} with the given call credentials.
   */
  public CallOptions withCallCredentials(@Nullable CallCredentials credentials) {
    Builder builder = toBuilder(this);
    builder.credentials = credentials;
    return builder.build();
  }

  /**
   * Sets the compression to use for the call.  The compressor must be a valid name known in the
   * {@link CompressorRegistry}.  By default, the "gzip" compressor will be available.
   *
   * <p>It is only safe to call this if the server supports the compression format chosen. There is
   * no negotiation performed; if the server does not support the compression chosen, the call will
   * fail.
   */
  public CallOptions withCompression(@Nullable String compressorName) {
    Builder builder = toBuilder(this);
    builder.compressorName = compressorName;
    return builder.build();
  }

  /**
   * Returns a new {@code CallOptions} with the given absolute deadline.
   *
   * <p>This is mostly used for propagating an existing deadline. {@link #withDeadlineAfter} is the
   * recommended way of setting a new deadline,
   *
   * @param deadline the deadline or {@code null} for unsetting the deadline.
   */
  public CallOptions withDeadline(@Nullable Deadline deadline) {
    Builder builder = toBuilder(this);
    builder.deadline = deadline;
    return builder.build();
  }

  /**
   * Returns a new {@code CallOptions} with a deadline that is after the given {@code duration} from
   * now.
   */
  public CallOptions withDeadlineAfter(long duration, TimeUnit unit) {
    return withDeadline(Deadline.after(duration, unit));
  }

  /**
   * Returns the deadline or {@code null} if the deadline is not set.
   */
  @Nullable
  public Deadline getDeadline() {
    return deadline;
  }

  /**
   * Enables <a href="https://github.com/grpc/grpc/blob/master/doc/wait-for-ready.md">
   * 'wait for ready'</a> for the call. Wait-for-ready queues the RPC until a connection is
   * available. This may dramatically increase the latency of the RPC, but avoids failing
   * "unnecessarily." The default queues the RPC until an attempt to connect has completed, but
   * fails RPCs without sending them if unable to connect.
   */
  public CallOptions withWaitForReady() {
    Builder builder = toBuilder(this);
    builder.waitForReady = Boolean.TRUE;
    return builder.build();
  }

  /**
   * Disables 'wait for ready' feature for the call.
   * This method should be rarely used because the default is without 'wait for ready'.
   */
  public CallOptions withoutWaitForReady() {
    Builder builder = toBuilder(this);
    builder.waitForReady = Boolean.FALSE;
    return builder.build();
  }

  /**
   * Returns the compressor's name.
   */
  @Nullable
  public String getCompressor() {
    return compressorName;
  }

  /**
   * Override the HTTP/2 authority the channel claims to be connecting to. <em>This is not
   * generally safe.</em> Overriding allows advanced users to re-use a single Channel for multiple
   * services, even if those services are hosted on different domain names. That assumes the
   * server is virtually hosting multiple domains and is guaranteed to continue doing so. It is
   * rare for a service provider to make such a guarantee. <em>At this time, there is no security
   * verification of the overridden value, such as making sure the authority matches the server's
   * TLS certificate.</em>
   */
  @Nullable
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1767")
  public String getAuthority() {
    return authority;
  }

  /**
   * Returns the call credentials.
   */
  @Nullable
  public CallCredentials getCredentials() {
    return credentials;
  }

  /**
   * Returns a new {@code CallOptions} with {@code executor} to be used instead of the default
   * executor specified with {@link ManagedChannelBuilder#executor}.
   */
  public CallOptions withExecutor(@Nullable Executor executor) {
    Builder builder = toBuilder(this);
    builder.executor = executor;
    return builder.build();
  }

  /**
   * Returns a new {@code CallOptions} with a {@code ClientStreamTracerFactory} in addition to
   * the existing factories.
   *
   * <p>This method doesn't replace existing factories, or try to de-duplicate factories.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
  public CallOptions withStreamTracerFactory(ClientStreamTracer.Factory factory) {
    ArrayList<ClientStreamTracer.Factory> newList =
        new ArrayList<>(streamTracerFactories.size() + 1);
    newList.addAll(streamTracerFactories);
    newList.add(factory);
    Builder builder = toBuilder(this);
    builder.streamTracerFactories = Collections.unmodifiableList(newList);
    return builder.build();
  }

  /**
   * Returns an immutable list of {@code ClientStreamTracerFactory}s.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
  public List<ClientStreamTracer.Factory> getStreamTracerFactories() {
    return streamTracerFactories;
  }

  /**
   * Key for a key-value pair. Uses reference equality.
   */
  public static final class Key<T> {
    private final String debugString;
    private final T defaultValue;

    private Key(String debugString, T defaultValue) {
      this.debugString = debugString;
      this.defaultValue = defaultValue;
    }

    /**
     * Returns the user supplied default value for this key.
     */
    public T getDefault() {
      return defaultValue;
    }

    @Override
    public String toString() {
      return debugString;
    }

    /**
     * Factory method for creating instances of {@link Key}.
     *
     * @param debugString a string used to describe this key, used for debugging.
     * @param defaultValue default value to return when value for key not set
     * @param <T> Key type
     * @return Key object
     * @deprecated Use {@link #create} or {@link #createWithDefault} instead. This method will
     *     be removed.
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1869")
    @Deprecated
    public static <T> Key<T> of(String debugString, T defaultValue) {
      Preconditions.checkNotNull(debugString, "debugString");
      return new Key<>(debugString, defaultValue);
    }

    /**
     * Factory method for creating instances of {@link Key}. The default value of the
     * key is {@code null}.
     *
     * @param debugString a debug string that describes this key.
     * @param <T> Key type
     * @return Key object
     * @since 1.13.0
     */
    public static <T> Key<T> create(String debugString) {
      Preconditions.checkNotNull(debugString, "debugString");
      return new Key<>(debugString, /*defaultValue=*/ null);
    }

    /**
     * Factory method for creating instances of {@link Key}.
     *
     * @param debugString a debug string that describes this key.
     * @param defaultValue default value to return when value for key not set
     * @param <T> Key type
     * @return Key object
     * @since 1.13.0
     */
    public static <T> Key<T> createWithDefault(String debugString, T defaultValue) {
      Preconditions.checkNotNull(debugString, "debugString");
      return new Key<>(debugString, defaultValue);
    }
  }

  /**
   * Sets a custom option. Any existing value for the key is overwritten.
   *
   * @param key The option key
   * @param value The option value.
   * @since 1.13.0
   */
  public <T> CallOptions withOption(Key<T> key, T value) {
    Preconditions.checkNotNull(key, "key");
    Preconditions.checkNotNull(value, "value");

    Builder builder = toBuilder(this);
    int existingIdx = -1;
    for (int i = 0; i < customOptions.length; i++) {
      if (key.equals(customOptions[i][0])) {
        existingIdx = i;
        break;
      }
    }

    builder.customOptions = new Object[customOptions.length + (existingIdx == -1 ? 1 : 0)][2];
    System.arraycopy(customOptions, 0, builder.customOptions, 0, customOptions.length);

    if (existingIdx == -1) {
      // Add a new option
      builder.customOptions[customOptions.length] = new Object[] {key, value};
    } else {
      // Replace an existing option
      builder.customOptions[existingIdx] = new Object[] {key, value};
    }

    return builder.build();
  }

  /**
   * Get the value for a custom option or its inherent default.
   * @param key Key identifying option
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1869")
  @SuppressWarnings("unchecked")
  public <T> T getOption(Key<T> key) {
    Preconditions.checkNotNull(key, "key");
    for (int i = 0; i < customOptions.length; i++) {
      if (key.equals(customOptions[i][0])) {
        return (T) customOptions[i][1];
      }
    }
    return key.defaultValue;
  }

  /**
   * Returns the executor override to use for this specific call, or {@code null} if there is no
   * override. The executor is only for servicing this one call, so is not safe to use after
   * {@link ClientCall.Listener#onClose}.
   */
  @Nullable
  public Executor getExecutor() {
    return executor;
  }

  /**
   * Returns whether <a href="https://github.com/grpc/grpc/blob/master/doc/wait-for-ready.md">
   * 'wait for ready'</a> option is enabled for the call. 'Fail fast' is the default option for gRPC
   * calls and 'wait for ready' is the opposite to it.
   */
  public boolean isWaitForReady() {
    return Boolean.TRUE.equals(waitForReady);
  }

  Boolean getWaitForReady() {
    return waitForReady;
  }

  /**
   * Sets the maximum allowed message size acceptable from the remote peer.  If unset, this will
   * default to the value set on the {@link ManagedChannelBuilder#maxInboundMessageSize(int)}.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2563")
  public CallOptions withMaxInboundMessageSize(int maxSize) {
    checkArgument(maxSize >= 0, "invalid maxsize %s", maxSize);
    Builder builder = toBuilder(this);
    builder.maxInboundMessageSize = maxSize;
    return builder.build();
  }

  /**
   * Sets the maximum allowed message size acceptable sent to the remote peer.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2563")
  public CallOptions withMaxOutboundMessageSize(int maxSize) {
    checkArgument(maxSize >= 0, "invalid maxsize %s", maxSize);
    Builder builder = toBuilder(this);
    builder.maxOutboundMessageSize = maxSize;
    return builder.build();
  }

  /**
   * Gets the maximum allowed message size acceptable from the remote peer.
   */
  @Nullable
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2563")
  public Integer getMaxInboundMessageSize() {
    return maxInboundMessageSize;
  }

  /**
   * Gets the maximum allowed message size acceptable to send the remote peer.
   */
  @Nullable
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2563")
  public Integer getMaxOutboundMessageSize() {
    return maxOutboundMessageSize;
  }

  /**
   * Copy CallOptions.
   */
  private static Builder toBuilder(CallOptions other) {
    Builder builder = new Builder();
    builder.deadline = other.deadline;
    builder.executor = other.executor;
    builder.authority = other.authority;
    builder.credentials = other.credentials;
    builder.compressorName = other.compressorName;
    builder.customOptions = other.customOptions;
    builder.streamTracerFactories = other.streamTracerFactories;
    builder.waitForReady = other.waitForReady;
    builder.maxInboundMessageSize = other.maxInboundMessageSize;
    builder.maxOutboundMessageSize = other.maxOutboundMessageSize;
    return builder;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("deadline", deadline)
        .add("authority", authority)
        .add("callCredentials", credentials)
        .add("executor", executor != null ? executor.getClass() : null)
        .add("compressorName", compressorName)
        .add("customOptions", Arrays.deepToString(customOptions))
        .add("waitForReady", isWaitForReady())
        .add("maxInboundMessageSize", maxInboundMessageSize)
        .add("maxOutboundMessageSize", maxOutboundMessageSize)
        .add("streamTracerFactories", streamTracerFactories)
        .toString();
  }
}
