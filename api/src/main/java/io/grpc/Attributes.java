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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * An immutable type-safe container of attributes.
 *
 * <h3>Annotation semantics</h3>
 *
 * <p>As a convention, annotations such as {@link Grpc.TransportAttr} is defined to associate
 * attribute {@link Key}s and their propagation paths.  The annotation may be applied to a {@code
 * Key} definition field, a method that returns {@link Attributes}, or a variable of type {@link
 * Attributes}, to indicate that the annotated {@link Attributes} objects may contain the annotated
 * {@code Key}.
 *
 * <p>Javadoc users may click "USE" on the navigation bars of the annotation's javadoc page to view
 * references of such annotation.
 *
 * @since 1.13.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1764")
@Immutable
public final class Attributes {

  private final IdentityHashMap<Key<?>, Object> data;

  private static final IdentityHashMap<Key<?>, Object> EMPTY_MAP =
      new IdentityHashMap<Key<?>, Object>();
  public static final Attributes EMPTY = new Attributes(EMPTY_MAP);

  private Attributes(IdentityHashMap<Key<?>, Object> data) {
    assert data != null;
    this.data = data;
  }

  /**
   * Gets the value for the key, or {@code null} if it's not present.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public <T> T get(Key<T> key) {
    return (T) data.get(key);
  }

  /**
   * Returns set of keys stored in container.
   *
   * @return Set of Key objects.
   * @deprecated This method is being considered for removal, if you feel this method is needed
   *     please reach out on this Github issue:
   *     <a href="https://github.com/grpc/grpc-java/issues/1764">grpc-java/issues/1764</a>.
   */
  @Deprecated
  public Set<Key<?>> keys() {
    return Collections.unmodifiableSet(data.keySet());
  }

  Set<Key<?>> keysForTest() {
    return Collections.unmodifiableSet(data.keySet());
  }

  /**
   * Create a new builder that is pre-populated with the content from a given container.
   * @deprecated Use {@link Attributes#toBuilder()} on the {@link Attributes} instance instead.
   *     This method will be removed in the future.
   */
  @Deprecated
  public static Builder newBuilder(Attributes base) {
    checkNotNull(base, "base");
    return new Builder(base);
  }

  /**
   * Create a new builder.
   */
  public static Builder newBuilder() {
    return new Builder(EMPTY);
  }

  /**
   * Creates a new builder that is pre-populated with the content of this container.
   * @return a new builder.
   */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * Key for an key-value pair. Uses reference equality.
   *
   * @param <T> type of the value in the key-value pair
   */
  @Immutable
  @SuppressWarnings("UnusedTypeParameter")
  public static final class Key<T> {
    private final String debugString;

    private Key(String debugString) {
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
     * @deprecated use {@link #create} instead. This method will be removed in the future.
     */
    @Deprecated
    public static <T> Key<T> of(String debugString) {
      return new Key<>(debugString);
    }

    /**
     * Factory method for creating instances of {@link Key}.
     *
     * @param debugString a string used to describe the key, used for debugging.
     * @param <T> Key type
     * @return Key object
     */
    public static <T> Key<T> create(String debugString) {
      return new Key<>(debugString);
    }
  }

  @Override
  public String toString() {
    return data.toString();
  }

  /**
   * Returns true if the given object is also a {@link Attributes} with an equal attribute values.
   *
   * <p>Note that if a stored values are mutable, it is possible for two objects to be considered
   * equal at one point in time and not equal at another (due to concurrent mutation of attribute
   * values).
   *
   * <p>This method is not implemented efficiently and is meant for testing.
   *
   * @param o an object.
   * @return true if the given object is a {@link Attributes} equal attributes.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Attributes that = (Attributes) o;
    if (data.size() != that.data.size()) {
      return false;
    }
    for (Map.Entry<Key<?>, Object> e : data.entrySet()) {
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
    for (Map.Entry<Key<?>, Object> e : data.entrySet()) {
      hashCode += Objects.hashCode(e.getKey(), e.getValue());
    }
    return hashCode;
  }

  /**
   * The helper class to build an Attributes instance.
   */
  public static final class Builder {
    // Exactly one of base and newdata will be set
    private Attributes base;
    private IdentityHashMap<Key<?>, Object> newdata;

    private Builder(Attributes base) {
      assert base != null;
      this.base = base;
    }

    private IdentityHashMap<Key<?>, Object> data(int size) {
      if (newdata == null) {
        newdata = new IdentityHashMap<>(base.data.size() + size);
        newdata.putAll(base.data);
        base = null;
      }
      assert base == null;
      return newdata;
    }

    public <T> Builder set(Key<T> key, T value) {
      data(1).put(key, value);
      return this;
    }

    /**
     * Removes the key and associated value from the attributes.
     *
     * @since 1.22.0
     * @param key The key to remove
     * @return this
     */
    public <T> Builder discard(Key<T> key) {
      if (base != null) {
        if (base.data.containsKey(key)) {
          data(0).remove(key);
        }
      } else {
        newdata.remove(key);
      }
      return this;
    }

    public Builder setAll(Attributes other) {
      data(other.data.size()).putAll(other.data);
      return this;
    }

    /**
     * Build the attributes.
     */
    public Attributes build() {
      if (newdata != null) {
        base = new Attributes(newdata);
        newdata = null;
      }
      return base;
    }
  }
}
