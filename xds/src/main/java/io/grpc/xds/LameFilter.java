/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Message;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.xds.Filter.ClientInterceptorBuilder;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * A filter that fails all RPCs. To be added to the end of filter chain if RouterFilter is absent.
 */
enum LameFilter implements Filter, ClientInterceptorBuilder {
  INSTANCE;

  static final FilterConfig LAME_CONFIG = new FilterConfig() {
    @Override
    public String typeUrl() {
      throw new UnsupportedOperationException("shouldn't be called");
    }

    @Override
    public String toString() {
      return "LAME_CONFIG";
    }
  };

  @Override
  public String[] typeUrls() {
    return new String[0];
  }

  @Override
  public ConfigOrError<? extends FilterConfig> parseFilterConfig(Message rawProtoMessage) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig, PickSubchannelArgs args,
      ScheduledExecutorService scheduler) {
    class LameInterceptor implements ClientInterceptor {

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, final CallOptions callOptions, Channel next) {
        final Context context = Context.current();
        return new ClientCall<ReqT, RespT>() {
          @Override
          public void start(final Listener<RespT> listener, Metadata headers) {
            Executor callExecutor = callOptions.getExecutor();
            if (callExecutor == null) { // This should never happen in practice because
              // ManagedChannelImpl.ConfigSelectingClientCall always provides CallOptions with
              // a callExecutor.
              // TODO(https://github.com/grpc/grpc-java/issues/7868)
              callExecutor = MoreExecutors.directExecutor();
            }
            callExecutor.execute(
                new Runnable() {
                  @Override
                  public void run() {
                    Context previous = context.attach();
                    try {
                      listener.onClose(
                          Status.UNAVAILABLE.withDescription("No router filter"), new Metadata());
                    } finally {
                      context.detach(previous);
                    }
                  }
                });
          }

          @Override
          public void request(int numMessages) {}

          @Override
          public void cancel(@Nullable String message, @Nullable Throwable cause) {}

          @Override
          public void halfClose() {}

          @Override
          public void sendMessage(ReqT message) {}
        };
      }
    }

    return new LameInterceptor();
  }
}
