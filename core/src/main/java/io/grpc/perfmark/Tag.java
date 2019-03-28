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
 * calls to {@link TagFactory} create methods
 */
public final class Tag {

  private static final long NULL_NUMERIC_TAG = 0;
  private static final String NULL_STRING_TAG = "";

  private static final Tag NULL_TAG = TagFactory.create(NULL_NUMERIC_TAG, NULL_STRING_TAG);

  /**
   * If PerfMark instrumentation is not enabled, returns a Tag with numericTag = 0L. Replacement
   * for {@link TagFactory#create(long, String)} if PerfMark agent is enabled.
   */
  public static Tag create(
      @SuppressWarnings("unused") long numericTag, @SuppressWarnings("unused") String stringTag) {
    // Warning suppression is safe as this method returns by default the NULL_TAG
    return NULL_TAG;
  }

  /**
   * If PerfMark instrumentation is not enabled returns a Tag with numericTag = 0L. Replacement
   * for {@link TagFactory#create(String)} if PerfMark agent is enabled.
   */
  public static Tag create(@SuppressWarnings("unused") String stringTag) {
    // Warning suppression is safe as this method returns by default the NULL_TAG
    return NULL_TAG;
  }

  /**
   * If PerfMark instrumentation is not enabled returns a Tag with numericTag = 0L. Replacement
   * for {@link TagFactory#create(long)} if PerfMark agent is enabled.
   */
  public static Tag create(@SuppressWarnings("unused") long numericTag) {
    // Warning suppression is safe as this method returns by default the NULL_TAG
    return NULL_TAG;
  }

  /**
   * Returns the null tag.
   */
  public static Tag create() {
    return NULL_TAG;
  }

  private final long numericTag;
  private final String stringTag;

  private Tag(long numericTag, String stringTag) {
    this.numericTag = numericTag;
    if (stringTag == null) {
      throw new NullPointerException("stringTag");
    }
    this.stringTag = stringTag;
  }

  /** Returns the numeric tag if set, or {@link Constants#NULL_NUMERIC_TAG} instead. */
  public long getNumericTag() {
    return numericTag;
  }

  /** Returns the string tag if set, or {@link Constants#NULL_STRING_TAG} instead. */
  public String getStringTag() {
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
  public boolean equals(Object obj) {
    if (!(obj instanceof Tag)) {
      return false;
    }
    Tag other = (Tag) obj;
    return this.numericTag == other.numericTag && this.stringTag.equals(other.stringTag);
  }

  /**
   * Provides methods that create Tag instances which should not be directly invoked by clients.
   *
   * <p><b>Warning:</b> Clients should not call methods from this class directly because of the
   * overhead involved in the creation of Tag objects when PerfMark instrumentation is not
   * enabled.
   *
   * <p>Calls to {@link Tag#create(long)}, {@link Tag#create(long, String)} and {@link
   * Tag#create(String)} are replaced with calls to the methods in this class using bytecode
   * rewriting, if enabled.
   */
  static final class TagFactory {
    /**
     * This class should not be instantiated.
     */
    private TagFactory() {}

    public static Tag create(long numericTag, String stringTag) {
      return new Tag(numericTag, stringTag);
    }

    public static Tag create(String stringTag) {
      return new Tag(NULL_NUMERIC_TAG, stringTag);
    }

    public static Tag create(long numericTag) {
      return new Tag(numericTag, NULL_STRING_TAG);
    }
  }
}

