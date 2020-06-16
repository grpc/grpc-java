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
 * A map for managing {@link SslContextProvider}s as reference-counted shared resources.
 *
 * <p>A key (of generic type K) identifies a {@link SslContextProvider}. The map also depends on a
 * factory {@link SslContextProviderFactory} to create a new instance of {@link SslContextProvider}
 * as needed. {@link SslContextProvider}s are ref-counted and closed by calling {@link
 * SslContextProvider#close()} when ref-count reaches zero.
 *
 * @param <K> Key type for the map
 */
@ThreadSafe
final class ReferenceCountingSslContextProviderMap<K> {

  private final Map<K, Instance> instances = new HashMap<>();
  private final SslContextProviderFactory<K> sslContextProviderFactory;

  ReferenceCountingSslContextProviderMap(SslContextProviderFactory<K> sslContextProviderFactory) {
    checkNotNull(sslContextProviderFactory, "sslContextProviderFactory");
    this.sslContextProviderFactory = sslContextProviderFactory;
  }

  /**
   * Gets an existing instance of {@link SslContextProvider}. If it doesn't exist, creates a new one
   * using the provided {@link SslContextProviderFactory&lt;K&gt;}
   */
  @CheckReturnValue
  public SslContextProvider get(K key) {
    checkNotNull(key, "key");
    return getInternal(key);
  }

  /**
   * Releases an instance of the given {@link SslContextProvider}.
   *
   * <p>The instance must have been obtained from {@link #get(K)}. Otherwise will throw
   * IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   *
   * @param key for the instance to be released
   * @param value the instance to be released
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public SslContextProvider release(K key, SslContextProvider value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    return releaseInternal(key, value);
  }

  private synchronized SslContextProvider getInternal(K key) {
    Instance instance = instances.get(key);
    if (instance == null) {
      instance = new Instance(sslContextProviderFactory.createSslContextProvider(key));
      instances.put(key, instance);
      return instance.sslContextProvider;
    } else {
      return instance.acquire();
    }
  }

  private synchronized SslContextProvider releaseInternal(K key, SslContextProvider instance) {
    Instance cached = instances.get(key);
    checkArgument(cached != null, "No cached instance found for %s", key);
    checkArgument(instance == cached.sslContextProvider, "Releasing the wrong instance");
    if (cached.release()) {
      try {
        cached.sslContextProvider.close();
      } finally {
        instances.remove(key);
      }
    }
    // Always return null
    return null;
  }

  /** A factory to create an SslContextProvider from the given key. */
  public interface SslContextProviderFactory<K> {
    SslContextProvider createSslContextProvider(K key);
  }

  private static class Instance {
    final SslContextProvider sslContextProvider;
    private int refCount;

    /** Increment refCount and acquire a reference to sslContextProvider. */
    SslContextProvider acquire() {
      refCount++;
      return sslContextProvider;
    }

    /** Decrement refCount and return true if it has reached 0. */
    boolean release() {
      checkState(refCount > 0, "refCount has to be > 0");
      return --refCount == 0;
    }

    Instance(SslContextProvider sslContextProvider) {
      this.sslContextProvider = sslContextProvider;
      this.refCount = 1;
    }
  }
}
