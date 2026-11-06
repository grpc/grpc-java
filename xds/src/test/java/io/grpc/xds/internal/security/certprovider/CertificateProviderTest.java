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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.xds.internal.security.certprovider.CertificateProvider.DistributorWatcher;
import io.grpc.xds.internal.security.certprovider.CertificateProvider.Watcher;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests notification delivery when callbacks change certificate provider subscriptions. */
@RunWith(JUnit4.class)
public class CertificateProviderTest {
  private final DistributorWatcher distributor = new DistributorWatcher();
  private final Watcher watcher1 = mock(Watcher.class);
  private final Watcher watcher2 = mock(Watcher.class);
  private final Map<String, List<X509Certificate>> trustMap =
      ImmutableMap.of("example.org", ImmutableList.of(mock(X509Certificate.class)));

  @Test
  public void updateSpiffeTrustMap_doesNotOverlapCertificateReplay() throws Exception {
    PrivateKey key = mock(PrivateKey.class);
    List<X509Certificate> certChain = ImmutableList.of(mock(X509Certificate.class));
    distributor.updateCertificate(key, certChain);
    CountDownLatch replayStarted = new CountDownLatch(1);
    CountDownLatch finishReplay = new CountDownLatch(1);
    CountDownLatch updateStarted = new CountDownLatch(1);
    AtomicBoolean replayingCertificate = new AtomicBoolean();
    AtomicBoolean overlappingNotifications = new AtomicBoolean();
    doAnswer(invocation -> {
      replayingCertificate.set(true);
      replayStarted.countDown();
      assertThat(finishReplay.await(10, TimeUnit.SECONDS)).isTrue();
      replayingCertificate.set(false);
      return null;
    }).when(watcher1).updateCertificate(key, certChain);
    doAnswer(invocation -> {
      overlappingNotifications.compareAndSet(false, replayingCertificate.get());
      return null;
    }).when(watcher1).updateSpiffeTrustMap(trustMap);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> registration = executor.submit(() -> distributor.addWatcher(watcher1));
      assertThat(replayStarted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> update = executor.submit(() -> {
        updateStarted.countDown();
        distributor.updateSpiffeTrustMap(trustMap);
      });
      assertThat(updateStarted.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        // A serialized update waits for registration to finish replaying the certificate.
        update.get(1, TimeUnit.SECONDS);
      } catch (TimeoutException expected) {
        // Release registration below so both tasks can finish before checking delivery.
      } finally {
        finishReplay.countDown();
      }
      registration.get(10, TimeUnit.SECONDS);
      update.get(10, TimeUnit.SECONDS);

      assertThat(overlappingNotifications.get()).isFalse();
      verify(watcher1).updateSpiffeTrustMap(trustMap);
    } finally {
      finishReplay.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  public void updateCertificate_watcherRemovedDuringNotification() {
    distributor.addWatcher(watcher1);
    distributor.addWatcher(watcher2);
    PrivateKey key = mock(PrivateKey.class);
    List<X509Certificate> certChain = ImmutableList.of(mock(X509Certificate.class));
    Watcher firstWatcher = distributor.getDownstreamWatchers().iterator().next();
    doAnswer(invocation -> {
      distributor.removeWatcher(firstWatcher);
      return null;
    }).when(firstWatcher).updateCertificate(key, certChain);

    distributor.updateCertificate(key, certChain);

    verify(watcher1).updateCertificate(key, certChain);
    verify(watcher2).updateCertificate(key, certChain);
  }

  @Test
  public void updateTrustedRoots_watcherRemovedDuringNotification() {
    distributor.addWatcher(watcher1);
    distributor.addWatcher(watcher2);
    List<X509Certificate> trustedRoots = ImmutableList.of(mock(X509Certificate.class));
    Watcher firstWatcher = distributor.getDownstreamWatchers().iterator().next();
    doAnswer(invocation -> {
      distributor.removeWatcher(firstWatcher);
      return null;
    }).when(firstWatcher).updateTrustedRoots(trustedRoots);

    distributor.updateTrustedRoots(trustedRoots);

    verify(watcher1).updateTrustedRoots(trustedRoots);
    verify(watcher2).updateTrustedRoots(trustedRoots);
  }

  @Test
  public void updateSpiffeTrustMap_watcherAddedDuringNotification() {
    distributor.addWatcher(watcher1);
    distributor.addWatcher(watcher2);
    Watcher addedWatcher = mock(Watcher.class);
    // Select the first callback without depending on HashSet's iteration order. At least one
    // existing watcher still needs its initial trust map when this callback changes the set.
    Watcher firstWatcher = distributor.getDownstreamWatchers().iterator().next();
    doAnswer(invocation -> {
      distributor.addWatcher(addedWatcher);
      return null;
    }).when(firstWatcher).updateSpiffeTrustMap(trustMap);

    distributor.updateSpiffeTrustMap(trustMap);

    verify(watcher1).updateSpiffeTrustMap(trustMap);
    verify(watcher2).updateSpiffeTrustMap(trustMap);
    verify(addedWatcher).updateSpiffeTrustMap(trustMap);
  }

  @Test
  public void updateSpiffeTrustMap_watcherRemovedDuringNotification() {
    distributor.addWatcher(watcher1);
    distributor.addWatcher(watcher2);
    Watcher firstWatcher = distributor.getDownstreamWatchers().iterator().next();
    doAnswer(invocation -> {
      distributor.removeWatcher(firstWatcher);
      return null;
    }).when(firstWatcher).updateSpiffeTrustMap(trustMap);

    distributor.updateSpiffeTrustMap(trustMap);

    verify(watcher1).updateSpiffeTrustMap(trustMap);
    verify(watcher2).updateSpiffeTrustMap(trustMap);
    assertThat(distributor.getDownstreamWatchers()).doesNotContain(firstWatcher);
  }

  @Test
  public void onError_watcherRemovedDuringNotification() {
    distributor.addWatcher(watcher1);
    distributor.addWatcher(watcher2);
    Watcher firstWatcher = distributor.getDownstreamWatchers().iterator().next();
    doAnswer(invocation -> {
      distributor.removeWatcher(firstWatcher);
      return null;
    }).when(firstWatcher).onError(Status.UNAVAILABLE);

    distributor.onError(Status.UNAVAILABLE);

    verify(watcher1).onError(Status.UNAVAILABLE);
    verify(watcher2).onError(Status.UNAVAILABLE);
    assertThat(distributor.getDownstreamWatchers()).doesNotContain(firstWatcher);
  }
}
