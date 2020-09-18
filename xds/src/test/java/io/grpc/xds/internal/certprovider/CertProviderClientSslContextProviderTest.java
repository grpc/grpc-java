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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.certprovider.CommonCertProviderTestUtils.getCertFromResourceName;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_0_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.doChecksOnSslContext;
import static org.junit.Assert.fail;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.CommonBootstrapperTestUtils;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.TestCallback;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CertProviderClientSslContextProvider}. */
@RunWith(JUnit4.class)
public class CertProviderClientSslContextProviderTest {
  private static final Logger logger =
      Logger.getLogger(CertProviderClientSslContextProviderTest.class.getName());

  CertificateProviderRegistry certificateProviderRegistry;
  CertificateProviderStore certificateProviderStore;
  private CertProviderClientSslContextProvider.Factory certProviderClientSslContextProviderFactory;

  @Before
  public void setUp() throws Exception {
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    certProviderClientSslContextProviderFactory =
        new CertProviderClientSslContextProvider.Factory(certificateProviderStore);
  }

  /** Helper method to build CertProviderClientSslContextProvider. */
  private CertProviderClientSslContextProvider getSslContextProvider(
      String certInstanceName,
      String rootInstanceName,
      Bootstrapper.BootstrapInfo bootstrapInfo,
      Iterable<String> alpnProtocols,
      CertificateValidationContext staticCertValidationContext) {
    EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
            certInstanceName,
            "cert-default",
            rootInstanceName,
            "root-default",
            alpnProtocols,
            staticCertValidationContext);
    return certProviderClientSslContextProviderFactory.getProvider(
        upstreamTlsContext,
        bootstrapInfo.getNode().toEnvoyProtoNode(),
        bootstrapInfo.getCertProviders());
  }

  @Test
  public void testProviderForClient_mtls() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    TestCertificateProvider.createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
        getSslContextProvider(
            "gcp_id",
            "gcp_id",
            CommonBootstrapperTestUtils.getTestBootstrapInfo(),
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null);

    assertThat(provider.savedKey).isNull();
    assertThat(provider.savedCertChain).isNull();
    assertThat(provider.savedTrustedRoots).isNull();
    assertThat(provider.getSslContext()).isNull();

    // now generate cert update
    watcherCaptor[0].updateCertificate(
        CommonCertProviderTestUtils.getPrivateKey(CLIENT_KEY_FILE),
        ImmutableList.of(getCertFromResourceName(CLIENT_PEM_FILE)));
    assertThat(provider.savedKey).isNotNull();
    assertThat(provider.savedCertChain).isNotNull();
    assertThat(provider.getSslContext()).isNull();

    // now generate root cert update
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
    assertThat(provider.getSslContext()).isNotNull();
    assertThat(provider.savedKey).isNull();
    assertThat(provider.savedCertChain).isNull();
    assertThat(provider.savedTrustedRoots).isNull();

    TestCallback testCallback =
        CommonTlsContextTestsUtil.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
    TestCallback testCallback1 =
        CommonTlsContextTestsUtil.getValueThruCallback(provider);
    assertThat(testCallback1.updatedSslContext).isSameInstanceAs(testCallback.updatedSslContext);

    // just do root cert update: sslContext should still be the same
    watcherCaptor[0].updateTrustedRoots(
        ImmutableList.of(getCertFromResourceName(SERVER_0_PEM_FILE)));
    assertThat(provider.savedKey).isNull();
    assertThat(provider.savedCertChain).isNull();
    assertThat(provider.savedTrustedRoots).isNotNull();
    testCallback1 = CommonTlsContextTestsUtil.getValueThruCallback(provider);
    assertThat(testCallback1.updatedSslContext).isSameInstanceAs(testCallback.updatedSslContext);

    // now update id cert: sslContext should be updated i.e.different from the previous one
    watcherCaptor[0].updateCertificate(
        CommonCertProviderTestUtils.getPrivateKey(SERVER_1_KEY_FILE),
        ImmutableList.of(getCertFromResourceName(SERVER_1_PEM_FILE)));
    assertThat(provider.savedKey).isNull();
    assertThat(provider.savedCertChain).isNull();
    assertThat(provider.savedTrustedRoots).isNull();
    assertThat(provider.getSslContext()).isNotNull();
    testCallback1 = CommonTlsContextTestsUtil.getValueThruCallback(provider);
    assertThat(testCallback1.updatedSslContext).isNotSameInstanceAs(testCallback.updatedSslContext);
  }

  @Test
  public void testProviderForClient_queueExecutor() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    TestCertificateProvider.createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
        getSslContextProvider(
            "gcp_id",
            "gcp_id",
            CommonBootstrapperTestUtils.getTestBootstrapInfo(),
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null);
    QueuedExecutor queuedExecutor = new QueuedExecutor();

    TestCallback testCallback =
        CommonTlsContextTestsUtil.getValueThruCallback(provider, queuedExecutor);
    assertThat(queuedExecutor.runQueue).isEmpty();

    // now generate cert update
    watcherCaptor[0].updateCertificate(
        CommonCertProviderTestUtils.getPrivateKey(CLIENT_KEY_FILE),
        ImmutableList.of(getCertFromResourceName(CLIENT_PEM_FILE)));
    assertThat(queuedExecutor.runQueue).isEmpty(); // still empty

    // now generate root cert update
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
    assertThat(queuedExecutor.runQueue).hasSize(1);
    queuedExecutor.drain();

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient_tls() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    TestCertificateProvider.createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
        getSslContextProvider(
            /* certInstanceName= */ null,
            "gcp_id",
            CommonBootstrapperTestUtils.getTestBootstrapInfo(),
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null);

    assertThat(provider.savedKey).isNull();
    assertThat(provider.savedCertChain).isNull();
    assertThat(provider.savedTrustedRoots).isNull();
    assertThat(provider.getSslContext()).isNull();

    // now generate root cert update
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
    assertThat(provider.getSslContext()).isNotNull();
    assertThat(provider.savedKey).isNull();
    assertThat(provider.savedCertChain).isNull();
    assertThat(provider.savedTrustedRoots).isNull();

    TestCallback testCallback =
        CommonTlsContextTestsUtil.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient_sslContextException_onError() throws Exception {
    CertificateValidationContext staticCertValidationContext =
            CertificateValidationContext.newBuilder()
                    .setTrustedCa(DataSource.newBuilder().setInlineString("foo"))
                    .build();

    final CertificateProvider.DistributorWatcher[] watcherCaptor =
            new CertificateProvider.DistributorWatcher[1];
    TestCertificateProvider.createAndRegisterProviderProvider(
            certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
            getSslContextProvider(
                    /* certInstanceName= */ null,
                    "gcp_id",
                    CommonBootstrapperTestUtils.getTestBootstrapInfo(),
                    /* alpnProtocols= */null,
                    staticCertValidationContext);

    TestCallback testCallback = new TestCallback(MoreExecutors.directExecutor());
    provider.addCallback(testCallback);
    try {
      watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
      fail("exception expected");
    } catch (RuntimeException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains("only static certificateValidationContext expected");
    }
    assertThat(testCallback.updatedThrowable).isNotNull();
    assertThat(testCallback.updatedThrowable)
        .hasCauseThat()
        .hasMessageThat()
        .contains("only static certificateValidationContext expected");
  }

  @Test
  public void testProviderForClient_rootInstanceNull_expectError() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    TestCertificateProvider.createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "testca", 0);
    try {
      getSslContextProvider(
          /* certInstanceName= */ null,
          /* rootInstanceName= */ null,
          CommonBootstrapperTestUtils.getTestBootstrapInfo(),
          /* alpnProtocols= */ null,
          /* staticCertValidationContext= */ null);
      fail("exception expected");
    } catch (NullPointerException expected) {
      assertThat(expected).hasMessageThat().contains("Client SSL requires rootCertInstance");
    }
  }

  static class QueuedExecutor implements Executor {
    /** A list of Runnables to be run in order. */
    @VisibleForTesting final Queue<Runnable> runQueue = new ConcurrentLinkedQueue<>();

    @Override
    public synchronized void execute(Runnable r) {
      runQueue.add(checkNotNull(r, "'r' must not be null."));
    }

    public synchronized void drain() {
      Runnable r;
      while ((r = runQueue.poll()) != null) {
        try {
          r.run();
        } catch (RuntimeException e) {
          // Log it and keep going.
          logger.log(Level.SEVERE, "Exception while executing runnable " + r, e);
        }
      }
    }
  }

}
