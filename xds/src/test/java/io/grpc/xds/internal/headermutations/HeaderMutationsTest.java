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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderValueOption.HeaderAppendAction;
import org.junit.Test;

public class HeaderMutationsTest {
  @Test
  public void testCreate() {
    HeaderValueOption reqHeader = HeaderValueOption.create(
        HeaderValue.create("req-key", "req-value"),
        HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD, false);
    RequestHeaderMutations requestMutations = RequestHeaderMutations
        .create(ImmutableList.of(reqHeader), ImmutableList.of("remove-req-key"));
    assertThat(requestMutations.headers()).containsExactly(reqHeader);
    assertThat(requestMutations.headersToRemove()).containsExactly("remove-req-key");

    HeaderValueOption respHeader = HeaderValueOption.create(
        HeaderValue.create("resp-key", "resp-value"),
        HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD, false);
    ResponseHeaderMutations responseMutations =
        ResponseHeaderMutations.create(ImmutableList.of(respHeader));
    assertThat(responseMutations.headers()).containsExactly(respHeader);

    HeaderMutations mutations = HeaderMutations.create(requestMutations, responseMutations);
    assertThat(mutations.requestMutations()).isEqualTo(requestMutations);
    assertThat(mutations.responseMutations()).isEqualTo(responseMutations);
  }
}
