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
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_0_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.auth.http.AuthHttpConstants;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.security.meshca.v1.MeshCertificateRequest;
import com.google.security.meshca.v1.MeshCertificateResponse;
import com.google.security.meshca.v1.MeshCertificateServiceGrpc;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.internal.certprovider.CertificateProvider.DistributorWatcher;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link MeshCaCertificateProvider}. */
@RunWith(JUnit4.class)
public class MeshCaCertificateProviderTest {

  private static final String TEST_STS_TOKEN = "test-stsToken";
  private static final long RENEWAL_GRACE_PERIOD_SECONDS = TimeUnit.HOURS.toSeconds(1L);
  private static final Metadata.Key<String> KEY_FOR_AUTHORIZATION =
      Metadata.Key.of(AuthHttpConstants.AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER);
  private static final String ZONE = "us-west2-a";
  private static final long START_DELAY = 200_000_000L;  // 0.2 seconds
  private static final long[] DELAY_VALUES = {START_DELAY, START_DELAY * 2, START_DELAY * 4};
  private static final long RPC_TIMEOUT_MILLIS = 1000L;
  /**
   * Expire time of cert SERVER_0_PEM_FILE.
   */
  static final long CERT0_EXPIRY_TIME_MILLIS = 1899853658000L;
  /**
   * Cert validity of 12 hours for the above cert.
   */
  private static final long CERT0_VALIDITY_MILLIS = TimeUnit.MILLISECONDS
      .convert(12, TimeUnit.HOURS);
  /**
   * Compute current time based on cert expiry and cert validity.
   */
  private static final long CURRENT_TIME_NANOS =
      TimeUnit.MILLISECONDS.toNanos(CERT0_EXPIRY_TIME_MILLIS - CERT0_VALIDITY_MILLIS);
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static class ResponseToSend {
    Throwable getThrowable() {
      throw new UnsupportedOperationException("Called on " + getClass().getCanonicalName());
    }

    List<String> getList() {
      throw new UnsupportedOperationException("Called on " + getClass().getCanonicalName());
    }
  }

  private static class ResponseThrowable extends ResponseToSend {
    final Throwable throwableToSend;

    ResponseThrowable(Throwable throwable) {
      throwableToSend = throwable;
    }

    @Override
    Throwable getThrowable() {
      return throwableToSend;
    }
  }

  private static class ResponseList extends ResponseToSend {
    final List<String> listToSend;

    ResponseList(List<String> list) {
      listToSend = list;
    }

    @Override
    List<String> getList() {
      return listToSend;
    }
  }

  private final Queue<MeshCertificateRequest> receivedRequests = new ArrayDeque<>();
  private final Queue<String> receivedStsCreds = new ArrayDeque<>();
  private final Queue<String> receivedZoneValues = new ArrayDeque<>();
  private final Queue<ResponseToSend> responsesToSend = new ArrayDeque<>();
  private final Queue<String> oauth2Tokens = new ArrayDeque<>();
  private final AtomicBoolean callEnded = new AtomicBoolean(true);

  @Mock private MeshCertificateServiceGrpc.MeshCertificateServiceImplBase mockedMeshCaService;
  @Mock private CertificateProvider.Watcher mockWatcher;
  @Mock private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock private BackoffPolicy backoffPolicy;
  @Spy private GoogleCredentials oauth2Creds;
  @Mock private ScheduledExecutorService timeService;
  @Mock private TimeProvider timeProvider;

