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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.grpc.ChannelCredentials;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ResourceAllocatingChannelCredentials}. */
@RunWith(JUnit4.class)
public class ResourceAllocatingChannelCredentialsTest {
  private ChannelCredentials channelCreds = new ChannelCredentials() {
    @Override
    public ChannelCredentials withoutBearerTokens() {
      return this;
    }
  };

  private Closeable mockCloseable = mock(Closeable.class);

  @SuppressWarnings("unchecked")
  private Supplier<ImmutableList<Closeable>> mockSupplier =
      (Supplier<ImmutableList<Closeable>>) mock(Supplier.class);

  private ResourceAllocatingChannelCredentials unit;

  @Before
  public void setUp() throws Exception {
    Constructor<ResourceAllocatingChannelCredentials> ctor =
        ResourceAllocatingChannelCredentials.class.getDeclaredConstructor(
            ChannelCredentials.class, Supplier.class);
    ctor.setAccessible(true);
    this.unit = ctor.newInstance(channelCreds, mockSupplier);

    when(mockSupplier.get()).thenReturn(ImmutableList.of(mockCloseable));
  }

  @Test
  public void withoutBearerTokenThrows() {
    Exception ex = assertThrows(UnsupportedOperationException.class, () -> {
      unit.withoutBearerTokens();
    });

    String expectedMsg = "Cannot get stripped tokens";
    String actualMsg = ex.getMessage();

    assertThat(actualMsg).isEqualTo(expectedMsg);
  }

  @Test
  public void channelCredentialsAcquiredAndReleasedEqualNumberOfTimes() throws IOException {
    int cycles = 5;

    for (int idx = 0; idx < cycles; ++idx) {
      assertSame(unit.acquireChannelCredentials(), channelCreds);
    }

    for (int idx = 0; idx < cycles; ++idx) {
      unit.releaseChannelCredentials();
    }

    verify(mockCloseable, times(1)).close();
  }

  @Test
  public void channelCredentialsReleasedMoreTimesThanAcquired() {
    assertSame(unit.acquireChannelCredentials(), channelCreds);
    unit.releaseChannelCredentials();

    Exception ex = assertThrows(IllegalStateException.class, () -> {
      unit.releaseChannelCredentials();
    });

    String expectedMsg = "Channel credentials were released more times than they were acquired";
    String actualMsg = ex.getMessage();

    assertThat(actualMsg).isEqualTo(expectedMsg);
  }



  @Test
  public void channelCredentialsAcquiredMoreTimesThanReleased() throws IOException {
    assertSame(unit.acquireChannelCredentials(), channelCreds);
    assertSame(unit.acquireChannelCredentials(), channelCreds);
    unit.releaseChannelCredentials();

    verify(mockCloseable, never()).close();
  }
}
