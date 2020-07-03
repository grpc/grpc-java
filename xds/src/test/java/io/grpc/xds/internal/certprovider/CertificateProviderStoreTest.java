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
import static org.mockito.ArgumentMatchers.anyListOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
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

  private class TestCertificateProvider extends CertificateProvider {
    Object config;
    CertificateProviderProvider certProviderProvider;
    int closeCalled = 0;

    protected TestCertificateProvider(
        Watcher watcher,
        boolean notifyCertUpdates,
        Object config,
        CertificateProviderProvider certificateProviderProvider) {
      super(watcher, notifyCertUpdates);
      if (throwExceptionForCertUpdates && notifyCertUpdates) {
        throw new UnsupportedOperationException("Provider does not support Certificate Updates.");
      }
      this.config = config;
      this.certProviderProvider = certificateProviderProvider;
    }

    @Override
    public void close() {
      closeCalled++;
    }
  }

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
      CertificateProviderStore.Handle unused =
          certificateProviderStore.createOrGetProvider(
              "cert-name1", "plugin1", "config", mockWatcher, true);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Provider not found.");
    }
  }

  @Test
  public void pluginUnregistered_expectException() {
    CertificateProviderProvider certificateProviderProvider = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle =
        certificateProviderStore.createOrGetProvider(
            "cert-name1", "plugin1", "config", mockWatcher, true);
    handle.close();
    certificateProviderRegistry.deregister(certificateProviderProvider);
    try {
      handle =
          certificateProviderStore.createOrGetProvider(
              "cert-name1", "plugin1", "config", mockWatcher, true);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Provider not found.");
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
    CertificateProviderProvider unused = registerPlugin("plugin1");
    throwExceptionForCertUpdates = true;
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.Handle handle1 =
            certificateProviderStore.createOrGetProvider(
                    "cert-name1", "plugin1", "config", mockWatcher, false);
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
    handle1.close();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void onePluginSameConfig_sameInstance() {
    registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle1 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle2 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher2, true);
    assertThat(handle1).isNotSameInstanceAs(handle2);
    assertThat(handle1.certProvider).isSameInstanceAs(handle2.certProvider);
    assertThat(handle1.certProvider).isInstanceOf(TestCertificateProvider.class);
    TestCertificateProvider testCertificateProvider =
        (TestCertificateProvider) handle1.certProvider;
    CertificateProviderStore.DistributorWatcher distWatcher =
        (CertificateProviderStore.DistributorWatcher) testCertificateProvider.watcher;
    assertThat(distWatcher.downsstreamWatchers.size()).isEqualTo(2);
    PrivateKey testKey = mock(PrivateKey.class);
    X509Certificate cert = mock(X509Certificate.class);
    List<X509Certificate> testList = ImmutableList.of(cert);
    testCertificateProvider.watcher.updateCertificate(testKey, testList);
    verify(mockWatcher1, times(1)).updateCertificate(eq(testKey), eq(testList));
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey), eq(testList));
    reset(mockWatcher1);
    reset(mockWatcher2);
    testCertificateProvider.watcher.updateTrustedRoots(testList);
    verify(mockWatcher1, times(1)).updateTrustedRoots(eq(testList));
    verify(mockWatcher2, times(1)).updateTrustedRoots(eq(testList));
    reset(mockWatcher1);
    reset(mockWatcher2);
    handle1.close();
    assertThat(testCertificateProvider.closeCalled).isEqualTo(0);
    assertThat(distWatcher.downsstreamWatchers.size()).isEqualTo(1);
    testCertificateProvider.watcher.updateCertificate(testKey, testList);
    verify(mockWatcher1, never())
        .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey), eq(testList));
    testCertificateProvider.watcher.updateTrustedRoots(testList);
    verify(mockWatcher2, times(1)).updateTrustedRoots(eq(testList));
    handle2.close();
    assertThat(testCertificateProvider.closeCalled).isEqualTo(1);
  }

  @Test
  public void onePluginDifferentConfig_differentInstance() {
    CertificateProviderProvider certProviderProvider = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle1 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle2 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config2", mockWatcher2, true);
    checkDifferentInstances(
        mockWatcher1, handle1, certProviderProvider, mockWatcher2, handle2, certProviderProvider);
  }

  @Test
  public void onePluginDifferentCertName_differentInstance() {
    CertificateProviderProvider certProviderProvider = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle1 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle2 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name2", "plugin1", "config", mockWatcher2, true);
    checkDifferentInstances(
        mockWatcher1, handle1, certProviderProvider, mockWatcher2, handle2, certProviderProvider);
  }

  @Test
  public void onePluginDifferentNotifyValue_sameInstance() {
    CertificateProviderProvider unused = registerPlugin("plugin1");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle1 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle2 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher2, false);
    assertThat(handle1).isNotSameInstanceAs(handle2);
    assertThat(handle1.certProvider).isSameInstanceAs(handle2.certProvider);
  }

  @Test
  public void twoPlugins_differentInstance() {
    CertificateProviderProvider certProviderProvider1 = registerPlugin("plugin1");
    CertificateProviderProvider certProviderProvider2 = registerPlugin("plugin2");
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle1 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle2 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin2", "config", mockWatcher2, true);
    checkDifferentInstances(
        mockWatcher1, handle1, certProviderProvider1, mockWatcher2, handle2, certProviderProvider2);
  }

  @SuppressWarnings("deprecation")
  private void checkDifferentInstances(
      CertificateProvider.Watcher mockWatcher1,
      CertificateProviderStore.HandleImpl handle1,
      CertificateProviderProvider certProviderProvider1,
      CertificateProvider.Watcher mockWatcher2,
      CertificateProviderStore.HandleImpl handle2,
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
    CertificateProviderStore.DistributorWatcher distWatcher1 =
        (CertificateProviderStore.DistributorWatcher) testCertificateProvider1.watcher;
    assertThat(distWatcher1.downsstreamWatchers.size()).isEqualTo(1);
    CertificateProviderStore.DistributorWatcher distWatcher2 =
        (CertificateProviderStore.DistributorWatcher) testCertificateProvider2.watcher;
    assertThat(distWatcher2.downsstreamWatchers.size()).isEqualTo(1);
    PrivateKey testKey1 = mock(PrivateKey.class);
    X509Certificate cert1 = mock(X509Certificate.class);
    List<X509Certificate> testList1 = ImmutableList.of(cert1);
    testCertificateProvider1.watcher.updateCertificate(testKey1, testList1);
    verify(mockWatcher1, times(1)).updateCertificate(eq(testKey1), eq(testList1));
    verify(mockWatcher2, never())
        .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
    reset(mockWatcher1);

    PrivateKey testKey2 = mock(PrivateKey.class);
    X509Certificate cert2 = mock(X509Certificate.class);
    List<X509Certificate> testList2 = ImmutableList.of(cert2);
    testCertificateProvider2.watcher.updateCertificate(testKey2, testList2);
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey2), eq(testList2));
    verify(mockWatcher1, never())
        .updateCertificate(any(PrivateKey.class), anyListOf(X509Certificate.class));
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
            any(Object.class), any(CertificateProvider.Watcher.class), any(Boolean.TYPE)))
        .then(
            new Answer<CertificateProvider>() {

              @Override
              public CertificateProvider answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Object config = args[0];
                CertificateProvider.Watcher watcher = (CertificateProvider.Watcher) args[1];
                boolean notifyCertUpdates = (Boolean) args[2];
                return new TestCertificateProvider(
                    watcher, notifyCertUpdates, config, certProviderProvider);
              }
            });
    certificateProviderRegistry.register(certProviderProvider);
    return certProviderProvider;
  }
}
