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

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import java.util.HashMap;
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

  private final HashMap<K, Instance> instances;
  private final SslContextProviderFactory<K> sslContextProviderFactory;

  ReferenceCountingSslContextProviderMap(SslContextProviderFactory<K> sslContextProviderFactory) {
    checkNotNull(sslContextProviderFactory, "sslContextProviderFactory");
    instances = new HashMap<>();
    this.sslContextProviderFactory = sslContextProviderFactory;
  }

  /**
   * Gets an existing instance of {@link SslContextProvider}. If it doesn't exist, creates a new one
   * using the provided {@link SslContextProviderFactory&lt;K&gt;}
   */
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
   * @param key the key that identifies the shared resource
   * @param value the instance to be released
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public SslContextProvider release(final K key, final SslContextProvider value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    return releaseInternal(key, value);
  }

  private synchronized SslContextProvider getInternal(K key) {
    Instance instance = instances.get(key);
    if (instance == null) {
      instance = new Instance(sslContextProviderFactory.createSslContextProvider(key));
      instances.put(key, instance);
    }
    instance.refcount++;
    return instance.sslContextProvider;
  }

  private synchronized SslContextProvider releaseInternal(
      final K key, final SslContextProvider instance) {
    final Instance cached = instances.get(key);
    if (cached == null) {
      throw new IllegalArgumentException("No cached instance found for " + key);
    }
    Preconditions.checkArgument(
        instance == cached.sslContextProvider, "Releasing the wrong instance");
    cached.refcount--;
    if (cached.refcount == 0) {
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
    int refcount;

    Instance(SslContextProvider sslContextProvider) {
      this.sslContextProvider = sslContextProvider;
      this.refcount = 0;
    }
  }
}
