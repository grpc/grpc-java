/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.certprovider;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyListOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.Status;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link CertificateProviderStore}. */
@RunWith(JUnit4.class)
public class CertificateProviderStoreTest {

  private CertificateProviderRegistry certificateProviderRegistry;
  private CertificateProviderStore certificateProviderStore;
  private boolean throwExceptionForCertUpdates;

  @Before
  public void setUp() {
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    throwExceptionForCertUpdates = false;
  }

  @Test
  public void pluginNotRegistered_expectException() {
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    try {
      CertificateProviderStore.Handle unused = certificateProviderStore.createOrGetProvider(
              "cert-name1", "plugin1", "config", mockWatcher, true);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Provider not found for plugin1");
    }
  }

  @Test
  public void pluginUnregistered_expectException() {
    CertificateProviderProvider certificateProviderProvider = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle = certificateProviderStore.createOrGetProvider(
            "cert-name1", "plugin1", "config", mockWatcher, true);
    handle.close();
    certificateProviderRegistry.deregister(certificateProviderProvider);
    try {
      handle = certificateProviderStore.createOrGetProvider(
              "cert-name1", "plugin1", "config", mockWatcher, true);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Provider not found for plugin1");
    }
  }

  @Test
  public void notifyCertUpdatesNotSupported_expectException() {
    CertificateProviderProvider unused = registerPlugin("plugin1");
    throwExceptionForCertUpdates = true;
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    try {
      CertificateProviderStore.Handle unused1 =
            certificateProviderStore.createOrGetProvider(
                    "cert-name1", "plugin1", "config", mockWatcher, true);
      fail("exception expected");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Provider does not support Certificate Updates.");
    }
  }

