/*
 * Copyright 2025 The gRPC Authors
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
package io.grpc.binder.internal;

import static android.content.Intent.URI_INTENT_SCHEME;

import android.content.Intent;
import com.google.common.collect.ImmutableSet;
import io.grpc.NameResolver;
import io.grpc.Uri;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.binder.AndroidComponentAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link NameResolverProvider} that handles Android-standard "intent:" target URIs, resolving
 * them to the list of {@link AndroidComponentAddress} that match by manifest intent filter.
 */
public final class IntentNameResolverProvider extends NameResolverProvider {

  static final String ANDROID_INTENT_SCHEME = "intent";

  @Override
  public String getDefaultScheme() {
    return ANDROID_INTENT_SCHEME;
  }

  @Nullable
  @Override
  public NameResolver newNameResolver(URI targetUri, final Args args) {
    if (Objects.equals(targetUri.getScheme(), ANDROID_INTENT_SCHEME)) {
      return new IntentNameResolver(parseUriArg(targetUri.toString()), args);
    } else {
      return null;
    }
  }

  @Nullable
  @Override
  public NameResolver newNameResolver(Uri targetUri, final Args args) {
    if (Objects.equals(targetUri.getScheme(), ANDROID_INTENT_SCHEME)) {
      return new IntentNameResolver(parseUriArg(targetUri.toString()), args);
    } else {
      return null;
    }
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int priority() {
    return 3; // Lower than DNS so we don't accidentally become the default scheme for a registry.
  }

  @Override
  public ImmutableSet<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return ImmutableSet.of(AndroidComponentAddress.class);
  }

  private static Intent parseUriArg(String targetUri) {
    try {
      return Intent.parseUri(targetUri, URI_INTENT_SCHEME);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
