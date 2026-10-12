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

package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Status;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class AsyncSecurityPoliciesTest {

  private static final int SOME_UID = 10001;

  private ExecutorService executor;

  @Before
  public void setUp() {
    executor = Executors.newSingleThreadExecutor();
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void testDeferred_asyncPolicy_succeeds() throws Exception {
    SettableFuture<AsyncSecurityPolicy> futurePolicy = SettableFuture.create();
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> futurePolicy, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(authFuture.isDone()).isFalse();

    AsyncSecurityPolicy delegatePolicy =
        asyncPolicyReturning(Status.OK.withDescription("yay"));

    futurePolicy.set(delegatePolicy);

    Status status = awaitResult(authFuture);
    assertThat(status.getCode()).isEqualTo(Status.Code.OK);
    assertThat(status.getDescription()).isEqualTo("yay");
  }

  @Test
  public void testDeferred_asyncPolicy_permissionDenied() throws Exception {
    SettableFuture<AsyncSecurityPolicy> futurePolicy = SettableFuture.create();
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> futurePolicy, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(authFuture.isDone()).isFalse();

    AsyncSecurityPolicy delegatePolicy =
        asyncPolicyReturning(Status.PERMISSION_DENIED.withDescription("no!"));

    futurePolicy.set(delegatePolicy);

    Status status = awaitResult(authFuture);
    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status.getDescription()).isEqualTo("no!");
  }

  @Test
  public void testDeferred_asyncPolicy_throwsException() throws Exception {
    AsyncSecurityPolicy delegatePolicy =
        new AsyncSecurityPolicy() {
          @Override
          public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
            throw new RuntimeException("async checkAuthorization failed");
          }
        };
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> immediateFuture(delegatePolicy), executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> awaitResult(authFuture));
    assertThat(e).hasCauseThat().isInstanceOf(RuntimeException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("async checkAuthorization failed");
  }

  @Test
  public void testDeferred_asyncPolicy_forwardsUidToDelegate() throws Exception {
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(
            () -> immediateFuture(asyncUniqueAuthorizedUidPolicy(11111)), executor);

    assertThat(awaitResult(asyncPolicy.checkAuthorizationAsync(11111)).getCode())
        .isEqualTo(Status.Code.OK);
    assertThat(awaitResult(asyncPolicy.checkAuthorizationAsync(22222)).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  public void testDeferred_policyProviderReturnsFailedFuture() throws Exception {
    SettableFuture<AsyncSecurityPolicy> futurePolicy = SettableFuture.create();
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> futurePolicy, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(authFuture.isDone()).isFalse();

    Exception exception = new RuntimeException("failed to load policy");
    futurePolicy.setException(exception);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> awaitResult(authFuture));
    assertThat(e).hasCauseThat().isEqualTo(exception);
  }

  @Test
  public void testDeferred_policyProviderThrowsException() throws Exception {
    Exception exception = new RuntimeException("ouch");
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(
            () -> {
              throw exception;
            },
            executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(authFuture.isDone()).isTrue();

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> awaitResult(authFuture));
    assertThat(e).hasCauseThat().isEqualTo(exception);
  }

  @Test
  public void testDeferred_policyProviderReturnsNullFuture_returnsFailedFuture()
      throws Exception {
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> null, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> awaitResult(authFuture));
    assertThat(e).hasCauseThat().isInstanceOf(NullPointerException.class);
  }

  @Test
  public void testDeferred_policyProviderResolvesToNullPolicy_returnsFailedFuture()
      throws Exception {
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> immediateFuture(null), executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> awaitResult(authFuture));
    assertThat(e).hasCauseThat().isInstanceOf(NullPointerException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("policyProvider");
  }

  @Test
  public void testDeferred_syncPolicy_succeeds() throws Exception {
    SettableFuture<SecurityPolicy> futurePolicy = SettableFuture.create();
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> futurePolicy, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(authFuture.isDone()).isFalse();

    SecurityPolicy delegatePolicy =
        syncPolicyReturning(Status.OK.withDescription("yay"));

    futurePolicy.set(delegatePolicy);

    Status status = awaitResult(authFuture);
    assertThat(status.getCode()).isEqualTo(Status.Code.OK);
    assertThat(status.getDescription()).isEqualTo("yay");
  }

  @Test
  public void testDeferred_syncPolicy_fails() throws Exception {
    SettableFuture<SecurityPolicy> futurePolicy = SettableFuture.create();
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> futurePolicy, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(authFuture.isDone()).isFalse();

    SecurityPolicy delegatePolicy =
        syncPolicyReturning(Status.PERMISSION_DENIED.withDescription("no!"));

    futurePolicy.set(delegatePolicy);

    Status status = awaitResult(authFuture);
    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status.getDescription()).isEqualTo("no!");
  }

  @Test
  public void testDeferred_syncPolicy_throwsException() throws Exception {
    SecurityPolicy delegatePolicy =
        new SecurityPolicy() {
          @Override
          public Status checkAuthorization(int uid) {
            throw new RuntimeException("checkAuthorization failed");
          }
        };
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> immediateFuture(delegatePolicy), executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> awaitResult(authFuture));
    assertThat(e).hasCauseThat().isInstanceOf(RuntimeException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("checkAuthorization failed");
  }

  @Test
  public void testDeferred_syncPolicy_forwardsUidToDelegate() throws Exception {
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(
            () -> immediateFuture(uniqueAuthorizedUidPolicy(11111)), executor);

    assertThat(awaitResult(asyncPolicy.checkAuthorizationAsync(11111)).getCode())
        .isEqualTo(Status.Code.OK);
    assertThat(awaitResult(asyncPolicy.checkAuthorizationAsync(22222)).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  public void testDeferred_cancellationPropagatesToProvider() {
    SettableFuture<SecurityPolicy> futurePolicy = SettableFuture.create();
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> futurePolicy, executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    authFuture.cancel(true);

    assertThat(futurePolicy.isCancelled()).isTrue();
  }

  @Test
  public void testDeferred_cancellationPropagatesToAsyncDelegate() throws Exception {
    SettableFuture<Status> delegateAuthFuture = SettableFuture.create();
    SettableFuture<Integer> settableUid = SettableFuture.create();
    AsyncSecurityPolicy delegatePolicy =
        new AsyncSecurityPolicy() {
          @Override
          public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
            settableUid.set(uid);
            return delegateAuthFuture;
          }
        };
    AsyncSecurityPolicy asyncPolicy =
        AsyncSecurityPolicies.deferred(() -> immediateFuture(delegatePolicy), executor);

    ListenableFuture<Status> authFuture = asyncPolicy.checkAuthorizationAsync(SOME_UID);
    assertThat(awaitResult(settableUid)).isEqualTo(SOME_UID);
    authFuture.cancel(false);
    executor.submit(() -> {}).get(10, TimeUnit.SECONDS);

    assertThat(delegateAuthFuture.isCancelled()).isTrue();
  }

  @Test
  public void testDeferred_nullProvider_throwsException() {
    NullPointerException e =
        assertThrows(
            NullPointerException.class, () -> AsyncSecurityPolicies.deferred(null, executor));
    assertThat(e).hasMessageThat().contains("policyProvider");
  }

  private static <T> T awaitResult(Future<T> future) throws Exception {
    return future.get(10, TimeUnit.SECONDS);
  }

  private static SecurityPolicy syncPolicyReturning(Status status) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return status;
      }
    };
  }

  private static AsyncSecurityPolicy asyncPolicyReturning(Status status) {
    return new AsyncSecurityPolicy() {
      @Override
      public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
        return immediateFuture(status);
      }
    };
  }

  private static SecurityPolicy uniqueAuthorizedUidPolicy(int authorizedUid) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return uid == authorizedUid
            ? Status.OK
            : Status.PERMISSION_DENIED.withDescription("unauthorized uid: " + uid);
      }
    };
  }

  private static AsyncSecurityPolicy asyncUniqueAuthorizedUidPolicy(int authorizedUid) {
    return new AsyncSecurityPolicy() {
      @Override
      public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
        return immediateFuture(
            uid == authorizedUid
                ? Status.OK
                : Status.PERMISSION_DENIED.withDescription("unauthorized uid: " + uid));
      }
    };
  }
}
