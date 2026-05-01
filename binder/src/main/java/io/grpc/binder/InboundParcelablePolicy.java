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

import com.google.common.base.Preconditions;
import io.grpc.ExperimentalApi;

/**
 * Contains the policy for accepting inbound parcelable objects.
 *
 * <p>Since parcelables are generally error prone and parsing a parcelable can have unspecified
 * side-effects, their use is generally discouraged. Some use cases require them though (E.g. when
 * dealing with some platform-defined objects), so this policy allows them to be supported.
 *
 * <p>Parcelables can arrive as RPC messages, or as metadata values (in headers or footers). The
 * default is to reject both cases, failing the RPC with a PERMISSION_DENED status code. This policy
 * can be updated to accept one or both cases.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class InboundParcelablePolicy {

  /** The maximum allowed total size of Parcelables in metadata. */
  public static final int MAX_PARCELABLE_METADATA_SIZE = 32 * 1024;

  public static final InboundParcelablePolicy DEFAULT =
      new InboundParcelablePolicy(false, false, MAX_PARCELABLE_METADATA_SIZE);

  private final boolean acceptParcelableMetadataValues;
  private final boolean acceptParcelableMessages;
  private final int maxParcelableMetadataSize;

  private InboundParcelablePolicy(
      boolean acceptParcelableMetadataValues,
      boolean acceptParcelableMessages,
      int maxParcelableMetadataSize) {
    this.acceptParcelableMetadataValues = acceptParcelableMetadataValues;
    this.acceptParcelableMessages = acceptParcelableMessages;
    this.maxParcelableMetadataSize = maxParcelableMetadataSize;
  }

  public boolean shouldAcceptParcelableMetadataValues() {
    return acceptParcelableMetadataValues;
  }

  public boolean shouldAcceptParcelableMessages() {
    return acceptParcelableMessages;
  }

  public int getMaxParcelableMetadataSize() {
    return maxParcelableMetadataSize;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** A builder for InboundParcelablePolicy. */
  public static final class Builder {
    private boolean acceptParcelableMetadataValues = DEFAULT.acceptParcelableMetadataValues;
    private boolean acceptParcelableMessages = DEFAULT.acceptParcelableMessages;
    private int maxParcelableMetadataSize = DEFAULT.maxParcelableMetadataSize;

    /** Sets whether the policy should accept parcelable metadata values. */
    public Builder setAcceptParcelableMetadataValues(boolean acceptParcelableMetadataValues) {
      this.acceptParcelableMetadataValues = acceptParcelableMetadataValues;
      return this;
    }

    /** Sets whether the policy should accept parcelable messages. */
    public Builder setAcceptParcelableMessages(boolean acceptParcelableMessages) {
      this.acceptParcelableMessages = acceptParcelableMessages;
      return this;
    }

    /**
     * Sets the maximum allowed total size of parcelables in metadata.
     *
     * @param maxParcelableMetadataSize must not exceed {@link #MAX_PARCELABLE_METADATA_SIZE}
     */
    public Builder setMaxParcelableMetadataSize(int maxParcelableMetadataSize) {
      Preconditions.checkArgument(
          maxParcelableMetadataSize <= MAX_PARCELABLE_METADATA_SIZE,
          "Parcelable metadata size can't exceed MAX_PARCELABLE_METADATA_SIZE.");
      this.maxParcelableMetadataSize = maxParcelableMetadataSize;
      return this;
    }

    public InboundParcelablePolicy build() {
      return new InboundParcelablePolicy(
          acceptParcelableMetadataValues, acceptParcelableMessages, maxParcelableMetadataSize);
    }
  }
}
