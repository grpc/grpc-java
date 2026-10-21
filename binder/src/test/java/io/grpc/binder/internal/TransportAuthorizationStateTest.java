/*
 * Copyright 2024 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.*;
import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Empty;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.binder.internal.BinderTransportSecurity.ServerPolicyChecker;
import io.grpc.binder.internal.BinderTransportSecurity.TransportAuthorizationState;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class TransportAuthorizationStateTest {

  private static final int UID = 12345;
  private static final String NONCODEGEN_SERVICE_NAME = "test.noncodegen.service";
  private static final MethodDescriptor<Empty, Empty> NONCODEGEN_METHOD =
      MethodDescriptor.<Empty, Empty>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(NONCODEGEN_SERVICE_NAME + "/NonCodegenMethod")
          .setRequestMarshaller(ProtoLiteUtils.marshaller(Empty.getDefaultInstance()))
          .setResponseMarshaller(ProtoLiteUtils.marshaller(Empty.getDefaultInstance()))
          .setSampledToLocalTracing(false)
          .build();

  private static final String CODEGEN_SERVICE_NAME = "test.codegen.service";
  private static final MethodDescriptor<Empty, Empty> CODEGEN_METHOD =
      MethodDescriptor.<Empty, Empty>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(CODEGEN_SERVICE_NAME + "/CodegenMethod")
          .setRequestMarshaller(ProtoLiteUtils.marshaller(Empty.getDefaultInstance()))
          .setResponseMarshaller(ProtoLiteUtils.marshaller(Empty.getDefaultInstance()))
          .setSampledToLocalTracing(true)
          .build();

  private ExecutorService executor;
  private FakeServerPolicyChecker fakePolicyChecker;
  private TransportAuthorizationState authState;

  @Before
  public void setUp() {
    executor = Executors.newSingleThreadExecutor();
    fakePolicyChecker = new FakeServerPolicyChecker();
    authState = new TransportAuthorizationState(UID, fakePolicyChecker, executor);
  }

  @After
  public void tearDown() throws Exception {
    assertThat(executor.shutdownNow()).isEmpty();
    assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
  }

  @Test
  public void checkAuthorization_deduplicatesSimultaneousNonCodegenMethods() throws Exception {
    ListenableFuture<Status> authResult1 = authState.checkAuthorization(NONCODEGEN_METHOD);
    assertThat(authResult1.isDone()).isFalse();

    ListenableFuture<Status> authResult2 = authState.checkAuthorization(NONCODEGEN_METHOD);
    assertThat(authResult2.isDone()).isFalse();

    // The fake policy checker was only invoked ONCE thanks to deduplication.
    // Completing that single underlying auth check satisfies both futures.
    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.OK);

    assertThat(authResult1.get()).isEqualTo(Status.OK);
    assertThat(authResult2.get()).isEqualTo(Status.OK);
    assertThat(fakePolicyChecker.statusesToSet).isEmpty();

    // Because it's a non-codegen method, the auth result should not be cached.
    ListenableFuture<Status> authResult3 = authState.checkAuthorization(NONCODEGEN_METHOD);
    assertThat(authResult3.isDone()).isFalse();

    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.PERMISSION_DENIED);
    assertThat(authResult3.get()).isEqualTo(Status.PERMISSION_DENIED);
  }

  @Test
  public void checkAuthorization_cachesSimultaneousCodegenMethods() throws Exception {
    ListenableFuture<Status> authResult1 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult1.isDone()).isFalse();

    ListenableFuture<Status> authResult2 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult2.isDone()).isFalse();

    // The fake policy checker was only invoked ONCE thanks to deduplication.
    // Completing that single underlying auth check satisfies both futures.
    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.OK);

    assertThat(authResult1.get()).isEqualTo(Status.OK);
    assertThat(authResult2.get()).isEqualTo(Status.OK);
    assertThat(fakePolicyChecker.statusesToSet).isEmpty();

    // Because it's a codegen method, the auth result should be cached for the life of the object.
    ListenableFuture<Status> authResult3 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult3.isDone()).isTrue();
    assertThat(authResult3.get()).isEqualTo(Status.OK);
    assertThat(fakePolicyChecker.statusesToSet).isEmpty();
  }

  @Test
  public void checkAuthorization_cachesPermissionDeniedForCodegenMethods() throws Exception {
    ListenableFuture<Status> authResult1 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult1.isDone()).isFalse();

    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.PERMISSION_DENIED);

    assertThat(authResult1.get()).isEqualTo(Status.PERMISSION_DENIED);
    assertThat(fakePolicyChecker.statusesToSet).isEmpty();

    // Because it's a codegen method, the non-OK status should be cached for the life of the object.
    ListenableFuture<Status> authResult2 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult2.isDone()).isTrue();
    assertThat(authResult2.get()).isEqualTo(Status.PERMISSION_DENIED);
    assertThat(fakePolicyChecker.statusesToSet).isEmpty();
  }

  @Test
  public void checkAuthorization_cancellation_doesNotPropagateToUnderlyingCheck() throws Exception {
    ListenableFuture<Status> authResult1 = authState.checkAuthorization(CODEGEN_METHOD);
    ListenableFuture<Status> authResult2 = authState.checkAuthorization(CODEGEN_METHOD);

    // Cancel the first future.
    authResult1.cancel(true);

    assertThat(authResult1.isCancelled()).isTrue();
    // The second future should NOT be cancelled, because the cancellation shouldn't propagate
    // to the underlying shared future.
    assertThat(authResult2.isCancelled()).isFalse();

    // Completing the underlying auth check satisfies the non-cancelled future.
    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.OK);

    assertThat(authResult2.get()).isEqualTo(Status.OK);
  }

  @Test
  public void checkAuthorization_failedFuture_notCached() throws Exception {
    ListenableFuture<Status> authResult1 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult1.isDone()).isFalse();

    fakePolicyChecker.takeNextAuthRequestOrDie().setException(new IllegalStateException("oops"));
    
    ExecutionException exception = assertThrows(ExecutionException.class, authResult1::get);
    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(fakePolicyChecker.statusesToSet).isEmpty();

    // Failed futures must not be cached, even for codegen methods.
    ListenableFuture<Status> authResult2 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult2.isDone()).isFalse();
    
    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.OK);
    assertThat(authResult2.get()).isEqualTo(Status.OK);
  }

  @Test
  public void notifyTerminatedUnlocked_cancelsPendingAuthFutures() {
    ListenableFuture<Status> authResult = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult.isDone()).isFalse();

    authState.notifyTerminatedUnlocked();

    assertThat(authResult.isCancelled()).isTrue();
  }

  @Test
  public void checkAuthorization_afterTermination_returnsCancelledFuture() {
    authState.notifyTerminatedUnlocked();

    ListenableFuture<Status> authResult = authState.checkAuthorization(CODEGEN_METHOD);

    assertThat(authResult.isCancelled()).isTrue();
    assertThat(fakePolicyChecker.statusesToSet).isEmpty(); // fakePolicyChecker never called.
  }

  @Test
  public void checkAuthorization_synchronousException_doesNotLeaveStrandedFuture()
      throws Exception {
    IllegalStateException syncException = new IllegalStateException("ouch");
    fakePolicyChecker.syncExceptionsToThrow.add(syncException);

    // The synchronous exception is safely returned as a failed future.
    ListenableFuture<Status> authResult1 = authState.checkAuthorization(CODEGEN_METHOD);
    ExecutionException ee = assertThrows(ExecutionException.class, authResult1::get);
    assertThat(ee).hasCauseThat().isSameInstanceAs(syncException);

    // Ensure the failed check can be retried.
    ListenableFuture<Status> authResult2 = authState.checkAuthorization(CODEGEN_METHOD);
    assertThat(authResult2.isDone()).isFalse();

    fakePolicyChecker.takeNextAuthRequestOrDie().set(Status.OK);
    assertThat(authResult2.get()).isEqualTo(Status.OK);
  }

  private static final class FakeServerPolicyChecker implements ServerPolicyChecker {
    final LinkedBlockingQueue<RuntimeException> syncExceptionsToThrow = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<SettableFuture<Status>> statusesToSet = new LinkedBlockingQueue<>();

    @Override
    public ListenableFuture<Status> checkAuthorizationForServiceAsync(int uid, String serviceName) {
      RuntimeException syncException = syncExceptionsToThrow.poll();
      if (syncException != null) {
        throw syncException;
      }
      SettableFuture<Status> pendingResult = SettableFuture.create();
      statusesToSet.add(pendingResult);
      return pendingResult;
    }

    SettableFuture<Status> takeNextAuthRequestOrDie() throws InterruptedException {
      SettableFuture<Status> item = statusesToSet.poll(10, SECONDS);
      if (item == null) {
        throw new NoSuchElementException("Queue timed out waiting for item");
      }
      return item;
    }
  }
}
