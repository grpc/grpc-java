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
import android.content.Intent;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.SynchronizationContext;
import io.grpc.binder.ApiConstants;
import java.net.URI;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoTestRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A test for AndroidAppNameResolverProvider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public final class AndroidAppNameResolverProviderTest {

  private final Application appContext = ApplicationProvider.getApplicationContext();
  private final SynchronizationContext syncContext = newSynchronizationContext();
  private final NameResolver.Args args = newNameResolverArgs();
  private final AndroidAppNameResolverProvider provider = new AndroidAppNameResolverProvider();

  @Rule public MockitoTestRule mockitoTestRule = MockitoJUnit.testRule(this);
  @Mock public NameResolver.Listener2 mockListener;
  @Captor public ArgumentCaptor<ResolutionResult> resultCaptor;

  @Test
  public void testProviderScheme_returnsIntentScheme() throws Exception {
    assertThat(provider.getDefaultScheme())
        .isEqualTo(AndroidAppNameResolverProvider.ANDROID_APP_SCHEME);
  }

  @Test
  public void testNoResolverForUnknownScheme_returnsNull() throws Exception {
    assertThat(provider.newNameResolver(new URI("random://uri"), args)).isNull();
  }

  @Test
  public void testResolutionWithBadUri_throwsIllegalArg() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.newNameResolver(new URI("android-app://xxx/yy#Intent;e.x=1;end;"), args));
  }

  @Test
  public void testIsAvailableSdk22() throws Exception {
    assertThat(provider.isAvailable()).isTrue();
  }

  @Test
  public void testResolverForIntentScheme_returnsResolver() throws Exception {
    URI uri = new URI("android-app:///scheme/authority/path#Intent;action=action;end");
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

  @Test
  public void testResolverForIntentScheme_supportsEmptySchemeSpecificPart() throws Exception {
    Intent intent =
        AndroidAppNameResolverProvider.parseUriArg(
            "android-app://com.example.app#Intent;action=action1;end;");
    assertThat(new Intent.FilterComparison(intent))
        .isEqualTo(
            new Intent.FilterComparison(
                new Intent().setAction("action1").setPackage("com.example.app")));
  }

  @Test
  public void testResolverForIntentScheme_supportsEmptyPackage() throws Exception {
    Intent intent =
        AndroidAppNameResolverProvider.parseUriArg("android-app:///#Intent;action=action1;end;");
    assertThat(new Intent.FilterComparison(intent))
        .isEqualTo(new Intent.FilterComparison(new Intent().setAction("action1")));
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