  private ManagedChannel channel;
  private MeshCaCertificateProvider provider;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy);
    when(backoffPolicy.nextBackoffNanos())
        .thenReturn(DELAY_VALUES[0], DELAY_VALUES[1], DELAY_VALUES[2]);
    doAnswer(
        new Answer<AccessToken>() {
          @Override
          public AccessToken answer(InvocationOnMock invocation) throws Throwable {
            return new AccessToken(
                oauth2Tokens.poll(), new Date(System.currentTimeMillis() + 1000L));
          }
        })
        .when(oauth2Creds)
        .refreshAccessToken();
    final String meshCaUri = InProcessServerBuilder.generateName();
    MeshCertificateServiceGrpc.MeshCertificateServiceImplBase meshCaServiceImpl =
        new MeshCertificateServiceGrpc.MeshCertificateServiceImplBase() {

          @Override
          public void createCertificate(
              MeshCertificateRequest request,
              StreamObserver<MeshCertificateResponse> responseObserver) {
            assertThat(callEnded.get()).isTrue(); // ensure previous call was ended
            callEnded.set(false);
            Context.current()
                .addListener(
                    new Context.CancellationListener() {
                      @Override
                      public void cancelled(Context context) {
                        callEnded.set(true);
                      }
                    },
                    MoreExecutors.directExecutor());
            receivedRequests.offer(request);
            ResponseToSend response = responsesToSend.poll();
            if (response instanceof ResponseThrowable) {
              responseObserver.onError(response.getThrowable());
            } else if (response instanceof ResponseList) {
              List<String> certChainInResponse = response.getList();
              MeshCertificateResponse responseToSend =
                  MeshCertificateResponse.newBuilder()
                      .addAllCertChain(certChainInResponse)
                      .build();
              responseObserver.onNext(responseToSend);
              responseObserver.onCompleted();
            } else {
              callEnded.set(true);
            }
          }
        };
    mockedMeshCaService =
        mock(
            MeshCertificateServiceGrpc.MeshCertificateServiceImplBase.class,
            delegatesTo(meshCaServiceImpl));
    ServerInterceptor interceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedStsCreds.offer(headers.get(KEY_FOR_AUTHORIZATION));
            receivedZoneValues.offer(headers.get(MeshCaCertificateProvider.KEY_FOR_ZONE_INFO));
            return next.startCall(call, headers);
          }
        };
    cleanupRule.register(
        InProcessServerBuilder.forName(meshCaUri)
            .addService(mockedMeshCaService)
            .intercept(interceptor)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(meshCaUri).directExecutor().build());
    MeshCaCertificateProvider.MeshCaChannelFactory channelFactory =
        new MeshCaCertificateProvider.MeshCaChannelFactory() {
          @Override
          ManagedChannel createChannel(String serverUri) {
            assertThat(serverUri).isEqualTo(meshCaUri);
            return channel;
          }
        };
    CertificateProvider.DistributorWatcher watcher = new CertificateProvider.DistributorWatcher();
    watcher.addWatcher(mockWatcher); //
    provider =
        new MeshCaCertificateProvider(
            watcher,
            true,
            meshCaUri,
            ZONE,
            TimeUnit.HOURS.toSeconds(9L),
            2048,
            "RSA",
            "SHA256withRSA",
            channelFactory,
            backoffPolicyProvider,
            RENEWAL_GRACE_PERIOD_SECONDS,
            MeshCaCertificateProviderProvider.MAX_RETRY_ATTEMPTS_DEFAULT,
            oauth2Creds,
            timeService,
            timeProvider,
            RPC_TIMEOUT_MILLIS);
  }

  @Test
  public void startAndClose() {
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.start();
    SynchronizationContext.ScheduledHandle savedScheduledHandle = provider.scheduledHandle;
    assertThat(savedScheduledHandle).isNotNull();
    assertThat(savedScheduledHandle.isPending()).isTrue();
    verify(timeService, times(1))
        .schedule(
            any(Runnable.class),
            eq(MeshCaCertificateProvider.INITIAL_DELAY_SECONDS),
            eq(TimeUnit.SECONDS));
    DistributorWatcher distWatcher = provider.getWatcher();
    assertThat(distWatcher.downstreamWatchers).hasSize(1);
    PrivateKey mockKey = mock(PrivateKey.class);
    X509Certificate mockCert = mock(X509Certificate.class);
    distWatcher.updateCertificate(mockKey, ImmutableList.of(mockCert));
    distWatcher.updateTrustedRoots(ImmutableList.of(mockCert));
    provider.close();
    assertThat(provider.scheduledHandle).isNull();
    assertThat(savedScheduledHandle.isPending()).isFalse();
    assertThat(distWatcher.downstreamWatchers).isEmpty();
    assertThat(distWatcher.getLastIdentityCert()).isNull();
  }

  @Test
  public void startTwice_noException() {
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.start();
    SynchronizationContext.ScheduledHandle savedScheduledHandle1 = provider.scheduledHandle;
    provider.start();
    SynchronizationContext.ScheduledHandle savedScheduledHandle2 = provider.scheduledHandle;
    assertThat(savedScheduledHandle2).isNotSameInstanceAs(savedScheduledHandle1);
    assertThat(savedScheduledHandle2.isPending()).isTrue();
  }

  @Test
  public void getCertificate()
      throws IOException, CertificateException, OperatorCreationException,
      NoSuchAlgorithmException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend.offer(
        new ResponseList(ImmutableList.of(
            CommonTlsContextTestsUtil.getResourceContents(SERVER_0_PEM_FILE),
            CommonTlsContextTestsUtil.getResourceContents(SERVER_1_PEM_FILE),
            CommonTlsContextTestsUtil.getResourceContents(CA_PEM_FILE))));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    MeshCertificateRequest receivedReq = receivedRequests.poll();
    assertThat(receivedReq.getValidity().getSeconds()).isEqualTo(TimeUnit.HOURS.toSeconds(9L));
    // cannot decode CSR: just check the PEM format delimiters
    String csr = receivedReq.getCsr();
    assertThat(csr).startsWith("-----BEGIN NEW CERTIFICATE REQUEST-----");
    verifyReceivedMetadataValues(1);
    verify(timeService, times(1))
        .schedule(
            any(Runnable.class),
            eq(
                TimeUnit.MILLISECONDS.toSeconds(
                    CERT0_VALIDITY_MILLIS
                        - TimeUnit.SECONDS.toMillis(RENEWAL_GRACE_PERIOD_SECONDS))),
            eq(TimeUnit.SECONDS));
    verifyMockWatcher();
  }

  @Test
  public void getCertificate_withError()
      throws IOException, OperatorCreationException, NoSuchAlgorithmException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend
        .offer(new ResponseThrowable(new StatusRuntimeException(Status.FAILED_PRECONDITION)));
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    verify(mockWatcher, never())
        .updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, times(1)).onError(Status.FAILED_PRECONDITION);
    verify(timeService, times(1)).schedule(any(Runnable.class),
        eq(MeshCaCertificateProvider.INITIAL_DELAY_SECONDS),
        eq(TimeUnit.SECONDS));
    verifyReceivedMetadataValues(1);
  }

  @Test
  public void getCertificate_withError_withExistingCert()
      throws IOException, OperatorCreationException, NoSuchAlgorithmException {
    PrivateKey mockKey = mock(PrivateKey.class);
    X509Certificate mockCert = mock(X509Certificate.class);
    // have current cert expire in 3 hours from current time
    long threeHoursFromNowMillis = TimeUnit.NANOSECONDS
        .toMillis(CURRENT_TIME_NANOS + TimeUnit.HOURS.toNanos(3));
    when(mockCert.getNotAfter()).thenReturn(new Date(threeHoursFromNowMillis));
    provider.getWatcher().updateCertificate(mockKey, ImmutableList.of(mockCert));
    reset(mockWatcher);
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend
        .offer(new ResponseThrowable(new StatusRuntimeException(Status.FAILED_PRECONDITION)));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    verify(mockWatcher, never())
        .updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).onError(any(Status.class));
    verify(timeService, times(1)).schedule(any(Runnable.class),
        eq(5400L),
        eq(TimeUnit.SECONDS));
    assertThat(provider.getWatcher().getLastIdentityCert()).isNotNull();
    verifyReceivedMetadataValues(1);
  }

  @Test
  public void getCertificate_withError_withExistingExpiredCert()
      throws IOException, OperatorCreationException, NoSuchAlgorithmException {
    PrivateKey mockKey = mock(PrivateKey.class);
    X509Certificate mockCert = mock(X509Certificate.class);
    // have current cert expire in 3 seconds from current time
    long threeSecondsFromNowMillis = TimeUnit.NANOSECONDS
        .toMillis(CURRENT_TIME_NANOS + TimeUnit.SECONDS.toNanos(3));
    when(mockCert.getNotAfter()).thenReturn(new Date(threeSecondsFromNowMillis));
    provider.getWatcher().updateCertificate(mockKey, ImmutableList.of(mockCert));
    reset(mockWatcher);
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend
        .offer(new ResponseThrowable(new StatusRuntimeException(Status.FAILED_PRECONDITION)));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    verify(mockWatcher, never())
        .updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, times(1)).onError(Status.FAILED_PRECONDITION);
    verify(timeService, times(1)).schedule(any(Runnable.class),
        eq(MeshCaCertificateProvider.INITIAL_DELAY_SECONDS),
        eq(TimeUnit.SECONDS));
    assertThat(provider.getWatcher().getLastIdentityCert()).isNull();
    verifyReceivedMetadataValues(1);
  }

  @Test
  public void getCertificate_retriesWithErrors()
      throws IOException, CertificateException, OperatorCreationException,
      NoSuchAlgorithmException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    oauth2Tokens.offer(TEST_STS_TOKEN + "1");
    oauth2Tokens.offer(TEST_STS_TOKEN + "2");
    responsesToSend.offer(new ResponseThrowable(new StatusRuntimeException(Status.UNKNOWN)));
    responsesToSend.offer(
        new ResponseThrowable(
            new Exception(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED))));
    responsesToSend.offer(new ResponseList(ImmutableList.of(
        CommonTlsContextTestsUtil.getResourceContents(SERVER_0_PEM_FILE),
        CommonTlsContextTestsUtil.getResourceContents(SERVER_1_PEM_FILE),
        CommonTlsContextTestsUtil.getResourceContents(CA_PEM_FILE))));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    TestScheduledFuture<?> scheduledFutureSleep = new TestScheduledFuture<>();
    doReturn(scheduledFutureSleep).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.NANOSECONDS));
    provider.refreshCertificate();
    assertThat(receivedRequests.size()).isEqualTo(3);
    verify(timeService, times(1)).schedule(any(Runnable.class),
        eq(TimeUnit.MILLISECONDS.toSeconds(
            CERT0_VALIDITY_MILLIS - TimeUnit.SECONDS.toMillis(RENEWAL_GRACE_PERIOD_SECONDS))),
        eq(TimeUnit.SECONDS));
    verifyRetriesWithBackoff(scheduledFutureSleep, 2);
    verifyMockWatcher();
    verifyReceivedMetadataValues(3);
  }

  @Test
  public void getCertificate_retriesWithTimeouts()
      throws IOException, CertificateException, OperatorCreationException,
      NoSuchAlgorithmException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    oauth2Tokens.offer(TEST_STS_TOKEN + "1");
    oauth2Tokens.offer(TEST_STS_TOKEN + "2");
    oauth2Tokens.offer(TEST_STS_TOKEN + "3");
    responsesToSend.offer(new ResponseToSend());
    responsesToSend.offer(new ResponseToSend());
    responsesToSend.offer(new ResponseToSend());
    responsesToSend.offer(new ResponseList(ImmutableList.of(
        CommonTlsContextTestsUtil.getResourceContents(SERVER_0_PEM_FILE),
        CommonTlsContextTestsUtil.getResourceContents(SERVER_1_PEM_FILE),
        CommonTlsContextTestsUtil.getResourceContents(CA_PEM_FILE))));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    TestScheduledFuture<?> scheduledFuture = new TestScheduledFuture<>();
    doReturn(scheduledFuture).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    TestScheduledFuture<?> scheduledFutureSleep = new TestScheduledFuture<>();
    doReturn(scheduledFutureSleep).when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.NANOSECONDS));
    provider.refreshCertificate();
    assertThat(receivedRequests.size()).isEqualTo(4);
    verify(timeService, times(1)).schedule(any(Runnable.class),
        eq(TimeUnit.MILLISECONDS.toSeconds(
            CERT0_VALIDITY_MILLIS - TimeUnit.SECONDS.toMillis(RENEWAL_GRACE_PERIOD_SECONDS))),
        eq(TimeUnit.SECONDS));
    verifyRetriesWithBackoff(scheduledFutureSleep, 3);
    verifyMockWatcher();
    verifyReceivedMetadataValues(4);
  }

  private void verifyRetriesWithBackoff(
      TestScheduledFuture<?> scheduledFutureSleep, int numOfRetries) {
    for (int i = 0; i < numOfRetries; i++) {
      long delayValue = DELAY_VALUES[i];
      verify(timeService, times(1)).schedule(any(Runnable.class),
          eq(delayValue),
          eq(TimeUnit.NANOSECONDS));
      assertThat(scheduledFutureSleep.calls.get(i).timeout).isEqualTo(delayValue);
      assertThat(scheduledFutureSleep.calls.get(i).unit).isEqualTo(TimeUnit.NANOSECONDS);
    }
  }

  private void verifyMockWatcher() throws IOException, CertificateException {
    ArgumentCaptor<List<X509Certificate>> certChainCaptor = ArgumentCaptor.forClass(null);
    verify(mockWatcher, times(1))
        .updateCertificate(any(PrivateKey.class), certChainCaptor.capture());
    List<X509Certificate> certChain = certChainCaptor.getValue();
    assertThat(certChain).hasSize(3);
    assertThat(certChain.get(0))
        .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(SERVER_0_PEM_FILE));
    assertThat(certChain.get(1))
        .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(SERVER_1_PEM_FILE));
    assertThat(certChain.get(2))
        .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(CA_PEM_FILE));

    ArgumentCaptor<List<X509Certificate>> rootsCaptor = ArgumentCaptor.forClass(null);
    verify(mockWatcher, times(1)).updateTrustedRoots(rootsCaptor.capture());
    List<X509Certificate> roots = rootsCaptor.getValue();
    assertThat(roots).hasSize(1);
    assertThat(roots.get(0))
        .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(CA_PEM_FILE));
    verify(mockWatcher, never()).onError(any(Status.class));
  }

  private void verifyReceivedMetadataValues(int count) {
    assertThat(receivedStsCreds).hasSize(count);
    assertThat(receivedZoneValues).hasSize(count);
    for (int i = 0; i < count; i++) {
      assertThat(receivedStsCreds.poll()).isEqualTo("Bearer " + TEST_STS_TOKEN + i);
      assertThat(receivedZoneValues.poll()).isEqualTo("location=locations/us-west2-a");
    }
  }

  static class TestScheduledFuture<V> implements ScheduledFuture<V> {

    static class Record {
      long timeout;
      TimeUnit unit;

      Record(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.unit = unit;
      }
    }

    ArrayList<Record> calls = new ArrayList<>();

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public V get() {
      return null;
    }

    @Override
    public V get(long timeout, TimeUnit unit) {
      calls.add(new Record(timeout, unit));
      return null;
    }
  }
}
