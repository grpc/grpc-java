/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds.internal;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.CompositeChannelCredentials;
import io.grpc.InternalServiceProviders;
import io.grpc.xds.XdsCredentialsProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GoogleDefaultXdsCredentialsProvider}. */
@RunWith(JUnit4.class)
public class GoogleDefaultXdsCredentialsProviderTest {
  private GoogleDefaultXdsCredentialsProvider provider = new GoogleDefaultXdsCredentialsProvider();

  @Test
  public void provided() {
    for (XdsCredentialsProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
          XdsCredentialsProvider.class, getClass().getClassLoader())) {
      if (current instanceof GoogleDefaultXdsCredentialsProvider) {
        return;
      }
    }
    fail("ServiceLoader unable to load GoogleDefaultXdsCredentialsProvider");
  }

  @Test
  public void isAvailable() {
    assertTrue(provider.isAvailable());
  }

  @Test
  public void channelCredentials() {
    assertSame(CompositeChannelCredentials.class,
        provider.newChannelCredentials(null).getClass());
  }
}
