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

package io.grpc.binder.internal;

import static io.grpc.binder.internal.TransactionUtils.FLAG_MESSAGE_DATA;
import static io.grpc.binder.internal.TransactionUtils.FLAG_MESSAGE_DATA_IS_PARTIAL;
import static io.grpc.binder.internal.TransactionUtils.FLAG_OUT_OF_BAND_CLOSE;
import static io.grpc.binder.internal.TransactionUtils.FLAG_PREFIX;
import static io.grpc.binder.internal.TransactionUtils.FLAG_SUFFIX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import android.os.Parcel;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Metadata;
import io.grpc.Status;

/**
 * Builds and dispatches grpc-binder transactions for unit testing.
 *
 * <p>This class implements the [grpc-binder
 * wireformat](https://github.com/grpc/proposal/blob/master/L73-java-binderchannel/wireformat.md).
 * It makes low level unit tests easy to write and easy to read. It's intentionally difficult, but
 * not impossible, to dispatch a transaction that violates the wireformat -- most mistakes will fail
 * to compile.
 */
public abstract class TransactionBuilder {

  /** Creates a builder for a client-to-server (request stream) transaction. */
  public static ServerStreamTxnBuilder newStreamTxnToServerBuilder(int index) {
    return new ServerStreamTxnBuilder(index);
  }

  /** Creates a builder for a server-to-client (response stream) transaction. */
  public static ClientStreamTxnBuilder newStreamTxnToClientBuilder(int index) {
    return new ClientStreamTxnBuilder(index);
  }

  /** Creates a builder for an out-of-band close transaction. */
  public static OutOfBandCloseTxnBuilder newOutOfBandCloseTxnBuilder(Status status) {
    return new OutOfBandCloseTxnBuilder(status);
  }

  /** Functional interface for parcel consumers that may throw checked exceptions. */
  @FunctionalInterface
  public interface ParcelConsumer {
    void accept(Parcel parcel) throws Exception;
  }

  /** Dispatches the synthesized parcel to a generic consumer, recycling the parcel afterwards. */
  public abstract void dispatchTo(ParcelConsumer consumer) throws Exception;

  /** Dispatches the synthesized parcel to a transport for the given callId. */
  public final void dispatchTo(BinderTransport transport, int callId) throws Exception {
    dispatchTo(parcel -> transport.handleTransaction(callId, parcel));
  }

  /** Dispatches the synthesized parcel to an inbound handler. */
  public final void dispatchTo(Inbound<?, ?> inbound) throws Exception {
    dispatchTo(inbound::handleTransaction);
  }

  /**
   * Base builder for in-band streaming transactions containing sequence index and message chunks.
   */
  public abstract static class StreamTransactionBuilder<B extends StreamTransactionBuilder<B>>
      extends TransactionBuilder {
    protected final int index;
    protected int flags;
    protected byte[] messageData;

    protected StreamTransactionBuilder(int index) {
      this.index = index;
    }

    protected abstract B self();

    protected abstract void writePrefix(Parcel parcel) throws Exception;

    protected abstract int writeSuffix(Parcel parcel) throws Exception;

    /** Appends complete message data from a byte array payload. */
    @CanIgnoreReturnValue
    public final B withMessage(byte[] data) {
      this.flags |= FLAG_MESSAGE_DATA;
      this.messageData = requireNonNull(data, "data");
      return self();
    }

    /** Appends a partial message fragment from a byte array payload. */
    @CanIgnoreReturnValue
    public final B withMessageFragment(byte[] data) {
      this.flags |= FLAG_MESSAGE_DATA | FLAG_MESSAGE_DATA_IS_PARTIAL;
      this.messageData = requireNonNull(data, "data");
      return self();
    }

    /** Appends the final message fragment from a byte array payload. */
    @CanIgnoreReturnValue
    public final B withFinalMessageFragment(byte[] data) {
      return withMessage(data);
    }

    @Override
    public final void dispatchTo(ParcelConsumer consumer) throws Exception {
      Parcel parcel = Parcel.obtain();
      try {
        parcel.writeInt(0); // placeholder for flags
        parcel.writeInt(index);
        writePrefix(parcel);
        if ((flags & FLAG_MESSAGE_DATA) != 0) {
          parcel.writeInt(messageData.length);
          if (messageData.length > 0) {
            parcel.writeByteArray(messageData);
          }
        }
        int computedFlags = flags | writeSuffix(parcel);
        TransactionUtils.fillInFlags(parcel, computedFlags);
        parcel.setDataPosition(0);
        consumer.accept(parcel);
      } finally {
        parcel.recycle();
      }
    }
  }

