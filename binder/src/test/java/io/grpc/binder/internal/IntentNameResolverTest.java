package io.grpc.binder.internal;

import static android.content.Intent.ACTION_PACKAGE_REPLACED;
import static android.content.Intent.URI_INTENT_SCHEME;
import static android.os.Looper.getMainLooper;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.ApiConstants;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.robolectric.shadows.ShadowPackageManager;

/** A test for IntentNameResolverProvider. */
@RunWith(RobolectricTestRunner.class)
public final class IntentNameResolverTest {

  private static final ComponentName SOME_COMPONENT_NAME =
      new ComponentName("com.foo.bar", "SomeComponent");
  private static final ComponentName ANOTHER_COMPONENT_NAME =
      new ComponentName("org.blah", "AnotherComponent");
  private final Application appContext = ApplicationProvider.getApplicationContext();
  private final SynchronizationContext syncContext = newSynchronizationContext();
  private final NameResolver.Args args = newNameResolverArgs().build();

  private final ShadowPackageManager shadowPackageManager =
      shadowOf(appContext.getPackageManager());

  @Rule public MockitoTestRule mockitoTestRule = MockitoJUnit.testRule(this);
  @Mock public NameResolver.Listener2 mockListener;
  @Captor public ArgumentCaptor<ResolutionResult> resultCaptor;

  @Test
  public void testResolverForIntentScheme_returnsResolverWithLocalHostAuthority() throws Exception {
    URI uri = getIntentUri(newIntent());
    NameResolver resolver = newNameResolver(uri);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("localhost");
  }

