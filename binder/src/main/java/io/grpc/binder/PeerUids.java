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

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.pm.PackageManager;
import android.os.Build.VERSION_CODES;
import android.os.UserHandle;
import androidx.annotation.RequiresApi;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ExperimentalApi;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.binder.internal.BinderTransport;
import javax.annotation.Nullable;

/** Static methods that operate on {@link PeerUid}. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class PeerUids {
  /**
   * The client's authentic identity will be stored under this key in the server {@link Context}.
   *
   * <p>In order for this key to be populated, the interceptor returned by {@link
   * #newPeerIdentifyingServerInterceptor} must be attached to the service. Note that the Context
   * must be propagated correctly across threads for this key to be populated when read from other
   * threads.
   */
  public static final Context.Key<PeerUid> REMOTE_PEER = Context.key("remote-peer");

  /**
   * Returns package names associated with the given peer's uid according to {@link
   * PackageManager#getPackagesForUid(int)}.
   *
   * <p><em>WARNING</em>: Apps installed from untrusted sources can set any package name they want.
   * Don't depend on package names for security -- use {@link SecurityPolicies} instead.
   */
  public static String[] getInsecurePackagesForUid(PackageManager packageManager, PeerUid who) {
    return packageManager.getPackagesForUid(who.getUid());
  }

  /**
   * Retrieves the "official name" associated with this uid, as specified by {@link
   * PackageManager#getNameForUid(int)}.
   */
  @Nullable
  public static String getNameForUid(PackageManager packageManager, PeerUid who) {
    return packageManager.getNameForUid(who.getUid());
  }

  /**
   * Retrieves the {@link UserHandle} associated with this uid according to {@link
   * UserHandle#getUserHandleForUid}.
   */
  @RequiresApi(api = VERSION_CODES.N)
  public static UserHandle getUserHandleForUid(PeerUid who) {
    return UserHandle.getUserHandleForUid(who.getUid());
  }

  /**
   * Creates an interceptor that exposes the client's identity in the {@link Context} under {@link
   * #REMOTE_PEER}.
   *
   * <p>The returned interceptor only works with the Android Binder transport. If installed
   * elsewhere, all intercepted requests will fail without ever reaching application-layer
   * processing.
   */
  public static ServerInterceptor newPeerIdentifyingServerInterceptor() {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Context context = Context.current();
        PeerUid client =
            new PeerUid(
                checkNotNull(
                    call.getAttributes().get(BinderTransport.REMOTE_UID),
                    "Expected BinderTransport attribute REMOTE_UID was missing. Is this "
                        + "interceptor installed on an unsupported type of Server?"));
        return Contexts.interceptCall(context.withValue(REMOTE_PEER, client), call, headers, next);
      }
    };
  }

  private PeerUids() {}
}