  /** Builder for client-to-server (request) stream transactions. */
  public static final class ServerStreamTxnBuilder
      extends StreamTransactionBuilder<ServerStreamTxnBuilder> {
    private String methodName;
    private Metadata headers;

    private ServerStreamTxnBuilder(int index) {
      super(index);
    }

    @Override
    protected ServerStreamTxnBuilder self() {
      return this;
    }

    /** Sets the client prefix with the target RPC method name and initial request headers. */
    @CanIgnoreReturnValue
    public ServerStreamTxnBuilder withPrefix(String methodName, Metadata headers) {
      this.flags |= FLAG_PREFIX;
      this.methodName = requireNonNull(methodName, "methodName");
      this.headers = requireNonNull(headers, "headers");
      return this;
    }

    /** Sets the flag indicating that this RPC expects a single unary response. */
    @CanIgnoreReturnValue
    public ServerStreamTxnBuilder withExpectSingleMessage() {
      this.flags |= TransactionUtils.FLAG_EXPECT_SINGLE_MESSAGE;
      return this;
    }

    /** Sets the client half-close / end-of-stream suffix flag. */
    @CanIgnoreReturnValue
    public ServerStreamTxnBuilder withSuffix() {
      this.flags |= FLAG_SUFFIX;
      return this;
    }

    @Override
    protected void writePrefix(Parcel parcel) throws Exception {
      if ((flags & FLAG_PREFIX) != 0) {
        parcel.writeString(methodName);
        MetadataHelper.writeMetadata(parcel, headers);
      }
    }

    @Override
    protected int writeSuffix(Parcel parcel) {
      // Client-to-server suffix has no payload.
      return 0;
    }
  }

  /** Builder for server-to-client (response) stream transactions. */
  public static final class ClientStreamTxnBuilder
      extends StreamTransactionBuilder<ClientStreamTxnBuilder> {
    private Metadata headers;
    private Status status;
    private Metadata trailers;

    private ClientStreamTxnBuilder(int index) {
      super(index);
    }

    @Override
    protected ClientStreamTxnBuilder self() {
      return this;
    }

    /** Sets the server prefix with initial response headers. */
    @CanIgnoreReturnValue
    public ClientStreamTxnBuilder withPrefix(Metadata headers) {
      this.flags |= FLAG_PREFIX;
      this.headers = requireNonNull(headers, "headers");
      return this;
    }

    /** Sets the server suffix with terminal status and trailing metadata. */
    @CanIgnoreReturnValue
    public ClientStreamTxnBuilder withSuffix(Status status, Metadata trailers) {
      this.flags |= FLAG_SUFFIX;
      this.status = requireNonNull(status, "status");
      this.trailers = requireNonNull(trailers, "trailers");
      return this;
    }

    @Override
    protected void writePrefix(Parcel parcel) throws Exception {
      if ((flags & FLAG_PREFIX) != 0) {
        MetadataHelper.writeMetadata(parcel, headers);
      }
    }

    @Override
    protected int writeSuffix(Parcel parcel) throws Exception {
      if ((flags & FLAG_SUFFIX) != 0) {
        int statusFlags = TransactionUtils.writeStatus(parcel, status);
        MetadataHelper.writeMetadata(parcel, trailers);
        return statusFlags;
      }
      return 0;
    }
  }

  /** Builder for out-of-band close transactions. */
  public static final class OutOfBandCloseTxnBuilder extends TransactionBuilder {
    private final Status status;

    private OutOfBandCloseTxnBuilder(Status status) {
      this.status = requireNonNull(status, "status");
    }

    @Override
    public void dispatchTo(ParcelConsumer consumer) throws Exception {
      Parcel parcel = Parcel.obtain();
      try {
        parcel.writeInt(0);
        int flags = FLAG_OUT_OF_BAND_CLOSE | TransactionUtils.writeStatus(parcel, status);
        TransactionUtils.fillInFlags(parcel, flags);
        parcel.setDataPosition(0);
        consumer.accept(parcel);
      } finally {
        parcel.recycle();
      }
    }
  }

  /**
   * Encodes the given string to bytes using UTF-8.
   *
   * <p>Convenient for unit tests that use string literals for payloads.
   */
  public static byte[] utf8(String string) {
    return string.getBytes(UTF_8);
  }
}
