/*
 * Copyright 2020 The gRPC Authors
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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.AndroidRuntimeException;
import io.grpc.Attributes;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.internal.GrpcUtil;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * Helper class for reading &amp; writing metadata to parcels.
 *
 * <p>Metadata is written to a parcel as a single int for the number of name/value pairs, followed
 * by the following pattern for each pair.
 *
 * <ol>
 *   <li>name length (int)
 *   <li>name (byte[])
 *   <li>value length OR sentinel (int)
 *   <li>value (byte[] OR Parcelable)
 * </ol>
 *
 * The sentinel int at the start of a value may indicate bad metadata. When this happens, no more
 * data follows the sentinel.
 */
public final class MetadataHelper {

  /** The generic metadata marshaller we use for reading parcelables from the transport. */
  private static final Metadata.BinaryStreamMarshaller<Parcelable> TRANSPORT_INBOUND_MARSHALLER =
    new ParcelableMetadataMarshaller<>(null, true);

  /** Indicates the following value is a parcelable. */
  private static final int PARCELABLE_SENTINEL = -1;

  private MetadataHelper() {}

  /**
   * Write a Metadata instance to a Parcel.
   *
   * @param parcel The {@link Parcel} to write to.
   * @param metadata The {@link Metadata} to write.
   */
  public static void writeMetadata(Parcel parcel, @Nullable Metadata metadata)
      throws StatusException, IOException {
    int n = metadata != null ? InternalMetadata.headerCount(metadata) : 0;
    if (n == 0) {
      parcel.writeInt(0);
      return;
    }
    Object[] serialized = InternalMetadata.serializePartial(metadata);
    parcel.writeInt(n);
    for (int i = 0; i < n; i++) {
      byte[] name = (byte[]) serialized[i * 2];
      parcel.writeInt(name.length);
      parcel.writeByteArray(name);
      Object value = serialized[i * 2 + 1];
      if (value instanceof byte[]) {
        byte[] valueBytes = (byte[]) value;
        parcel.writeInt(valueBytes.length);
        parcel.writeByteArray(valueBytes);
      } else if (value instanceof ParcelableInputStream) {
        parcel.writeInt(PARCELABLE_SENTINEL);
        ((ParcelableInputStream) value).writeToParcel(parcel);
      } else {
        // An InputStream which wasn't created by ParcelableUtils, which means there's another use
        // of Metadata.BinaryStreamMarshaller. Just read the bytes.
        //
        // We know that BlockPool will give us a buffer at least as large as the max space for all
        // names and values so it'll certainly be large enough (and the limit is only 8k so this
        // is fine).
        byte[] buffer = BlockPool.acquireBlock();
        try {
          InputStream stream = (InputStream) value;
          int total = 0;
          while (total < buffer.length) {
            int read = stream.read(buffer, total, buffer.length - total); 
            if (read == -1) {
              break;
            }
            total += read;
          }
          if (total == buffer.length) {
            throw Status.RESOURCE_EXHAUSTED.withDescription("Metadata value too large").asException();
          }
          parcel.writeInt(total);
          if (total > 0) {
            parcel.writeByteArray(buffer, 0, total);
          }
        } finally {
          BlockPool.releaseBlock(buffer);
        }
      }
    }
  }

  /**
   * Read a Metadata instance from a Parcel.
   *
   * @param parcel The {@link Parcel} to read from.
   */
  public static Metadata readMetadata(Parcel parcel, Attributes attributes) throws StatusException {
    int n = parcel.readInt();
    if (n == 0) {
      return new Metadata();
    }
    // For enforcing the header-size limit. Doesn't include parcelable data.
    int bytesRead = 0;
    // For enforcing the maximum allowed parcelable data (see InboundParcelablePolicy).
    int parcelableBytesRead = 0;
    Object[] serialized = new Object[n * 2];
    for (int i = 0; i < n; i++) {
      int numNameBytes = parcel.readInt();
      bytesRead += 4;
      byte[] name = readBytesChecked(parcel, numNameBytes, bytesRead);
      bytesRead += numNameBytes;
      serialized[i * 2] = name;
      int numValueBytes = parcel.readInt();
      bytesRead += 4;
      if (numValueBytes == PARCELABLE_SENTINEL) {
        InboundParcelablePolicy policy = attributes.get(BinderTransport.INBOUND_PARCELABLE_POLICY);
        if (!policy.shouldAcceptParcelableMetadataValues()) {
          throw Status.PERMISSION_DENIED
              .withDescription("Parcelable metadata values not allowed")
              .asException();
        }
        int parcelableStartPos = parcel.dataPosition();
        try {
          Parcelable value = parcel.readParcelable(MetadataHelper.class.getClassLoader());
          if (value == null) {
            throw Status.INTERNAL.withDescription("Read null parcelable in metadata").asException();
          }
          serialized[i * 2 + 1] = InternalMetadata.parsedValue(TRANSPORT_INBOUND_MARSHALLER, value);
        } catch (AndroidRuntimeException are) {
          throw Status.INTERNAL
              .withCause(are)
              .withDescription("Failure reading parcelable in metadata")
              .asException();
        }
        int parcelableSize = parcel.dataPosition() - parcelableStartPos;
        parcelableBytesRead += parcelableSize;
        if (parcelableBytesRead > policy.getMaxParcelableMetadataSize()) {
          throw Status.RESOURCE_EXHAUSTED
              .withDescription(
                  "Inbound Parcelables too large according to policy (see InboundParcelablePolicy)")
              .asException();
        }
      } else if (numValueBytes < 0) {
        throw Status.INTERNAL.withDescription("Unrecognized metadata sentinel").asException();
      } else {
        byte[] value = readBytesChecked(parcel, numValueBytes, bytesRead);
        bytesRead += numValueBytes;
        serialized[i * 2 + 1] = value;
      }
    }
    return InternalMetadata.newMetadataWithParsedValues(n, serialized);
  }

  /** Read a byte array checking that we're not reading too much. */
  private static byte[] readBytesChecked(
      Parcel parcel,
      int numBytes,
      int bytesRead) throws StatusException {
    if (bytesRead + numBytes > GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE) {
      throw Status.RESOURCE_EXHAUSTED.withDescription("Metadata too large").asException();
    }
    byte[] res = new byte[numBytes];
    if (numBytes > 0) {
      parcel.readByteArray(res);
    }
    return res;
  }

  /** A marshaller for passing parcelables in gRPC {@link Metadata} */
  public static final class ParcelableMetadataMarshaller<P extends Parcelable>
      implements Metadata.BinaryStreamMarshaller<P> {

    @Nullable private final Parcelable.Creator<P> creator;
    private final boolean immutableType;

    public ParcelableMetadataMarshaller(@Nullable Parcelable.Creator<P> creator, boolean immutableType) {
      this.creator = creator;
      this.immutableType = immutableType;
    }

    @Override
    public InputStream toStream(P value) {
      return new ParcelableInputStream<>(creator, value, immutableType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public P parseStream(InputStream stream) {
      if (stream instanceof ParcelableInputStream) {
        return ((ParcelableInputStream<P>) stream).getParcelable();
      } else {
        throw new UnsupportedOperationException(
            "Can't unmarshall a parcelable from a regular byte stream");
      }
    }
  }
}
