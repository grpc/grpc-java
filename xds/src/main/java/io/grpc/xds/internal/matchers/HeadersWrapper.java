/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.internal.matchers;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.xds.internal.MetadataHelper;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

public final class HeadersWrapper extends AbstractMap<String, String> {
  private static final ImmutableSet<String> PSEUDO_HEADERS =
      ImmutableSet.of(":method", ":authority", ":path");
  private final HttpMatchInput httpMatchInput;

  HeadersWrapper(HttpMatchInput httpMatchInput) {
    this.httpMatchInput = httpMatchInput;
  }

  @Override
  @Nullable
  public String get(Object key) {
    String headerName = (String) key;
    // Pseudo-headers.
    switch (headerName) {
      case ":method":
        return httpMatchInput.getMethod();
      case ":authority":
        return httpMatchInput.getHost();
      case ":path":
        return httpMatchInput.getPath();
      default:
        return MetadataHelper.deserializeHeader(httpMatchInput.metadata(), headerName);
    }
  }

  @Override
  public String getOrDefault(Object key, String defaultValue) {
    String value = get(key);
    return value != null ? value : defaultValue;
  }

  @Override
  public boolean containsKey(Object key) {
    String headerName = (String) key;
    if (PSEUDO_HEADERS.contains(headerName)) {
      return true;
    }
    return MetadataHelper.containsHeader(httpMatchInput.metadata(), headerName);
  }

  @Override
  public Set<String> keySet() {
    return ImmutableSet.<String>builder()
        .addAll(httpMatchInput.metadata().keys())
        .addAll(PSEUDO_HEADERS).build();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public Set<Entry<String, String>> entrySet() {
    throw new UnsupportedOperationException(
        "Should not be called to prevent resolving header values.");
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public Collection<String> values() {
    throw new UnsupportedOperationException(
        "Should not be called to prevent resolving header values.");
  }

  @Override
  public String toString() {
    // Prevent iterating to avoid resolving all values on "key not found".
    return getClass().getName() + "@" + Integer.toHexString(hashCode());
  }

  @Override public int hashCode() {
    return Objects.hashCode(httpMatchInput.serverCall(), httpMatchInput.metadata());
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public void replaceAll(BiFunction<? super String, ? super String, ? extends String> function) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public String putIfAbsent(String key, String value) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public boolean remove(Object key, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public boolean replace(String key, String oldValue, String newValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public String replace(String key, String value) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public String computeIfAbsent(
      String key, Function<? super String, ? extends String> mappingFunction) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public String computeIfPresent(
      String key, BiFunction<? super String, ? super String, ? extends String> remappingFunction) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public String compute(
      String key, BiFunction<? super String, ? super String, ? extends String> remappingFunction) {
    throw new UnsupportedOperationException();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public String merge(
      String key, String value,
      BiFunction<? super String, ? super String, ? extends String> remappingFunction) {
    throw new UnsupportedOperationException();
  }
}
