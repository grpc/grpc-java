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

package io.grpc.autosharding;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import io.grpc.ConnectivityState;
import io.grpc.InternalMetadata;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

/**
 * Subchannel picker for the auto-sharding load balancing policy.
 *
 * <p>Routes RPCs to backend endpoints based on a request metadata header key, matching against
 * an immutable {@link SliceMap}.
 */
final class AutoShardingPicker extends SubchannelPicker {
  private static final byte[] EMPTY_BYTES = new byte[0];

  private static final InternalMetadata.TrustedAsciiMarshaller<byte[]> RAW_ASCII_MARSHALLER =
      new InternalMetadata.TrustedAsciiMarshaller<byte[]>() {
        @Override
        public byte[] toAsciiString(byte[] value) {
          return value;
        }

        @Override
        public byte[] parseAsciiString(byte[] serialized) {
          return serialized;
        }
      };

  private final SliceMap sliceMap;
  private final ImmutableList<PickerEndpoint> endpoints;
  private final boolean[] sliceInFallback;
  private final boolean fallbackEnabled;
  @Nullable private final Metadata.Key<byte[]> keyHeader;

  /**
   * Pre-creates a {@link Metadata.Key} for the given key header name.
   *
   * @param keyHeaderName the metadata header name, or {@code null}/empty if no header routing
   * @return the pre-computed {@link Metadata.Key}, or {@code null} if keyHeaderName is null/empty
   */
  @Nullable
  static Metadata.Key<byte[]> createKeyHeader(@Nullable String keyHeaderName) {
    if (keyHeaderName == null || keyHeaderName.isEmpty()) {
      return null;
    } else if (keyHeaderName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      return Metadata.Key.of(keyHeaderName, Metadata.BINARY_BYTE_MARSHALLER);
    } else {
      return InternalMetadata.keyOf(keyHeaderName, RAW_ASCII_MARSHALLER);
    }
  }

  /**
   * Constructs an {@link AutoShardingPicker}.
   *
   * @param sliceMap the pre-built, immutable mapping from key ranges to endpoint indices
   * @param endpoints the list of endpoint snapshots corresponding 1:1 to endpoint indices
   * @param fallbackEnabled whether fallback routing to all resolved endpoints is enabled
   * @param keyHeader the pre-parsed metadata header key used to extract the routing key
   */
  AutoShardingPicker(
      SliceMap sliceMap,
      List<PickerEndpoint> endpoints,
      boolean fallbackEnabled,
      @Nullable Metadata.Key<byte[]> keyHeader) {
    this.sliceMap = checkNotNull(sliceMap, "sliceMap");
    this.endpoints = ImmutableList.copyOf(checkNotNull(endpoints, "endpoints"));
    this.fallbackEnabled = fallbackEnabled;
    this.keyHeader = keyHeader;

    this.sliceInFallback = new boolean[sliceMap.getSlices().size()];
    for (int i = 0; i < sliceInFallback.length; i++) {
      this.sliceInFallback[i] = isPoolInFallback(sliceMap.getSlices().get(i).getEndpoints());
    }
  }

  private boolean isPoolInFallback(List<Integer> indices) {
    if (indices.isEmpty()) {
      return true;
    }
    for (int idx : indices) {
      if (endpoints.get(idx).getState() != ConnectivityState.TRANSIENT_FAILURE) {
        return false;
      }
    }
    return true;
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    byte[] key = extractKeyBytes(args.getHeaders());
    int sliceIdx = sliceMap.lookup(key);

    if (sliceIdx == -1) {
      if (fallbackEnabled) {
        return pickFromEndpointIndices(sliceMap.getFallbackPool(), args);
      } else {
        return PickResult.withError(
            Status.UNAVAILABLE.withDescription(
                "No sharding assignment available and fallback disabled"));
      }
    }

    if (sliceInFallback[sliceIdx] && fallbackEnabled) {
      return pickFromEndpointIndices(sliceMap.getFallbackPool(), args);
    }

    SliceMap.SliceEntry sliceEntry = sliceMap.getSlices().get(sliceIdx);
    return pickFromEndpointIndices(sliceEntry.getEndpoints(), args);
  }

  private PickResult pickFromEndpointIndices(
      List<Integer> indices, PickSubchannelArgs args) {
    if (indices.isEmpty()) {
      return PickResult.withError(
          Status.UNAVAILABLE.withDescription("No valid endpoints in slice and fallback disabled"));
    }

    int size = indices.size();
    int firstIndex = ThreadLocalRandom.current().nextInt(size);
    boolean requestedConnection = false;
    boolean foundConnecting = false;

    for (int i = 0; i < size; i++) {
      int epIdx = indices.get((firstIndex + i) % size);
      PickerEndpoint endpoint = endpoints.get(epIdx);

      if (endpoint.getState() == ConnectivityState.READY) {
        return endpoint.getPicker().pickSubchannel(args);
      }

      if (endpoint.getState() == ConnectivityState.CONNECTING) {
        foundConnecting = true;
      } else if (!requestedConnection && endpoint.getState() == ConnectivityState.IDLE) {
        endpoint.requestConnection();
        requestedConnection = true;
      }
    }

    if (requestedConnection || foundConnecting) {
      return PickResult.withNoResult("connecting", "Waiting for endpoint connection");
    }

    int firstEpIdx = indices.get(firstIndex);
    return endpoints.get(firstEpIdx).getPicker().pickSubchannel(args);
  }

  private byte[] extractKeyBytes(Metadata headers) {
    if (keyHeader != null) {
      byte[] val = headers.get(keyHeader);
      return val != null ? val : EMPTY_BYTES;
    }
    return EMPTY_BYTES;
  }
}
