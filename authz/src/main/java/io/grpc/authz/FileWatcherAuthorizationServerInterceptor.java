/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.authz;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.grpc.ExperimentalApi;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Authorization server interceptor for policy from file with refresh capability.
 * The class will get <a href="https://github.com/grpc/proposal/blob/master/A43-grpc-authorization-api.md#user-facing-authorization-policy">
 * gRPC Authorization policy</a> from a JSON file during initialization.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9746")
public final class FileWatcherAuthorizationServerInterceptor implements ServerInterceptor {
  private static final Logger logger = 
      Logger.getLogger(FileWatcherAuthorizationServerInterceptor.class.getName());

  private volatile AuthorizationServerInterceptor internalAuthzServerInterceptor;

  private final File policyFile;
  private String policyContents;

  private FileWatcherAuthorizationServerInterceptor(File policyFile) throws IOException {
    this.policyFile = policyFile;
    updateInternalInterceptor();
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {
    return internalAuthzServerInterceptor.interceptCall(call, headers, next);
  }

  void updateInternalInterceptor() throws IOException {
    String currentPolicyContents = new String(Files.readAllBytes(policyFile.toPath()), UTF_8);
    if (currentPolicyContents.equals(policyContents)) {
      return;
    }
    policyContents = currentPolicyContents;
    internalAuthzServerInterceptor = AuthorizationServerInterceptor.create(policyContents);
  }

  /** 
   * Policy is reloaded periodically as per the provided refresh interval. Unlike the
   * constructor, exception thrown during reload will be caught and logged and the
   * previous AuthorizationServerInterceptor will be used to make authorization
   * decisions.
   * 
   * @param period the period between successive file load executions.
   * @param unit the time unit for period parameter
   * @param executor the execute service we use to read and update authorization policy
   * @return an object that caller should close when the file refreshes are not needed
   */
  public Closeable scheduleRefreshes(
      long period, TimeUnit unit, ScheduledExecutorService executor) throws IOException {
    checkNotNull(executor, "scheduledExecutorService");
    if (period <= 0) {
      throw new IllegalArgumentException("Refresh interval must be greater than 0");
    }
    final ScheduledFuture<?> future = 
        executor.scheduleWithFixedDelay(new Runnable() {
          @Override
          public void run() {
            try {
              updateInternalInterceptor();
            } catch (Exception e) {
              logger.log(Level.WARNING, "Authorization Policy file reload failed", e);
            }
          }
        }, period, period, unit);
    return new Closeable() {
      @Override public void close() {
        future.cancel(false);
      }
    };
  }

  public static FileWatcherAuthorizationServerInterceptor create(File policyFile) 
      throws IOException {
    checkNotNull(policyFile, "policyFile");
    return new FileWatcherAuthorizationServerInterceptor(policyFile);
  }
}
