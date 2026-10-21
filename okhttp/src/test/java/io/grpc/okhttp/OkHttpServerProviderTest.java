/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.okhttp;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import io.grpc.InsecureServerCredentials;
import io.grpc.ServerCredentials;
import io.grpc.ServerProvider;
import io.grpc.ServerRegistryAccessor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link OkHttpServerProvider}. */
@RunWith(JUnit4.class)
public class OkHttpServerProviderTest {
  private OkHttpServerProvider provider = new OkHttpServerProvider();

  @Test
  public void provided() {
    assertThat(ServerProvider.provider()).isInstanceOf(OkHttpServerProvider.class);
  }

  @Test
  public void providedHardCoded() {
    assertThat(ServerRegistryAccessor.getHardCodedClasses()).contains(OkHttpServerProvider.class);
  }

  @Test
  public void basicMethods() {
    assertThat(provider.isAvailable()).isTrue();
    assertThat(provider.priority()).isEqualTo(4);
  }

  @Test
  public void builderIsAOkHttpBuilder() {
    assertThrows(UnsupportedOperationException.class, () -> provider.builderForPort(80));
  }

  @Test
  public void newServerBuilderForPort_success() {
    ServerProvider.NewServerBuilderResult result =
        provider.newServerBuilderForPort(80, InsecureServerCredentials.create());
    assertThat(result.getServerBuilder()).isInstanceOf(OkHttpServerBuilder.class);
  }

  @Test
  public void newServerBuilderForPort_fail() {
    ServerProvider.NewServerBuilderResult result = provider.newServerBuilderForPort(
        80, new FakeServerCredentials());
    assertThat(result.getError()).contains("FakeServerCredentials");
  }

  private static final class FakeServerCredentials extends ServerCredentials {}
}
