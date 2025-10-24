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

package io.grpc.xds.internal.headermutations;

import com.google.common.io.BaseEncoding;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption.HeaderAppendAction;
import io.grpc.Metadata;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * The HeaderMutator class is an implementation of the HeaderMutator interface. It provides methods
 * to apply header mutations to a given set of headers based on a given set of rules.
 */
public interface HeaderMutator {
  /**
   * Creates a new instance of {@code HeaderMutator}.
   */
  static HeaderMutator create() {
    return new HeaderMutatorImpl();
  }

  /**
   * Applies the given header mutations to the provided metadata headers.
   *
   * @param mutations The header mutations to apply.
   * @param headers The metadata headers to which the mutations will be applied.
   */
  void applyRequestMutations(RequestHeaderMutations mutations, Metadata headers);


  /**
   * Applies the given header mutations to the provided metadata headers.
   *
   * @param mutations The header mutations to apply.
   * @param headers The metadata headers to which the mutations will be applied.
   */
  void applyResponseMutations(ResponseHeaderMutations mutations, Metadata headers);

  /** Default implementation of {@link HeaderMutator}. */
  final class HeaderMutatorImpl implements HeaderMutator {

    private static final Logger logger = Logger.getLogger(HeaderMutatorImpl.class.getName());

    @Override
    public void applyRequestMutations(final RequestHeaderMutations mutations, Metadata headers) {
      // TODO(sauravzg): The specification is not clear on order of header removals and additions.
      // in case of conflicts. Copying the order from Envoy here, which does removals at the end.
      applyHeaderUpdates(mutations.headers(), headers);
      for (String headerToRemove : mutations.headersToRemove()) {
        headers.discardAll(Metadata.Key.of(headerToRemove, Metadata.ASCII_STRING_MARSHALLER));
      }
    }

    @Override
    public void applyResponseMutations(final ResponseHeaderMutations mutations, Metadata headers) {
      applyHeaderUpdates(mutations.headers(), headers);
    }

    private void applyHeaderUpdates(final Iterable<HeaderValueOption> headerOptions,
        Metadata headers) {
      for (HeaderValueOption headerOption : headerOptions) {
        HeaderValue headerValue = headerOption.getHeader();
        updateHeader(headerValue, headerOption.getAppendAction(), headers);
      }
    }

    private void updateHeader(final HeaderValue header, final HeaderAppendAction action,
        Metadata mutableHeaders) {
      if (header.getKey().endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        updateHeader(action, Metadata.Key.of(header.getKey(), Metadata.BINARY_BYTE_MARSHALLER),
            getBinaryHeaderValue(header), mutableHeaders);
      } else {
        updateHeader(action, Metadata.Key.of(header.getKey(), Metadata.ASCII_STRING_MARSHALLER),
            getAsciiValue(header), mutableHeaders);
      }
    }

    private <T> void updateHeader(final HeaderAppendAction action, final Metadata.Key<T> key,
        final T value, Metadata mutableHeaders) {
      switch (action) {
        case APPEND_IF_EXISTS_OR_ADD:
          mutableHeaders.put(key, value);
          break;
        case ADD_IF_ABSENT:
          if (!mutableHeaders.containsKey(key)) {
            mutableHeaders.put(key, value);
          }
          break;
        case OVERWRITE_IF_EXISTS_OR_ADD:
          mutableHeaders.discardAll(key);
          mutableHeaders.put(key, value);
          break;
        case OVERWRITE_IF_EXISTS:
          if (mutableHeaders.containsKey(key)) {
            mutableHeaders.discardAll(key);
            mutableHeaders.put(key, value);
          }
          break;
        case UNRECOGNIZED:
          // Ignore invalid value
          logger.warning("Unrecognized HeaderAppendAction: " + action);
          break;
        default:
          // Should be unreachable unless there's a proto schema mismatch.
          logger.warning("Unknown HeaderAppendAction: " + action);
      }
    }

    private byte[] getBinaryHeaderValue(HeaderValue header) {
      return BaseEncoding.base64().decode(getAsciiValue(header));
    }

    private String getAsciiValue(HeaderValue header) {
      // TODO(sauravzg): GRPC only supports base64 encoded binary headers, so we decode bytes to
      // String using `StandardCharsets.US_ASCII`.
      // Envoy's spec `raw_value` specification can contain non UTF-8 bytes, so this may potentially
      // cause an exception or corruption.
      if (!header.getRawValue().isEmpty()) {
        return header.getRawValue().toString(StandardCharsets.US_ASCII);
      }
      return header.getValue();
    }
  }
}
