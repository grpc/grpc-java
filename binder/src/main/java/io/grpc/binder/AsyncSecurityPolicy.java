/*
 * Copyright 2023 The gRPC Authors
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

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.annotation.CheckReturnValue;

/**
 * Decides whether a given Android UID is authorized to access some resource.
 *
 * <p>This class provides the asynchronous version of {@link SecurityPolicy}, allowing
 * implementations of authorization logic that involves slow or asynchronous calls without
 * necessarily blocking the calling thread.
 *
 * @see SecurityPolicy
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10566")
@CheckReturnValue
public abstract class AsyncSecurityPolicy extends SecurityPolicy {

  /**
   * @deprecated Prefer {@link #checkAuthorizationAsync(int)} for async or slow calls or subclass
   *     {@link SecurityPolicy} directly for quick, synchronous implementations.
   */
  @Override
  @Deprecated
  public final Status checkAuthorization(int uid) {
    try {
      return checkAuthorizationAsync(uid).get();
    } catch (ExecutionException e) {
      return Status.fromThrowable(e);
    } catch (CancellationException e) {
      return Status.CANCELLED.withCause(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // re-set the current thread's interruption state
      return Status.CANCELLED.withCause(e);
    }
  }

  /**
   * Decides whether the given Android UID is authorized. (Validity is implementation dependent).
   *
   * <p>As long as any given UID has active processes, this method should return the same value for
   * that UID. In order words, policy changes which occur while a transport instance is active, will
   * have no effect on that transport instance.
   *
   * @param uid The Android UID to authenticate.
   * @return A {@link ListenableFuture} for a gRPC {@link Status} object, with OK indicating
   *     authorized.
   */
  public abstract ListenableFuture<Status> checkAuthorizationAsync(int uid);
}
