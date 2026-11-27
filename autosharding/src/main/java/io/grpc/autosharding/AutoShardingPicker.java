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

import com.google.common.annotations.VisibleForTesting;
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
import javax.annotation.concurrent.ThreadSafe;

/**
 * Routes each RPC to an endpoint of the slice covering its sharding key. With fallback enabled,
 * an RPC goes to any resolved endpoint instead when there is no assignment or the slice has no
 * usable endpoint. A slice that the server assigned no endpoints fails the RPC either way.
 */
final class AutoShardingPicker extends SubchannelPicker {
  @ThreadSafe
  @FunctionalInterface
  interface ThreadSafeRandom {
    int nextInt(int bound);
  }

  private static final ThreadSafeRandom DEFAULT_RANDOM =
      bound -> ThreadLocalRandom.current().nextInt(bound);

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
  private final Metadata.Key<byte[]> keyHeader;
  private final ThreadSafeRandom random;

  /**
   * Returns a key that reads {@code keyHeaderName}'s value as bytes: the binary value for a
   * {@code -bin} header, and the ASCII bytes otherwise.
   *
   * @throws IllegalArgumentException if {@code keyHeaderName} is not a valid header name
   */
  static Metadata.Key<byte[]> createKeyHeader(String keyHeaderName) {
    if (keyHeaderName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      return Metadata.Key.of(keyHeaderName, Metadata.BINARY_BYTE_MARSHALLER);
    } else {
      return InternalMetadata.keyOf(keyHeaderName, RAW_ASCII_MARSHALLER);
    }
  }

  AutoShardingPicker(
      SliceMap sliceMap,
      List<PickerEndpoint> endpoints,
      boolean fallbackEnabled,
      Metadata.Key<byte[]> keyHeader) {
    this(sliceMap, endpoints, fallbackEnabled, keyHeader, DEFAULT_RANDOM);
  }

  @VisibleForTesting
  AutoShardingPicker(
      SliceMap sliceMap,
      List<PickerEndpoint> endpoints,
      boolean fallbackEnabled,
      Metadata.Key<byte[]> keyHeader,
      ThreadSafeRandom random) {
    this.sliceMap = checkNotNull(sliceMap, "sliceMap");
    this.endpoints = ImmutableList.copyOf(checkNotNull(endpoints, "endpoints"));
    this.fallbackEnabled = fallbackEnabled;
    this.keyHeader = checkNotNull(keyHeader, "keyHeader");
    this.random = checkNotNull(random, "random");

    boolean hasTransientFailure = false;
    for (int i = 0; i < this.endpoints.size(); i++) {
      if (this.endpoints.get(i).getState() == ConnectivityState.TRANSIENT_FAILURE) {
        hasTransientFailure = true;
        break;
      }
    }

    this.sliceInFallback = new boolean[sliceMap.getSlices().size()];
    if (!hasTransientFailure) {
      for (int i = 0; i < sliceInFallback.length; i++) {
        this.sliceInFallback[i] = sliceMap.getSlices().get(i).getEndpoints().isEmpty();
      }
    } else {
      for (int i = 0; i < sliceInFallback.length; i++) {
        this.sliceInFallback[i] = isPoolInFallback(sliceMap.getSlices().get(i).getEndpoints());
      }
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
    // The first value if the header was sent more than once; get() would return the last.
    Iterable<byte[]> keys = args.getHeaders().getAll(keyHeader);
    byte[] key = keys == null ? null : keys.iterator().next();
    if (key == null) {
      // wait-for-ready RPCs keep waiting for a picker
      // that can route them, and the others fail.
      return PickResult.withError(
          Status.UNAVAILABLE.withDescription(
              "autosharding: request is missing the '" + keyHeader.name() + "' key header"));
    }
    int sliceIdx = sliceMap.lookup(key);

    if (sliceIdx == -1) {
      if (fallbackEnabled) {
        return pickFromEndpointIndices(
            sliceMap.getFallbackPool(), args, "No endpoints available in fallback pool");
      } else {
        return PickResult.withError(
            Status.UNAVAILABLE.withDescription(
                "No sharding assignment available and fallback disabled"));
      }
    }

    SliceMap.SliceEntry sliceEntry = sliceMap.getSlices().get(sliceIdx);
    if (sliceEntry.isFallbackDisabled()) {
      // A drop, so that wait-for-ready RPCs fail too instead of waiting on a range that the
      // server has deliberately left unassigned.
      return PickResult.withDrop(
          Status.UNAVAILABLE.withDescription(
              "autosharding: matching slice has fallback disabled by the sharding server"
                  + " (no endpoints assigned)"));
    }

    if (sliceInFallback[sliceIdx] && fallbackEnabled) {
      return pickFromEndpointIndices(
          sliceMap.getFallbackPool(), args, "No endpoints available in fallback pool");
    }

    return pickFromEndpointIndices(
        sliceEntry.getEndpoints(), args, "No valid endpoints in slice and fallback disabled");
  }

  private PickResult pickFromEndpointIndices(
      List<Integer> indices, PickSubchannelArgs args, String emptyErrorDescription) {
    if (indices.isEmpty()) {
      return PickResult.withError(
          Status.UNAVAILABLE.withDescription(emptyErrorDescription));
    }

    int size = indices.size();
    int firstIndex = random.nextInt(size);
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
}