  @Test
  public void testResolutionWithoutServicesAvailable_returnsUnimplemented() throws Exception {
    NameResolver nameResolver = newNameResolver(getIntentUri(newIntent()));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddressesOrError().getStatus().getCode())
        .isEqualTo(Status.UNIMPLEMENTED.getCode());
  }

  @Test
  public void testResolutionWithBadUri_returnsIllegalArg() throws Exception {
    NameResolver nameResolver = newNameResolver(new URI("intent:xxx#Intent;e.x=1;end;"));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddressesOrError().getStatus().getCode())
        .isEqualTo(Status.INVALID_ARGUMENT.getCode());
  }

  @Test
  public void testResolutionWithMultipleServicesAvailable_returnsAndroidComponentAddresses()
      throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    // Adds another valid Service
    shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = newNameResolver(getIntentUri(intent));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(
            toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)),
            toAddressList(intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME)));

    syncContext.execute(nameResolver::shutdown);
    shadowOf(getMainLooper()).idle();
  }

  @Test
  public void testResolution_setsPreAuthEagAttribute() throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = newNameResolver(getIntentUri(intent));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();

    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)));
    assertThat(
            getEagsOrThrow(resultCaptor.getValue()).stream()
                .map(EquivalentAddressGroup::getAttributes)
                .collect(toImmutableList())
                .get(0)
                .get(ApiConstants.PRE_AUTH_SERVER_OVERRIDE))
        .isTrue();

    syncContext.execute(nameResolver::shutdown);
    shadowOf(getMainLooper()).idle();
  }

  @Test
  public void testServiceRemoved_pushesUpdatedAndroidComponentAddresses() throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);
    shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = newNameResolver(getIntentUri(intent));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(
            toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)),
            toAddressList(intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME)));

    shadowPackageManager.removeService(ANOTHER_COMPONENT_NAME);
    broadcastPackageChange(ACTION_PACKAGE_REPLACED, ANOTHER_COMPONENT_NAME.getPackageName());
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)));

    syncContext.execute(nameResolver::shutdown);
    shadowOf(getMainLooper()).idle();

    // No Listener callbacks post-shutdown().
    verifyNoMoreInteractions(mockListener);
    // No leaked receivers.
    assertThat(shadowOf(appContext).getRegisteredReceivers()).isEmpty();
  }

  @Test
  @Config(sdk = 29)
  public void testServiceAppearsUponBootComplete_pushesUpdatedAndroidComponentAddresses()
      throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    // Suppose this directBootAware=true Service appears in PackageManager before a user unlock.
    shadowOf(appContext.getSystemService(UserManager.class)).setUserUnlocked(false);
    ServiceInfo someServiceInfo = shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    someServiceInfo.directBootAware = true;
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = newNameResolver(getIntentUri(intent));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)));

    // TODO(b/331618070): Robolectric doesn't yet support ServiceInfo.directBootAware filtering.
    // Simulate support by waiting for a user unlock to add this !directBootAware Service.
    ServiceInfo anotherServiceInfo =
        shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    anotherServiceInfo.directBootAware = false;
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    shadowOf(appContext.getSystemService(UserManager.class)).setUserUnlocked(true);
    broadcastUserUnlocked(android.os.Process.myUserHandle());
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(
            toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)),
            toAddressList(intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME)));

    syncContext.execute(nameResolver::shutdown);
    shadowOf(getMainLooper()).idle();
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void testRefresh_returnsSameAndroidComponentAddresses() throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);
    shadowPackageManager.addServiceIfNotPresent(ANOTHER_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(ANOTHER_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = newNameResolver(getIntentUri(intent));
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(
            toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)),
            toAddressList(intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME)));

    syncContext.execute(nameResolver::refresh);
    shadowOf(getMainLooper()).idle();
    verify(mockListener, never()).onError(any());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(
            toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)),
            toAddressList(intent.cloneFilter().setComponent(ANOTHER_COMPONENT_NAME)));

    syncContext.execute(nameResolver::shutdown);
    shadowOf(getMainLooper()).idle();
    assertThat(shadowOf(appContext).getRegisteredReceivers()).isEmpty();
  }

  @Test
  public void testRefresh_collapsesMultipleRequestsIntoOneLookup() throws Exception {
    Intent intent = newIntent();
    IntentFilter serviceIntentFilter = newFilterMatching(intent);

    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, serviceIntentFilter);

    NameResolver nameResolver = newNameResolver(getIntentUri(intent));
    syncContext.execute(() -> nameResolver.start(mockListener)); // Should kick off the 1st lookup.
    syncContext.execute(nameResolver::refresh); // Should queue a lookup to run when 1st finishes.
    syncContext.execute(nameResolver::refresh); // Should be ignored since a lookup is already Q'd.
    syncContext.execute(nameResolver::refresh); // Also ignored.
    shadowOf(getMainLooper()).idle();

    verify(mockListener, never()).onError(any());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertThat(getAddressesOrThrow(resultCaptor.getValue()))
        .containsExactly(toAddressList(intent.cloneFilter().setComponent(SOME_COMPONENT_NAME)));

    syncContext.execute(nameResolver::shutdown);
    shadowOf(getMainLooper()).idle();
  }

  private void broadcastPackageChange(String action, String pkgName) {
    Intent broadcast = new Intent();
    broadcast.setAction(action);
    broadcast.setData(Uri.parse("package:" + pkgName));
    appContext.sendBroadcast(broadcast);
  }

  private void broadcastUserUnlocked(UserHandle userHandle) {
    Intent unlockedBroadcast = new Intent(Intent.ACTION_USER_UNLOCKED);
    unlockedBroadcast.putExtra(Intent.EXTRA_USER, userHandle);
    appContext.sendBroadcast(unlockedBroadcast);
  }

  @Test
  public void testResolutionOnResultThrows_onErrorNotCalled() throws Exception {
    RetainingUncaughtExceptionHandler exceptionHandler = new RetainingUncaughtExceptionHandler();
    SynchronizationContext syncContext = new SynchronizationContext(exceptionHandler);
    Intent intent = newIntent();
    shadowPackageManager.addServiceIfNotPresent(SOME_COMPONENT_NAME);
    shadowPackageManager.addIntentFilterForService(SOME_COMPONENT_NAME, newFilterMatching(intent));

    @SuppressWarnings("serial")
    class SomeRuntimeException extends RuntimeException {}
    doThrow(SomeRuntimeException.class).when(mockListener).onResult2(any());

    NameResolver nameResolver =
        newNameResolver(
            getIntentUri(intent),
            newNameResolverArgs().setSynchronizationContext(syncContext).build());
    syncContext.execute(() -> nameResolver.start(mockListener));
    shadowOf(getMainLooper()).idle();

    verify(mockListener).onResult2(any());
    verify(mockListener, never()).onError(any());
    assertThat(exceptionHandler.uncaught).hasSize(1);
    assertThat(exceptionHandler.uncaught.get(0)).isInstanceOf(SomeRuntimeException.class);
  }

  private static Intent newIntent() {
    Intent intent = new Intent();
    intent.setAction("test.action");
    intent.setData(Uri.parse("grpc:ServiceName"));
    return intent;
  }

  private static IntentFilter newFilterMatching(Intent intent) {
    IntentFilter filter = new IntentFilter();
    if (intent.getAction() != null) {
      filter.addAction(intent.getAction());
    }
    Uri data = intent.getData();
    if (data != null) {
      if (data.getScheme() != null) {
        filter.addDataScheme(data.getScheme());
      }
      if (data.getSchemeSpecificPart() != null) {
        filter.addDataSchemeSpecificPart(data.getSchemeSpecificPart(), 0);
      }
    }
    Set<String> categories = intent.getCategories();
    if (categories != null) {
      for (String category : categories) {
        filter.addCategory(category);
      }
    }
    return filter;
  }

  private static URI getIntentUri(Intent intent) throws Exception {
    return new URI(intent.toUri(URI_INTENT_SCHEME));
  }

  private static List<EquivalentAddressGroup> getEagsOrThrow(ResolutionResult result) {
    StatusOr<List<EquivalentAddressGroup>> eags = result.getAddressesOrError();
    if (!eags.hasValue()) {
      throw eags.getStatus().asRuntimeException();
    }
    return eags.getValue();
  }

  // Extracts just the addresses from 'result's EquivalentAddressGroups.
  private static ImmutableList<List<SocketAddress>> getAddressesOrThrow(ResolutionResult result) {
    return getEagsOrThrow(result).stream()
        .map(EquivalentAddressGroup::getAddresses)
        .collect(toImmutableList());
  }

  // Converts given Intents to a list of ACAs, for convenient comparison with getAddressesOrThrow().
  private static ImmutableList<AndroidComponentAddress> toAddressList(Intent... bindIntents) {
    ImmutableList.Builder<AndroidComponentAddress> builder = ImmutableList.builder();
    for (Intent bindIntent : bindIntents) {
      builder.add(AndroidComponentAddress.forBindIntent(bindIntent));
    }
    return builder.build();
  }

  private NameResolver newNameResolver(URI targetUri) {
    return newNameResolver(targetUri, args);
  }

  private NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    return new IntentNameResolver(appContext, targetUri, args);
  }

  /** Returns a new test-specific {@link NameResolver.Args} instance. */
  private NameResolver.Args.Builder newNameResolverArgs() {
    return NameResolver.Args.newBuilder()
        .setDefaultPort(-1)
        .setProxyDetector((target) -> null) // No proxies here.
        .setSynchronizationContext(syncContext)
        .setOffloadExecutor(ContextCompat.getMainExecutor(appContext))
        .setServiceConfigParser(mock(ServiceConfigParser.class));
  }

  /**
   * Returns a test {@link SynchronizationContext}.
   *
   * <p>Exceptions will cause the test to fail with {@link AssertionError}.
   */
  private static SynchronizationContext newSynchronizationContext() {
    return new SynchronizationContext(
        (thread, exception) -> {
          throw new AssertionError(exception);
        });
  }

  static final class RetainingUncaughtExceptionHandler implements UncaughtExceptionHandler {
    final ArrayList<Throwable> uncaught = new ArrayList<>();

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
      uncaught.add(e);
    }
  }
}
