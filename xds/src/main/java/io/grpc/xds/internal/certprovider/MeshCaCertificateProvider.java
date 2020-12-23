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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.Status.Code.ABORTED;
import static io.grpc.Status.Code.CANCELLED;
import static io.grpc.Status.Code.DEADLINE_EXCEEDED;
import static io.grpc.Status.Code.INTERNAL;
import static io.grpc.Status.Code.RESOURCE_EXHAUSTED;
import static io.grpc.Status.Code.UNAVAILABLE;
import static io.grpc.Status.Code.UNKNOWN;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;
import com.google.security.meshca.v1.MeshCertificateRequest;
import com.google.security.meshca.v1.MeshCertificateResponse;
import com.google.security.meshca.v1.MeshCertificateServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.TlsChannelCredentials;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.internal.sds.trust.CertificateUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;

/** Implementation of {@link CertificateProvider} for the Google Mesh CA. */
final class MeshCaCertificateProvider extends CertificateProvider {
  private static final Logger logger = Logger.getLogger(MeshCaCertificateProvider.class.getName());

  MeshCaCertificateProvider(
      DistributorWatcher watcher,
      boolean notifyCertUpdates,
      String meshCaUrl,
      String zone,
      long validitySeconds,
      int keySize,
      String unused, //TODO(sanjaypujare): to remove during refactoring
      String signatureAlg, MeshCaChannelFactory meshCaChannelFactory,
      BackoffPolicy.Provider backoffPolicyProvider,
      long renewalGracePeriodSeconds,
      int maxRetryAttempts,
      GoogleCredentials oauth2Creds,
      ScheduledExecutorService scheduledExecutorService,
      TimeProvider timeProvider,
      long rpcTimeoutMillis) {
    super(watcher, notifyCertUpdates);
    this.meshCaUrl = checkNotNull(meshCaUrl, "meshCaUrl");
    checkArgument(
        validitySeconds > INITIAL_DELAY_SECONDS,
        "validitySeconds must be greater than " + INITIAL_DELAY_SECONDS);
    this.validitySeconds = validitySeconds;
    this.keySize = keySize;
    this.signatureAlg = checkNotNull(signatureAlg, "signatureAlg");
    this.meshCaChannelFactory = checkNotNull(meshCaChannelFactory, "meshCaChannelFactory");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    checkArgument(
        renewalGracePeriodSeconds > 0L && renewalGracePeriodSeconds < validitySeconds,
        "renewalGracePeriodSeconds should be between 0 and " + validitySeconds);
    this.renewalGracePeriodSeconds = renewalGracePeriodSeconds;
    checkArgument(maxRetryAttempts >= 0, "maxRetryAttempts must be >= 0");
    this.maxRetryAttempts = maxRetryAttempts;
    this.oauth2Creds = checkNotNull(oauth2Creds, "oauth2Creds");
    this.scheduledExecutorService =
        checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    this.headerInterceptor = new ZoneInfoClientInterceptor(checkNotNull(zone, "zone"));
    this.syncContext = createSynchronizationContext(meshCaUrl);
    this.rpcTimeoutMillis = rpcTimeoutMillis;
  }

  private SynchronizationContext createSynchronizationContext(String details) {
    final InternalLogId logId = InternalLogId.allocate("MeshCaCertificateProvider", details);
    return new SynchronizationContext(
        new Thread.UncaughtExceptionHandler() {
          private boolean panicMode;

          @Override
          public void uncaughtException(Thread t, Throwable e) {
            logger.log(
                Level.SEVERE,
                "[" + logId + "] Uncaught exception in the SynchronizationContext. Panic!",
                e);
            panic(e);
          }

          void panic(final Throwable t) {
            if (panicMode) {
              // Preserve the first panic information
              return;
            }
            panicMode = true;
            close();
          }
        });
  }

  @Override
  public void start() {
    scheduleNextRefreshCertificate(INITIAL_DELAY_SECONDS);
  }

  @Override
  public void close() {
    if (scheduledHandle != null) {
      scheduledHandle.cancel();
      scheduledHandle = null;
    }
    getWatcher().close();
  }

  private void scheduleNextRefreshCertificate(long delayInSeconds) {
    if (scheduledHandle != null && scheduledHandle.isPending()) {
      logger.log(Level.SEVERE, "Pending task found: inconsistent state in scheduledHandle!");
      scheduledHandle.cancel();
    }
    RefreshCertificateTask runnable = new RefreshCertificateTask();
    scheduledHandle = syncContext.schedule(
            runnable, delayInSeconds, TimeUnit.SECONDS, scheduledExecutorService);
  }

