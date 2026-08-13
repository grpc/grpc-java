/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.internal;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link RefCountedClientTransportFactory}. */
@RunWith(JUnit4.class)
public class RefCountedClientTransportFactoryTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ClientTransportFactory mockDelegate;

  @Test
  public void singleClose_closesDelegate() {
    RefCountedClientTransportFactory factory = new RefCountedClientTransportFactory(mockDelegate);
    factory.close();
    verify(mockDelegate).close();
  }

  @Test
  public void retainAndClose_closesDelegateOnlyWhenCountReachesZero() {
    RefCountedClientTransportFactory factory = new RefCountedClientTransportFactory(mockDelegate);
    RefCountedClientTransportFactory retained = factory.retain();

    factory.close();
    verify(mockDelegate, never()).close();

    retained.close();
    verify(mockDelegate).close();
  }

  @Test
  public void multipleRetains_requiresEqualClosesToCloseDelegate() {
    RefCountedClientTransportFactory factory = new RefCountedClientTransportFactory(mockDelegate);
    factory.retain();
    factory.retain();

    factory.close();
    verify(mockDelegate, never()).close();

    factory.close();
    verify(mockDelegate, never()).close();

    factory.close();
    verify(mockDelegate).close();
  }

  @Test
  public void closeMoreThanRetain_throwsIllegalStateException() {
    RefCountedClientTransportFactory factory = new RefCountedClientTransportFactory(mockDelegate);
    factory.close();
    verify(mockDelegate).close();

    assertThrows(IllegalStateException.class, factory::close);
  }
}
