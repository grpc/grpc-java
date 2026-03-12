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


import io.grpc.Metadata;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderValueOption.HeaderAppendAction;
import java.util.logging.Logger;

/**
 * The HeaderMutator class is an implementation of the HeaderMutator interface. It provides methods
 * to apply header mutations to a given set of headers based on a given set of rules.
 */
public class HeaderMutator {

  private static final Logger logger = Logger.getLogger(HeaderMutator.class.getName());

  /**
   * Creates a new instance of {@code HeaderMutator}.
   */
  public static HeaderMutator create() {
    return new HeaderMutator();
  }

  HeaderMutator() {}

  /**
   * Applies the given header mutations to the provided metadata headers.
   *
   * @param mutations The header mutations to apply.
   * @param headers The metadata headers to which the mutations will be applied.
   */
  public void applyRequestMutations(final RequestHeaderMutations mutations, Metadata headers) {
    // TODO(sauravzg): The specification is not clear on order of header removals and additions.
    // in case of conflicts. Copying the order from Envoy here, which does removals at the end.
    applyHeaderUpdates(mutations.headers(), headers);
    for (String headerToRemove : mutations.headersToRemove()) {
      headers.discardAll(Metadata.Key.of(headerToRemove, Metadata.ASCII_STRING_MARSHALLER));
    }
  }

  /**
   * Applies the given header mutations to the provided metadata headers.
   *
   * @param mutations The header mutations to apply.
   * @param headers The metadata headers to which the mutations will be applied.
   */
  public void applyResponseMutations(final ResponseHeaderMutations mutations, Metadata headers) {
    applyHeaderUpdates(mutations.headers(), headers);
  }

  private void applyHeaderUpdates(final Iterable<HeaderValueOption> headerOptions,
      Metadata headers) {
    for (HeaderValueOption headerOption : headerOptions) {
      updateHeader(headerOption, headers);
    }
  }

  private void updateHeader(final HeaderValueOption option, Metadata mutableHeaders) {
    HeaderValue header = option.header();
    HeaderAppendAction action = option.appendAction();
    boolean keepEmptyValue = option.keepEmptyValue();

    if (header.key().endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      updateHeader(action, Metadata.Key.of(header.key(), Metadata.BINARY_BYTE_MARSHALLER),
          header.rawValue().get().toByteArray(), mutableHeaders, keepEmptyValue);
    } else {
      updateHeader(action, Metadata.Key.of(header.key(), Metadata.ASCII_STRING_MARSHALLER),
          header.value().get(), mutableHeaders, keepEmptyValue);
    }
  }

  private <T> void updateHeader(final HeaderAppendAction action, final Metadata.Key<T> key,
      final T value, Metadata mutableHeaders, boolean keepEmptyValue) {
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

      default:
        // Should be unreachable unless there's a proto schema mismatch.
        logger.warning("Unknown HeaderAppendAction: " + action);
    }

    if (!keepEmptyValue) {
      checkAndRemoveEmpty(key, mutableHeaders);
    }
  }

  private <T> void checkAndRemoveEmpty(Metadata.Key<T> key, Metadata headers) {
    Iterable<T> values = headers.getAll(key);
    if (values == null) {
      return;
    }
    boolean allEmpty = true;
    for (T val : values) {
      if (val instanceof String) {
        if (!((String) val).isEmpty()) {
          allEmpty = false;
          break;
        }
      } else if (val instanceof byte[]) {
        if (((byte[]) val).length > 0) {
          allEmpty = false;
          break;
        }
      }
    }
    if (allEmpty) {
      headers.discardAll(key);
    }
  }
}
