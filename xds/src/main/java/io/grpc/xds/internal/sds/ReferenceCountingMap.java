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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A map for managing reference-counted shared resources - typically providers.
 *
 * <p>A key (of generic type K) identifies a provider (of generic type V). The map also depends on a
 * factory {@link ValueFactory} to create a new instance of V as needed. Values are ref-counted and
 * closed by calling {@link Closeable#close()} when ref-count reaches zero.
 *
 * @param <K> Key type for the map
 * @param <V> Value type for the map - it should be a {@link Closeable}
 */
@ThreadSafe
public final class ReferenceCountingMap<K, V extends Closeable> {

  private final Map<K, Instance<V>> instances = new HashMap<>();
  private final ValueFactory<K, V> valueFactory;

  public ReferenceCountingMap(ValueFactory<K, V> valueFactory) {
    checkNotNull(valueFactory, "valueFactory");
    this.valueFactory = valueFactory;
  }

  /**
   * Gets an existing instance of a provider. If it doesn't exist, creates a new one
   * using the provided {@link ValueFactory &lt;K, V&gt;}
   */
  @CheckReturnValue
  public V get(K key) {
    checkNotNull(key, "key");
    return getInternal(key);
  }

  /**
   * Releases an instance of the given value.
   *
   * <p>The instance must have been obtained from {@link #get(Object)}. Otherwise will throw
   * IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   *
   * @param key for the instance to be released
   * @param value the instance to be released
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public V release(K key, V value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    return releaseInternal(key, value);
  }

  private synchronized V getInternal(K key) {
    Instance<V> instance = instances.get(key);
    if (instance == null) {
      instance = new Instance<>(valueFactory.create(key));
      instances.put(key, instance);
      return instance.value;
    } else {
      return instance.acquire();
    }
  }

  private synchronized V releaseInternal(K key, V value) {
    Instance<V> cached = instances.get(key);
    checkArgument(cached != null, "No cached instance found for %s", key);
    checkArgument(value == cached.value, "Releasing the wrong instance");
    if (cached.release()) {
      try {
        cached.value.close();
      } finally {
        instances.remove(key);
      }
    }
    // Always return null
    return null;
  }

  /** A factory to create a value from the given key. */
  public interface ValueFactory<K, V extends Closeable> {
    V create(K key);
  }

  private static final class Instance<V extends Closeable> {
    final V value;
    private int refCount;

    /** Increment refCount and acquire a reference to value. */
    V acquire() {
      refCount++;
      return value;
    }

    /** Decrement refCount and return true if it has reached 0. */
    boolean release() {
      checkState(refCount > 0, "refCount has to be > 0");
      return --refCount == 0;
    }

    Instance(V value) {
      this.value = value;
      this.refCount = 1;
    }
  }
}