  @VisibleForTesting
  void refreshCertificate()
      throws NoSuchAlgorithmException, IOException, OperatorCreationException {
    long refreshDelaySeconds = computeRefreshSecondsFromCurrentCertExpiry();
    ManagedChannel channel = meshCaChannelFactory.createChannel(meshCaUrl);
    try {
      String uniqueReqIdForAllRetries = UUID.randomUUID().toString();
      Duration duration = Duration.newBuilder().setSeconds(validitySeconds).build();
      KeyPair keyPair = generateKeyPair();
      String csr = generateCsr(keyPair);
      MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub stub =
          createStubToMeshCa(channel);
      List<X509Certificate> x509Chain = makeRequestWithRetries(stub, uniqueReqIdForAllRetries,
          duration, csr);
      if (x509Chain != null) {
        refreshDelaySeconds =
            computeDelaySecondsToCertExpiry(x509Chain.get(0)) - renewalGracePeriodSeconds;
        getWatcher().updateCertificate(keyPair.getPrivate(), x509Chain);
        getWatcher().updateTrustedRoots(ImmutableList.of(x509Chain.get(x509Chain.size() - 1)));
      }
    } finally {
      shutdownChannel(channel);
      scheduleNextRefreshCertificate(refreshDelaySeconds);
    }
  }

  private MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub createStubToMeshCa(
      ManagedChannel channel) {
    return MeshCertificateServiceGrpc
        .newBlockingStub(channel)
        .withCallCredentials(MoreCallCredentials.from(oauth2Creds))
        .withInterceptors(headerInterceptor);
  }

  private List<X509Certificate> makeRequestWithRetries(
      MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub stub,
      String reqId,
      Duration duration,
      String csr) {
    MeshCertificateRequest request =
        MeshCertificateRequest.newBuilder()
            .setValidity(duration)
            .setCsr(csr)
            .setRequestId(reqId)
            .build();

    BackoffPolicy backoffPolicy = backoffPolicyProvider.get();
    Throwable lastException = null;
    for (int i = 0; i <= maxRetryAttempts; i++) {
      try {
        MeshCertificateResponse response =
            stub.withDeadlineAfter(rpcTimeoutMillis, TimeUnit.MILLISECONDS)
                .createCertificate(request);
        return getX509CertificatesFromResponse(response);
      } catch (Throwable t) {
        if (!retriable(t)) {
          generateErrorIfCurrentCertExpired(t);
          return null;
        }
        lastException = t;
        sleepForNanos(backoffPolicy.nextBackoffNanos());
      }
    }
    generateErrorIfCurrentCertExpired(lastException);
    return null;
  }

  private void sleepForNanos(long nanos) {
    ScheduledFuture<?> future = scheduledExecutorService.schedule(new Runnable() {
      @Override
      public void run() {
        // do nothing
      }
    }, nanos, TimeUnit.NANOSECONDS);
    try {
      future.get(nanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException ie) {
      logger.log(Level.SEVERE, "Inside sleep", ie);
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException ex) {
      logger.log(Level.SEVERE, "Inside sleep", ex);
    }
  }

  private static boolean retriable(Throwable t) {
    return RETRIABLE_CODES.contains(Status.fromThrowable(t).getCode());
  }

  private void generateErrorIfCurrentCertExpired(Throwable t) {
    X509Certificate currentCert = getWatcher().getLastIdentityCert();
    if (currentCert != null) {
      long delaySeconds = computeDelaySecondsToCertExpiry(currentCert);
      if (delaySeconds > INITIAL_DELAY_SECONDS) {
        return;
      }
      getWatcher().clearValues();
    }
    getWatcher().onError(Status.fromThrowable(t));
  }

  private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(keySize);
    return keyPairGenerator.generateKeyPair();
  }

  private String generateCsr(KeyPair pair) throws IOException, OperatorCreationException {
    PKCS10CertificationRequestBuilder p10Builder =
        new JcaPKCS10CertificationRequestBuilder(
            new X500Principal("CN=EXAMPLE.COM"), pair.getPublic());
    JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder(signatureAlg);
    ContentSigner signer = csBuilder.build(pair.getPrivate());
    PKCS10CertificationRequest csr = p10Builder.build(signer);
    PemObject pemObject = new PemObject("NEW CERTIFICATE REQUEST", csr.getEncoded());
    try (StringWriter str = new StringWriter()) {
      try (JcaPEMWriter pemWriter = new JcaPEMWriter(str)) {
        pemWriter.writeObject(pemObject);
      }
      return str.toString();
    }
  }

  /** Compute refresh interval as half of interval to current cert expiry. */
  private long computeRefreshSecondsFromCurrentCertExpiry() {
    X509Certificate lastCert = getWatcher().getLastIdentityCert();
    if (lastCert == null) {
      return INITIAL_DELAY_SECONDS;
    }
    long delayToCertExpirySeconds = computeDelaySecondsToCertExpiry(lastCert) / 2;
    return Math.max(delayToCertExpirySeconds, INITIAL_DELAY_SECONDS);
  }

  @SuppressWarnings("JdkObsolete")
  private long computeDelaySecondsToCertExpiry(X509Certificate lastCert) {
    checkNotNull(lastCert, "lastCert");
    return TimeUnit.NANOSECONDS.toSeconds(
        TimeUnit.MILLISECONDS.toNanos(lastCert.getNotAfter().getTime()) - timeProvider
            .currentTimeNanos());
  }

