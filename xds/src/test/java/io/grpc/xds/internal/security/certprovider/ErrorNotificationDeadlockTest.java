/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.security.certprovider;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.security.Closeable;
import io.grpc.xds.internal.security.DynamicSslContextProvider;
import io.grpc.xds.internal.security.ReferenceCountingMap;
import io.grpc.xds.internal.security.SslContextProvider;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Reproduces the lock-order cycle between certificate error notification and provider release
 * observed in production: a file_watcher refresh thread delivers {@code onError} while the
 * {@link CertificateProvider.DistributorWatcher} holds its lock, and the error callback releases
 * the {@code SslContextProvider} through {@link ReferenceCountingMap}, which itself holds its
 * lock while closing the provider, and closing reaches {@code removeWatcher} on the same
 * DistributorWatcher. When both run concurrently the two locks are acquired in opposite orders.
 */
@RunWith(JUnit4.class)
public class ErrorNotificationDeadlockTest {

  @Test
  public void onError_doesNotHoldWatcherLockAcrossProviderRelease() throws Exception {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    final TestErrorWatcher provider = new TestErrorWatcher();
    distWatcher.addWatcher(provider);

    final CountDownLatch releaseThreadParked = new CountDownLatch(1);
    final CountDownLatch letReleaseProceed = new CountDownLatch(1);
    final CountDownLatch errorCallbackStarted = new CountDownLatch(1);

    final Closeable errorCallbackRef = new TestCloseable();
    Closeable releaseThreadRef = new TestCloseable() {
      @Override
      public void close() {
        releaseThreadParked.countDown();
        try {
          assertThat(letReleaseProceed.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        distWatcher.removeWatcher(provider);
      }
    };
    final ReferenceCountingMap<String, Closeable> certProviderMap =
        new ReferenceCountingMap<>(
            key -> "releaseThreadRef".equals(key) ? releaseThreadRef : errorCallbackRef);
    assertThat(certProviderMap.get("errorCallbackRef")).isSameInstanceAs(errorCallbackRef);
    assertThat(certProviderMap.get("releaseThreadRef")).isSameInstanceAs(releaseThreadRef);

    ExecutorService callbackExecutor = newDaemonExecutor();
    provider.addCallback(new SslContextProvider.Callback(callbackExecutor) {
      @Override
      public void updateSslContextAndExtendedX509TrustManager(
          AbstractMap.SimpleImmutableEntry<SslContext, X509TrustManager> sslContext) {
      }

      @Override
      protected void onException(Throwable throwable) {
        errorCallbackStarted.countDown();
        certProviderMap.release("errorCallbackRef", errorCallbackRef);
      }
    });

    ExecutorService releaseExecutor = newDaemonExecutor();
    ExecutorService refreshExecutor = newDaemonExecutor();
    try {
      // Like a Netty event loop releasing an SslContextProvider: releaseInternal() holds the
      // map lock while closing the value, and the close reaches removeWatcher(), which needs
      // the DistributorWatcher lock.
      Future<?> release = releaseExecutor.submit(
          () -> certProviderMap.release("releaseThreadRef", releaseThreadRef));
      assertThat(releaseThreadParked.await(10, TimeUnit.SECONDS)).isTrue();

      // Like a file_watcher refresh thread delivering an error: the DistributorWatcher holds
      // its lock while fanning out to downstream watchers.
      Future<?> refresh = refreshExecutor.submit(() -> distWatcher.onError(Status.UNAVAILABLE));
      assertThat(errorCallbackStarted.await(10, TimeUnit.SECONDS)).isTrue();

      letReleaseProceed.countDown();

      try {
        refresh.get(10, TimeUnit.SECONDS);
        release.get(10, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        fail("Deadlock between error notification and provider release: the refresh thread"
            + " holds the DistributorWatcher lock while blocked on ReferenceCountingMap,"
            + " while the release thread holds the map lock while blocked on removeWatcher");
        throw e;
      }
    } finally {
      releaseExecutor.shutdownNow();
      refreshExecutor.shutdownNow();
      callbackExecutor.shutdownNow();
    }
  }

  private static ExecutorService newDaemonExecutor() {
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable);
      thread.setDaemon(true);
      return thread;
    });
  }

  private static class TestCloseable implements Closeable {
    @Override
    public void close() {
    }
  }

  private static final class TestErrorWatcher extends DynamicSslContextProvider
      implements CertificateProvider.Watcher {

    TestErrorWatcher() {
      super(new UpstreamTlsContext(CommonTlsContext.getDefaultInstance()), null);
    }

    @Override
    public void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
    }

    @Override
    public void updateTrustedRoots(List<X509Certificate> trustedRoots) {
    }

    @Override
    public void updateSpiffeTrustMap(Map<String, List<X509Certificate>> spiffeTrustMap) {
    }

    @Override
    protected CertificateValidationContext generateCertificateValidationContext() {
      return null;
    }

    @Override
    protected AbstractMap.SimpleImmutableEntry<SslContextBuilder, X509TrustManager>
        getSslContextBuilderAndTrustManager(
            CertificateValidationContext certificateValidationContext) {
      throw new UnsupportedOperationException("Not expected in this test");
    }

    @Override
    public void close() {
    }
  }
}
