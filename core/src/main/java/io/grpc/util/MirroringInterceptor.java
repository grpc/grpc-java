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

package io.grpc.util;

import com.google.common.base.Preconditions;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A ClientInterceptor that mirrors calls to a shadow channel.
 * Designed to support Unary, Client-Streaming, Server-Streaming, and Bidi calls.
 */
public final class MirroringInterceptor implements ClientInterceptor {
  private static final Logger logger = Logger.getLogger(MirroringInterceptor.class.getName());

  private final Channel mirrorChannel;
  private final Executor executor;

  public MirroringInterceptor(Channel mirrorChannel, Executor executor) {
    this.mirrorChannel = Preconditions.checkNotNull(mirrorChannel, "mirrorChannel");
    this.executor = Preconditions.checkNotNull(executor, "executor");
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
        next.newCall(method, callOptions)) {
      
      private ClientCall<ReqT, RespT> mirrorCall;

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        // 1. Capture and copy headers immediately (thread-safe for the executor)
        final Metadata mirrorHeaders = new Metadata();
        mirrorHeaders.merge(headers);

        executor.execute(() -> {
          try {
            // 2. Initialize the shadow call once per stream
            mirrorCall = mirrorChannel.newCall(method, callOptions);
            mirrorCall.start(new ClientCall.Listener<RespT>() {}, mirrorHeaders);
          } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to start mirror call", e);
          }
        });
        super.start(responseListener, headers);
      }

      @Override
      public void sendMessage(ReqT message) {
        executor.execute(() -> {
          if (mirrorCall != null) {
            try {
              mirrorCall.sendMessage(message);
            } catch (Exception e) {
              logger.log(Level.WARNING, "Mirroring message failed", e);
            }
          }
        });
        super.sendMessage(message);
      }

      @Override
      public void halfClose() {
        executor.execute(() -> {
          if (mirrorCall != null) {
            try {
              mirrorCall.halfClose();
            } catch (Exception e) {
              logger.log(Level.WARNING, "Mirroring halfClose failed", e);
            }
          }
        });
        super.halfClose();
      }

      @Override
      public void cancel(String message, Throwable cause) {
        executor.execute(() -> {
          if (mirrorCall != null) {
            try {
              mirrorCall.cancel(message, cause);
            } catch (Exception e) {
              logger.log(Level.WARNING, "Mirroring cancel failed", e);
            }
          }
        });
        super.cancel(message, cause);
      }
    };
  }
}