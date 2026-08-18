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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import java.util.concurrent.Executor;

/** Static factory methods for creating asynchronous security policies. */
@CheckReturnValue
public final class AsyncSecurityPolicies {

  private AsyncSecurityPolicies() {}

  /**
   * Returns an {@link AsyncSecurityPolicy} that delegates to some other policy that's provided
   * lazily and asynchronously.
   *
   * <p>Use this when your security policy is slow or expensive to load and not immediately
   * available at Channel or Server initialization. It's particularly useful in {@code
   * android.app.Service#onCreate()} where blocking the main thread to load a server's security
   * policy risks an "Application Not Responding" (ANR) error. Implementations of 'policyProvider'
   * must not block the calling thread either.
   *
   * <p>The provided {@link AsyncCallable} is invoked each time the returned policy is evaluated.
   * This happens once per connection for a grpc-binder Channel and once per (service, incoming
   * connection) called on a Server. So 'policyProvider' must be prepared to be invoked more than
   * once but not normally for every RPC. Depending on the cost of loading the policy, a provider
   * may want to memoize and reuse its products.
   *
   * <p>Binder Channels and Servers try to coalesce multiple identical authorization checks that
   * overlap in time but this isn't guaranteed. So 'policyProvider' must be thread-safe but may or
   * may not go to the trouble of coalescing simultaneous calls for itself. Those that do should use
   * {@link Futures#nonCancellationPropagating} or similar to protect a future returned to multiple
   * callers from individual cancellation.
   *
   * <p>'policyProvider' can express the failure to load a security policy by returning a failed
   * future. This failure will propagate to the Server or Channel operation that needed authorizing,
   * but will not be cached, leaving open the possibility of success upon retry. A memoizing policy
   * provider may want to retain only successes for the same reason.
   *
   * @param policyProvider used to get the delegate SecurityPolicy when needed
   * @param executor used to call into the delegate once provided. If the delegate is a
   *     SecurityPolicy, note that many implementations of checkAuthorization() block.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public static <T extends SecurityPolicy> AsyncSecurityPolicy deferred(
      AsyncCallable<T> policyProvider, Executor executor) {
    checkNotNull(policyProvider, "policyProvider");
    checkNotNull(executor, "executor");
    return new AsyncSecurityPolicy() {
      @Override
      public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
        try {
          return Futures.transformAsync(
              policyProvider.call(),
              policy -> {
                checkNotNull(policy, "policyProvider returned a null SecurityPolicy");
                if (policy instanceof AsyncSecurityPolicy) {
                  return ((AsyncSecurityPolicy) policy).checkAuthorizationAsync(uid);
                }
                // This may block but 'executor' must be prepared for that.
                return Futures.immediateFuture(policy.checkAuthorization(uid));
              },
              executor);
        } catch (Exception e) {
          return Futures.immediateFailedFuture(e);
        }
      }
    };
  }
}
