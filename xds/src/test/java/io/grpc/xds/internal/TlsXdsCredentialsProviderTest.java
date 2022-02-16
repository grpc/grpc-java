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

import io.grpc.TlsChannelCredentials;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TlsXdsCredentialsProvider}. */
@RunWith(JUnit4.class)
public class TlsXdsCredentialsProviderTest {
  private TlsXdsCredentialsProvider provider = new TlsXdsCredentialsProvider();

  @Test
  public void isAvailable() {
    assertTrue(provider.isAvailable());
  }

  @Test
  public void channelCredentials() {
    assertSame(TlsChannelCredentials.class,
        provider.getChannelCredentials(null).getClass());
  }
}