  private static void shutdownChannel(ManagedChannel channel) {
    channel.shutdown();
    try {
      channel.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      logger.log(Level.SEVERE, "awaiting channel Termination", ex);
      channel.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private List<X509Certificate> getX509CertificatesFromResponse(
      MeshCertificateResponse response) throws CertificateException, IOException {
    List<String> certChain = response.getCertChainList();
    List<X509Certificate> x509Chain = new ArrayList<>(certChain.size());
    for (String certString : certChain) {
      try (ByteArrayInputStream bais = new ByteArrayInputStream(certString.getBytes(UTF_8))) {
        x509Chain.add(CertificateUtils.toX509Certificate(bais));
      }
    }
    return x509Chain;
  }

  @VisibleForTesting
  class RefreshCertificateTask implements Runnable {
    @Override
    public void run() {
      try {
        refreshCertificate();
      } catch (NoSuchAlgorithmException | OperatorCreationException | IOException ex) {
        logger.log(Level.SEVERE, "refreshing certificate", ex);
      }
    }
  }

  /** Factory for creating channels to MeshCA sever. */
  abstract static class MeshCaChannelFactory {

    private static final MeshCaChannelFactory DEFAULT_INSTANCE =
        new MeshCaChannelFactory() {

          /** Creates a channel to the URL in the given list. */
          @Override
          ManagedChannel createChannel(String serverUri) {
            checkArgument(serverUri != null && !serverUri.isEmpty(), "serverUri is null/empty!");
            logger.log(Level.INFO, "Creating channel to {0}", serverUri);

            return Grpc.newChannelBuilder(serverUri, TlsChannelCredentials.create())
                .keepAliveTime(1, TimeUnit.MINUTES)
                .build();
          }
        };

    static MeshCaChannelFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /**
     * Creates a channel to the server.
     */
    abstract ManagedChannel createChannel(String serverUri);
  }

  /** Factory for creating channels to MeshCA sever. */
  abstract static class Factory {
    private static final Factory DEFAULT_INSTANCE =
        new Factory() {

          @Override
          MeshCaCertificateProvider create(
              DistributorWatcher watcher,
              boolean notifyCertUpdates,
              String meshCaUrl,
              String zone,
              long validitySeconds,
              int keySize,
              String alg,
              String signatureAlg,
              MeshCaChannelFactory meshCaChannelFactory,
              BackoffPolicy.Provider backoffPolicyProvider,
              long renewalGracePeriodSeconds,
              int maxRetryAttempts,
              GoogleCredentials oauth2Creds,
              ScheduledExecutorService scheduledExecutorService,
              TimeProvider timeProvider,
              long rpcTimeoutMillis) {
            return new MeshCaCertificateProvider(
                watcher,
                notifyCertUpdates,
                meshCaUrl,
                zone,
                validitySeconds,
                keySize,
                alg,
                signatureAlg,
                meshCaChannelFactory,
                backoffPolicyProvider,
                renewalGracePeriodSeconds,
                maxRetryAttempts,
                oauth2Creds,
                scheduledExecutorService,
                timeProvider,
                rpcTimeoutMillis);
          }
        };

    static Factory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract MeshCaCertificateProvider create(
        DistributorWatcher watcher,
        boolean notifyCertUpdates,
        String meshCaUrl,
        String zone,
        long validitySeconds,
        int keySize,
        String alg,
        String signatureAlg,
        MeshCaChannelFactory meshCaChannelFactory,
        BackoffPolicy.Provider backoffPolicyProvider,
        long renewalGracePeriodSeconds,
        int maxRetryAttempts,
        GoogleCredentials oauth2Creds,
        ScheduledExecutorService scheduledExecutorService,
        TimeProvider timeProvider,
        long rpcTimeoutMillis);
  }

  private class ZoneInfoClientInterceptor implements ClientInterceptor {
    private final String zone;

    ZoneInfoClientInterceptor(String zone) {
      this.zone = zone;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
          next.newCall(method, callOptions)) {

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          headers.put(KEY_FOR_ZONE_INFO, "location=locations/" + zone);
          super.start(responseListener, headers);
        }
      };
    }
  }

  @VisibleForTesting
  static final Metadata.Key<String> KEY_FOR_ZONE_INFO =
      Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);
  @VisibleForTesting
  static final long INITIAL_DELAY_SECONDS = 4L;

  private static final EnumSet<Status.Code> RETRIABLE_CODES =
      EnumSet.of(
          CANCELLED,
          UNKNOWN,
          DEADLINE_EXCEEDED,
          RESOURCE_EXHAUSTED,
          ABORTED,
          INTERNAL,
          UNAVAILABLE);

  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService scheduledExecutorService;
  private final int maxRetryAttempts;
  private final ZoneInfoClientInterceptor headerInterceptor;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final String meshCaUrl;
  private final long validitySeconds;
  private final long renewalGracePeriodSeconds;
  private final int keySize;
  private final String signatureAlg;
  private final GoogleCredentials oauth2Creds;
  private final TimeProvider timeProvider;
  private final MeshCaChannelFactory meshCaChannelFactory;
  @VisibleForTesting SynchronizationContext.ScheduledHandle scheduledHandle;
  private final long rpcTimeoutMillis;
}