  @Test
  public void notifyCertUpdatesNotSupported_expectExceptionOnSecondCall() {
    registerPlugin("plugin1");
    throwExceptionForCertUpdates = true;
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    try (CertificateProviderStore.Handle unused =
        certificateProviderStore
            .createOrGetProvider("cert-name1", "plugin1", "config", mockWatcher, false)) {
      try {
        certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher, true);
        fail("exception expected");
      } catch (UnsupportedOperationException expected) {
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo("Provider does not support Certificate Updates.");
      }
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void onePluginSameConfig_sameInstance() {
    registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle2 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher2, true);
    assertThat(handle1).isNotSameInstanceAs(handle2);
    assertThat(handle1.certProvider).isSameInstanceAs(handle2.certProvider);
    assertThat(handle1.certProvider).isInstanceOf(TestCertificateProvider.class);
    TestCertificateProvider testCertificateProvider =
        (TestCertificateProvider) handle1.certProvider;
    assertThat(testCertificateProvider.startCalled).isEqualTo(1);
    CertificateProvider.DistributorWatcher distWatcher = testCertificateProvider.getWatcher();
    assertThat(distWatcher.downstreamWatchers).hasSize(2);
    PrivateKey testKey = mock(PrivateKey.class);
    X509Certificate cert = mock(X509Certificate.class);
    List<X509Certificate> testList = ImmutableList.of(cert);
    testCertificateProvider.getWatcher().updateCertificate(testKey, testList);
    verify(mockWatcher1, times(1)).updateCertificate(eq(testKey), eq(testList));
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey), eq(testList));
    reset(mockWatcher1);
    reset(mockWatcher2);
    testCertificateProvider.getWatcher().updateTrustedRoots(testList);
    verify(mockWatcher1, times(1)).updateTrustedRoots(eq(testList));
    verify(mockWatcher2, times(1)).updateTrustedRoots(eq(testList));
    reset(mockWatcher1);
    reset(mockWatcher2);
    handle1.close();
    assertThat(testCertificateProvider.closeCalled).isEqualTo(0);
    assertThat(distWatcher.downstreamWatchers).hasSize(1);
    testCertificateProvider.getWatcher().updateCertificate(testKey, testList);
    verify(mockWatcher1, never())
        .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey), eq(testList));
    testCertificateProvider.getWatcher().updateTrustedRoots(testList);
    verify(mockWatcher2, times(1)).updateTrustedRoots(eq(testList));
    handle2.close();
    assertThat(testCertificateProvider.closeCalled).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void onePluginSameConfig_secondWatcherAfterFirstNotify() {
    registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
            "cert-name1", "plugin1", "config", mockWatcher1, true);
    TestCertificateProvider testCertificateProvider =
            (TestCertificateProvider) handle1.certProvider;
    CertificateProvider.DistributorWatcher distWatcher = testCertificateProvider.getWatcher();
    PrivateKey testKey = mock(PrivateKey.class);
    X509Certificate cert = mock(X509Certificate.class);
    List<X509Certificate> testList = ImmutableList.of(cert);
    testCertificateProvider.getWatcher().updateCertificate(testKey, testList);
    verify(mockWatcher1, times(1)).updateCertificate(eq(testKey), eq(testList));
    testCertificateProvider.getWatcher().updateTrustedRoots(testList);
    verify(mockWatcher1, times(1)).updateTrustedRoots(eq(testList));
    testCertificateProvider.getWatcher().onError(Status.CANCELLED);
    verify(mockWatcher1, times(1)).onError(eq(Status.CANCELLED));
    reset(mockWatcher1);

    // now add the second watcher
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle unused = certificateProviderStore.createOrGetProvider(
            "cert-name1", "plugin1", "config", mockWatcher2, true);
    assertThat(distWatcher.downstreamWatchers).hasSize(2);
    // updates sent to the second watcher
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey), eq(testList));
    verify(mockWatcher2, times(1)).updateTrustedRoots(eq(testList));
    // but not errors!
    verify(mockWatcher2, never()).onError(eq(Status.CANCELLED));
    // and none to first one
    verify(mockWatcher1, never())
            .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
    verify(mockWatcher1, never()).updateTrustedRoots(anyListOf(X509Certificate.class));
    verify(mockWatcher1, never()).onError(any(Status.class));
  }

  @Test
  public void onePluginTwoInstances_notifyError() {
    registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
                            "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle2 = certificateProviderStore.createOrGetProvider(
                            "cert-name1", "plugin1", "config", mockWatcher2, true);
    TestCertificateProvider testCertificateProvider =
        (TestCertificateProvider) handle1.certProvider;
    testCertificateProvider.getWatcher().onError(Status.CANCELLED);
    verify(mockWatcher1, times(1)).onError(eq(Status.CANCELLED));
    verify(mockWatcher2, times(1)).onError(eq(Status.CANCELLED));
    handle1.close();
    handle2.close();
  }

  @Test
  public void onePluginDifferentConfig_differentInstance() {
    CertificateProviderProvider certProviderProvider = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle2 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config2", mockWatcher2, true);
    checkDifferentInstances(
        mockWatcher1, handle1, certProviderProvider, mockWatcher2, handle2, certProviderProvider);
  }

  @Test
  public void onePluginDifferentCertName_differentInstance() {
    CertificateProviderProvider certProviderProvider = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle2 = certificateProviderStore.createOrGetProvider(
                "cert-name2", "plugin1", "config", mockWatcher2, true);
    checkDifferentInstances(
        mockWatcher1, handle1, certProviderProvider, mockWatcher2, handle2, certProviderProvider);
  }

  @Test
  public void onePluginDifferentNotifyValue_sameInstance() {
    CertificateProviderProvider unused = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle2 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher2, false);
    assertThat(handle1).isNotSameInstanceAs(handle2);
    assertThat(handle1.certProvider).isSameInstanceAs(handle2.certProvider);
  }

  @Test
  public void twoPlugins_differentInstance() {
    CertificateProviderProvider certProviderProvider1 = registerPlugin("plugin1");
    CertificateProviderProvider certProviderProvider2 = registerPlugin("plugin2");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle2 = certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin2", "config", mockWatcher2, true);
    checkDifferentInstances(
        mockWatcher1, handle1, certProviderProvider1, mockWatcher2, handle2, certProviderProvider2);
  }

  @SuppressWarnings("deprecation")
  private static void checkDifferentInstances(
      CertificateProvider.Watcher mockWatcher1,
      CertificateProviderStore.Handle handle1,
      CertificateProviderProvider certProviderProvider1,
      CertificateProvider.Watcher mockWatcher2,
      CertificateProviderStore.Handle handle2,
      CertificateProviderProvider certProviderProvider2) {
    assertThat(handle1.certProvider).isNotSameInstanceAs(handle2.certProvider);
    TestCertificateProvider testCertificateProvider1 =
        (TestCertificateProvider) handle1.certProvider;
    TestCertificateProvider testCertificateProvider2 =
        (TestCertificateProvider) handle2.certProvider;
    assertThat(testCertificateProvider1.certProviderProvider)
        .isSameInstanceAs(certProviderProvider1);
    assertThat(testCertificateProvider2.certProviderProvider)
        .isSameInstanceAs(certProviderProvider2);
    CertificateProvider.DistributorWatcher distWatcher1 = testCertificateProvider1.getWatcher();
    assertThat(distWatcher1.downstreamWatchers).hasSize(1);
    CertificateProvider.DistributorWatcher distWatcher2 = testCertificateProvider2.getWatcher();
    assertThat(distWatcher2.downstreamWatchers).hasSize(1);
    PrivateKey testKey1 = mock(PrivateKey.class);
    X509Certificate cert1 = mock(X509Certificate.class);
    List<X509Certificate> testList1 = ImmutableList.of(cert1);
    testCertificateProvider1.getWatcher().updateCertificate(testKey1, testList1);
    verify(mockWatcher1, times(1)).updateCertificate(eq(testKey1), eq(testList1));
    verify(mockWatcher2, never())
        .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
    reset(mockWatcher1);

    PrivateKey testKey2 = mock(PrivateKey.class);
    X509Certificate cert2 = mock(X509Certificate.class);
    List<X509Certificate> testList2 = ImmutableList.of(cert2);
    testCertificateProvider2.getWatcher().updateCertificate(testKey2, testList2);
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey2), eq(testList2));
    verify(mockWatcher1, never())
        .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
    assertThat(testCertificateProvider1.startCalled).isEqualTo(1);
    assertThat(testCertificateProvider2.startCalled).isEqualTo(1);
    handle2.close();
    assertThat(testCertificateProvider2.closeCalled).isEqualTo(1);
    handle1.close();
    assertThat(testCertificateProvider1.closeCalled).isEqualTo(1);
  }

  private CertificateProviderProvider registerPlugin(String pluginName) {
    final CertificateProviderProvider certProviderProvider =
        mock(CertificateProviderProvider.class);
    when(certProviderProvider.getName()).thenReturn(pluginName);
    when(certProviderProvider.createCertificateProvider(
            any(Object.class),
            any(CertificateProvider.DistributorWatcher.class),
            anyBoolean()))
        .then(
            new Answer<CertificateProvider>() {

              @Override
              public CertificateProvider answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Object config = args[0];
                CertificateProvider.DistributorWatcher watcher =
                    (CertificateProvider.DistributorWatcher) args[1];
                boolean notifyCertUpdates = (Boolean) args[2];
                return new TestCertificateProvider(
                    watcher, notifyCertUpdates, config, certProviderProvider,
                    throwExceptionForCertUpdates);
              }
            });
    certificateProviderRegistry.register(certProviderProvider);
    return certProviderProvider;
  }
}
