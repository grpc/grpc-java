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
    } catch (InterruptedException e) {
      throw new RuntimeException(ERROR_MSG, e);
    } catch (ExecutionException e) {
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
    } catch (InterruptedException e) {
      throw new RuntimeException(ERROR_MSG, e);
    } catch (ExecutionException e) {
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
    } catch (InterruptedException e) {
      throw new RuntimeException(ERROR_MSG, e);
    } catch (ExecutionException e) {
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
    } catch (InterruptedException e) {
      throw new RuntimeException(ERROR_MSG, e);
    } catch (ExecutionException e) {
      throw new RuntimeException(ERROR_MSG, e);
    }
  }
}
