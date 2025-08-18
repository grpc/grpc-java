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

import static android.os.Looper.getMainLooper;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverProvider;
import io.grpc.SynchronizationContext;
import io.grpc.binder.ApiConstants;
import java.net.URI;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoTestRule;
import org.robolectric.RobolectricTestRunner;

/** A test for IntentNameResolverProvider. */
@RunWith(RobolectricTestRunner.class)
public final class IntentNameResolverProviderTest {

  private final Application appContext = ApplicationProvider.getApplicationContext();
  private final SynchronizationContext syncContext = newSynchronizationContext();
  private final NameResolver.Args args = newNameResolverArgs();

  private NameResolverProvider provider;

  @Rule public MockitoTestRule mockitoTestRule = MockitoJUnit.testRule(this);
  @Mock public NameResolver.Listener2 mockListener;
  @Captor public ArgumentCaptor<ResolutionResult> resultCaptor;

  @Before
  public void setUp() {
    provider = new IntentNameResolverProvider();
  }

  @Test
  public void testProviderScheme_returnsIntentScheme() throws Exception {
    assertThat(provider.getDefaultScheme())
        .isEqualTo(IntentNameResolverProvider.ANDROID_INTENT_SCHEME);
  }

  @Test
  public void testNoResolverForUnknownScheme_returnsNull() throws Exception {
    assertThat(provider.newNameResolver(new URI("random://uri"), args)).isNull();
  }

  @Test
  public void testResolutionWithBadUri_throwsIllegalArg() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.newNameResolver(new URI("intent:xxx#Intent;e.x=1;end;"), args));
  }

  @Test
  public void testResolverForIntentScheme_returnsResolver() throws Exception {
    URI uri = new URI("intent://authority/path#Intent;action=action;scheme=scheme;end");
    NameResolver resolver = provider.newNameResolver(uri, args);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("localhost");
    syncContext.execute(() -> resolver.start(mockListener));
    shadowOf(getMainLooper()).idle();
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddressesOrError()).isNotNull();
    syncContext.execute(resolver::shutdown);
    shadowOf(getMainLooper()).idle();
  }

  /** Returns a new test-specific {@link NameResolver.Args} instance. */
  private NameResolver.Args newNameResolverArgs() {
    return NameResolver.Args.newBuilder()
        .setDefaultPort(-1)
        .setProxyDetector((target) -> null) // No proxies here.
        .setSynchronizationContext(syncContext)
        .setOffloadExecutor(ContextCompat.getMainExecutor(appContext))
        .setServiceConfigParser(mock(ServiceConfigParser.class))
        .setArg(ApiConstants.SOURCE_ANDROID_CONTEXT, appContext)
        .build();
  }

  private static SynchronizationContext newSynchronizationContext() {
    return new SynchronizationContext(
        (thread, exception) -> {
          throw new AssertionError(exception);
        });
  }
}
