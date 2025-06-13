/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Status;
import io.grpc.binder.AsyncSecurityPolicy;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link AsyncSecurityPolicy} that lets unit tests verify the exact order of authorization
 * requests and respond to them one at a time.
 */
public class SettableAsyncSecurityPolicy extends AsyncSecurityPolicy {
  private final LinkedBlockingDeque<AuthRequest> pendingRequests = new LinkedBlockingDeque<>();

  @Override
  public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
    AuthRequest request = new AuthRequest(uid);
    pendingRequests.add(request);
    return request.resultFuture;
  }

  /**
   * Waits for the next "check authorization" request to be made and returns it, throwing in case no
   * request arrives in time.
   */
  public AuthRequest takeNextAuthRequest(long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {
    AuthRequest nextAuthRequest = pendingRequests.poll(timeout, unit);
    if (nextAuthRequest == null) {
      throw new TimeoutException();
    }
    return nextAuthRequest;
  }

  /** Represents a single call to {@link AsyncSecurityPolicy#checkAuthorizationAsync(int)}. */
  public static class AuthRequest {

    /** The argument passed to {@link AsyncSecurityPolicy#checkAuthorizationAsync(int)}. */
    public final int uid;

    private final SettableFuture<Status> resultFuture = SettableFuture.create();

    private AuthRequest(int uid) {
      this.uid = uid;
    }

    /** Provides this SecurityPolicy's response to this authorization request. */
    public void setResult(Status result) {
      checkState(resultFuture.set(result));
    }

    /** Simulates an exceptional response to this authorization request. */
    public void setResult(Throwable t) {
      checkState(resultFuture.setException(t));
    }
  }
}
