/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.perfmark;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A Tag is used to provide additional information to identify a task and consists of a 64-bit
 * integer value and a string.
 *
 * <p>Both the {@code numericTag} and the {@code stringTag} are optional. The {@code numericTag}
 * value can be used to identify the specific task being worked on (e.g. the id of the rpc call).
 * The {@code stringTag} can be used to store any value that is not a compile-time constant (a
 * restriction imposed for the name passed to PerfMark tasks and events). A value of 0 for the
 * {@code numericTag} is considered null. Don't use 0 for the {@code numericTag} unless you intend
 * to specify null. In that case you are encouraged to use {@link #create(String)}.
 *
 * <p>Invocations to {@code create} methods in this class are a no-op unless PerfMark
 * instrumentation is enabled. If so, calls to {@code create} methods to this class are replaced for
 * calls to {@link TagFactory} create methods.
 *
 * <p>This class is {@link io.grpc.Internal} and {@link io.grpc.ExperimentalApi}.  Do not use this
 * yet.
 */
@Immutable
public final class PerfTag {

  private static final long NULL_NUMERIC_TAG = 0;
  private static final String NULL_STRING_TAG = null;

  private final long numericTag;
  private final String stringTag;

  private PerfTag(long numericTag, @Nullable String stringTag) {
    this.numericTag = numericTag;
    this.stringTag = stringTag;
  }

  /** Returns the numeric tag if set, or {@link #NULL_NUMERIC_TAG} instead. */
  public long getNumericTag() {
    return numericTag;
  }

  /** Returns the string tag if set, or {@link #NULL_STRING_TAG} instead. */
  @Nullable public String getStringTag() {
    return stringTag;
  }

  @Override
  public String toString() {
    return "Tag(numericTag=" + numericTag + ",stringTag='" + stringTag + "')";
  }

  @Override
  public int hashCode() {
    int longHashCode = (int)(numericTag ^ (numericTag >>> 32));
    return longHashCode + (stringTag != null ? stringTag.hashCode() : 31);
  }

  @Override
  @SuppressWarnings("ReferenceEquality") // No Java 8 yet.
  public boolean equals(Object obj) {
    if (!(obj instanceof PerfTag)) {
      return false;
    }
    PerfTag that = (PerfTag) obj;
    return numericTag == that.numericTag
        && (stringTag == that.stringTag || (stringTag != null && stringTag.equals(that.stringTag)));
  }

  /**
   * Provides methods that create Tag instances which should not be directly invoked by clients.
   *
   * <p>Calls to {@link PerfMark#create(long)}, {@link PerfMark#create(long, String)} and {@link
   * PerfMark#create(String)} are replaced with calls to the methods in this class using bytecode
   * rewriting, if enabled.
   */
  static final class TagFactory {
    /**
     * This class should not be instantiated.
     */
    private TagFactory() {
      throw new AssertionError("nope");
    }

    public static PerfTag create(long numericTag, String stringTag) {
      return new PerfTag(numericTag, stringTag);
    }

    public static PerfTag create(String stringTag) {
      return new PerfTag(NULL_NUMERIC_TAG, stringTag);
    }

    public static PerfTag create(long numericTag) {
      return new PerfTag(numericTag, NULL_STRING_TAG);
    }

    static PerfTag create() {
      return new PerfTag(NULL_NUMERIC_TAG, NULL_STRING_TAG);
    }
  }
}

