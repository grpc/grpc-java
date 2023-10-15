/*
 * Copyright 2017 The gRPC Authors
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

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.internal.SerializingExecutor;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * A {@link ServerCall} that wraps around a non thread safe delegate and provides thread safe
 * access by serializing everything on an executor.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2189")
class SerializingServerCall<ReqT, RespT> extends
    ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
  private static final String ERROR_MSG = "Encountered error during serialized access";
  private final SerializingExecutor serializingExecutor =
      new SerializingExecutor(MoreExecutors.directExecutor());
  private boolean closeCalled = false;

  SerializingServerCall(ServerCall<ReqT, RespT> delegate) {
    super(delegate);
  }

  @Override
  public void sendMessage(final RespT message) {
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        SerializingServerCall.super.sendMessage(message);
      }
    });
  }

  @Override
  public void request(final int numMessages) {
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        SerializingServerCall.super.request(numMessages);
      }
    });
  }

  @Override
  public void sendHeaders(final Metadata headers) {
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        SerializingServerCall.super.sendHeaders(headers);
      }
    });
  }

  @Override
  public void close(final Status status, final Metadata trailers) {
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        if (!closeCalled) {
          closeCalled = true;

          SerializingServerCall.super.close(status, trailers);
        }
      }
    });
  }

  @Override
  public boolean isReady() {
    final SettableFuture<Boolean> retVal = SettableFuture.create();
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        retVal.set(SerializingServerCall.super.isReady());
      }
    });
    try {
      return retVal.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(ERROR_MSG, e);
    }
  }

  @Override
  public boolean isCancelled() {
    final SettableFuture<Boolean> retVal = SettableFuture.create();
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        retVal.set(SerializingServerCall.super.isCancelled());
      }
    });
    try {
      return retVal.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(ERROR_MSG, e);
    }
  }

  @Override
  public void setMessageCompression(final boolean enabled) {
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        SerializingServerCall.super.setMessageCompression(enabled);
      }
    });
  }

  @Override
  public void setCompression(final String compressor) {
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        SerializingServerCall.super.setCompression(compressor);
      }
    });
  }

  @Override
  public Attributes getAttributes() {
    final SettableFuture<Attributes> retVal = SettableFuture.create();
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        retVal.set(SerializingServerCall.super.getAttributes());
      }
    });
    try {
      return retVal.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(ERROR_MSG, e);
    }
  }

  @Nullable
  @Override
  public String getAuthority() {
    final SettableFuture<String> retVal = SettableFuture.create();
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        retVal.set(SerializingServerCall.super.getAuthority());
      }
    });
    try {
      return retVal.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(ERROR_MSG, e);
    }
  }
}
