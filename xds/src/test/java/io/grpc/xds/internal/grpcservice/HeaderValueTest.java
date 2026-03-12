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

package io.grpc.xds.internal.grpcservice;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderValueTest {

  @Test
  public void create_withStringValue_success() {
    HeaderValue headerValue = HeaderValue.create("key1", "value1");
    assertThat(headerValue.key()).isEqualTo("key1");
    assertThat(headerValue.value().isPresent()).isTrue();
    assertThat(headerValue.value().get()).isEqualTo("value1");
    assertThat(headerValue.rawValue().isPresent()).isFalse();
  }

  @Test
  public void create_withByteStringValue_success() {
    ByteString rawValue = ByteString.copyFromUtf8("raw_value");
    HeaderValue headerValue = HeaderValue.create("key2", rawValue);
    assertThat(headerValue.key()).isEqualTo("key2");
    assertThat(headerValue.rawValue().isPresent()).isTrue();
    assertThat(headerValue.rawValue().get()).isEqualTo(rawValue);
    assertThat(headerValue.value().isPresent()).isFalse();
  }


}
