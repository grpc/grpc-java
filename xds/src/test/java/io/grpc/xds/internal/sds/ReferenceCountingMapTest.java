/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.xds.internal.sds.ReferenceCountingMap.ValueFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ReferenceCountingMap}. */
@RunWith(JUnit4.class)
public class ReferenceCountingMapTest {

  @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  ValueFactory<Integer, SslContextProvider> mockFactory;

  ReferenceCountingMap<Integer, SslContextProvider> map;

  @Before
  public void setUp() {
    map = new ReferenceCountingMap<>(mockFactory);
  }

  @Test
  public void referenceCountingMap_getAndRelease_closeCalled() throws InterruptedException {
    SslContextProvider valueFor3 = getTypedMock();
    when(mockFactory.create(3)).thenReturn(valueFor3);
    SslContextProvider val = map.get(3);
    assertThat(val).isSameInstanceAs(valueFor3);
    verify(valueFor3, never()).close();
    val = map.get(3);
    assertThat(val).isSameInstanceAs(valueFor3);
    // at this point ref-count is 2
    assertThat(map.release(3, val)).isNull();
    verify(valueFor3, never()).close();
    assertThat(map.release(3, val)).isNull(); // after this ref-count is 0
    verify(valueFor3, times(1)).close();
  }

  private static SslContextProvider getTypedMock() {
    return mock(SslContextProvider.class);
  }

  @Test
  public void referenceCountingMap_distinctElements() throws InterruptedException {
    SslContextProvider valueFor3 = getTypedMock();
    SslContextProvider valueFor4 = getTypedMock();
    when(mockFactory.create(3)).thenReturn(valueFor3);
    when(mockFactory.create(4)).thenReturn(valueFor4);
    SslContextProvider val3 = map.get(3);
    assertThat(val3).isSameInstanceAs(valueFor3);
    SslContextProvider val4 = map.get(4);
    assertThat(val4).isSameInstanceAs(valueFor4);
    assertThat(map.release(3, val3)).isNull();
    verify(valueFor3, times(1)).close();
    verify(valueFor4, never()).close();
    assertThat(map.release(4, val4)).isNull();
    verify(valueFor4, times(1)).close();
  }

  @Test
  public void referenceCountingMap_releaseWrongElement_expectException()
      throws InterruptedException {
    SslContextProvider valueFor3 = getTypedMock();
    SslContextProvider valueFor4 = getTypedMock();
    when(mockFactory.create(3)).thenReturn(valueFor3);
    when(mockFactory.create(4)).thenReturn(valueFor4);
    SslContextProvider unused = map.get(3);
    SslContextProvider val4 = map.get(4);
    // now provide wrong key (3) and value (val4) combination
    try {
      map.release(3, val4);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("Releasing the wrong instance");
    }
  }

  @Test
  public void referenceCountingMap_excessRelease_expectException() throws InterruptedException {
    SslContextProvider valueFor4 = getTypedMock();
    when(mockFactory.create(4)).thenReturn(valueFor4);
    SslContextProvider val = map.get(4);
    assertThat(val).isSameInstanceAs(valueFor4);
    // at this point ref-count is 1
    map.release(4, val);
    // at this point ref-count is 0
    try {
      map.release(4, val);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("No cached instance found for 4");
    }
  }

  @Test
  public void referenceCountingMap_releaseAndGet_differentInstance() throws InterruptedException {
    SslContextProvider valueFor4 = getTypedMock();
    when(mockFactory.create(4)).thenReturn(valueFor4);
    SslContextProvider val = map.get(4);
    assertThat(val).isSameInstanceAs(valueFor4);
    // at this point ref-count is 1
    map.release(4, val);
    // at this point ref-count is 0 and val is removed
    // should get another instance for 4
    SslContextProvider valueFor4a = getTypedMock();
    when(mockFactory.create(4)).thenReturn(valueFor4a);
    val = map.get(4);
    assertThat(val).isSameInstanceAs(valueFor4a);
    // verify it is a different instance from before
    assertThat(valueFor4).isNotSameInstanceAs(valueFor4a);
  }
}
