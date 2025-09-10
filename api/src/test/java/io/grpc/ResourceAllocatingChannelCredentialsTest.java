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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ResourceAllocatingChannelCredentials}. */
@RunWith(JUnit4.class)
public class ResourceAllocatingChannelCredentialsTest {
  @Test
  public void withoutBearerTokenDelegatesCall() {
    ChannelCredentials channelChreds = new ChannelCredentials() {
      @Override
      public ChannelCredentials withoutBearerTokens() {
        return this;
      }
    };
    ImmutableList<Closeable> resources = ImmutableList.<Closeable>of();
    ChannelCredentials creds =
        ResourceAllocatingChannelCredentials.create(channelChreds, resources);
    assertThat(creds.withoutBearerTokens()).isEqualTo(channelChreds);
  }
}
