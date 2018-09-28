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

import com.google.common.base.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * An immutable type-safe container of attributes.
 * @since 1.13.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1764")
@Immutable
public class AttributeMap<AT> {

  // TODO(zhangkun83): to be accessible from Attributes only.
  // Make this private after Attributes is deleted.
  final Map<Key<AT, ?>, Object> data;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static final AttributeMap EMPTY =
      new AttributeMap(Collections.<Key<?, ?>, Object>emptyMap());

  @SuppressWarnings("unchecked")
  public static <AT> AttributeMap<AT> getEmptyInstance() {
    return EMPTY;
  }

  private AttributeMap(Map<Key<AT, ?>, Object> data) {
    assert data != null;
    this.data = Collections.unmodifiableMap(data);
  }

  /**
   * Gets the value for the key, or {@code null} if it's not present.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public final <T> T get(Key<AT, T> key) {
    return (T) data.get(key);
  }

  Set<Key<AT, ?>> keysForTest() {
    return data.keySet();
  }

  /**
   * Create a new builder.
   */
  @SuppressWarnings("unchecked")
  public static <AT> Builder<AT> newBuilder() {
    return new Builder<AT>((AttributeMap<AT>) EMPTY);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <AT> AttributeMap<AT> fromAttributes(Attributes attrs) {
    return new AttributeMap(attrs.data);
  }

  /**
   * Creates a new builder that is pre-populated with the content of this container.
   * @return a new builder.
   */
  public Builder<AT> toBuilder() {
    return new Builder<AT>(this);
  }

  /**
   * Key for an key-value pair.
   * @param <T> type of the value in the key-value pair
   */
  @Immutable
  public static class Key<AT, T> {
    private final String debugString;

    protected Key(String debugString) {
      this.debugString = debugString;
    }

    @Override
    public String toString() {
      return debugString;
    }

    /**
     * Factory method for creating instances of {@link Key}.
     *
     * @param debugString a string used to describe the key, used for debugging.
     * @param <T> Key type
     * @return Key object
     */
    public static <AT, T> Key<AT, T> define(String debugString) {
      return new Key<AT, T>(debugString);
    }
  }

  @Override
  public String toString() {
    return data.toString();
  }

  /**
   * Returns true if the given object is also a {@link AttributeMap} with an equal attribute values.
   *
   * <p>Note that if a stored values are mutable, it is possible for two objects to be considered
   * equal at one point in time and not equal at another (due to concurrent mutation of attribute
   * values).
   *
   * <p>This method is not implemented efficiently and is meant for testing.
   *
   * @param o an object.
   * @return true if the given object is a {@link AttributeMap} equal attributes.
   */
  @Override
  @SuppressWarnings("rawtypes")
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AttributeMap that = (AttributeMap) o;
    if (data.size() != that.data.size()) {
      return false;
    }
    for (Entry<Key<AT, ?>, Object> e : data.entrySet()) {
      if (!that.data.containsKey(e.getKey())) {
        return false;
      }
      if (!Objects.equal(e.getValue(), that.data.get(e.getKey()))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a hash code for the attributes.
   *
   * <p>Note that if a stored values are mutable, it is possible for two objects to be considered
   * equal at one point in time and not equal at another (due to concurrent mutation of attribute
   * values).
   *
   * @return a hash code for the attributes map.
   */
  @Override
  public int hashCode() {
    int hashCode = 0;
    for (Entry<Key<AT, ?>, Object> e : data.entrySet()) {
      hashCode += Objects.hashCode(e.getKey(), e.getValue());
    }
    return hashCode;
  }

  /**
   * The helper class to build an AttributeMap instance.
   */
  public static final class Builder<AT> {
    private AttributeMap<AT> base;
    private Map<Key<AT, ?>, Object> newdata;

    private Builder(AttributeMap<AT> base) {
      assert base != null;
      this.base = base;
    }

    private Map<Key<AT, ?>, Object> data(int size) {
      if (newdata == null) {
        newdata = new IdentityHashMap<Key<AT, ?>, Object>(size);
      }
      return newdata;
    }

    public <T> Builder<AT> set(Key<AT, T> key, T value) {
      data(1).put(key, value);
      return this;
    }

    public <T> Builder<AT> setAll(AttributeMap<AT> other) {
      data(other.data.size()).putAll(other.data);
      return this;
    }

    /**
     * Build the attributes.
     */
    public AttributeMap<AT> build() {
      if (newdata != null) {
        for (Entry<Key<AT, ?>, Object> entry : base.data.entrySet()) {
          if (!newdata.containsKey(entry.getKey())) {
            newdata.put(entry.getKey(), entry.getValue());
          }
        }
        base = new AttributeMap<AT>(newdata);
        newdata = null;
      }
      return base;
    }
  }
}
