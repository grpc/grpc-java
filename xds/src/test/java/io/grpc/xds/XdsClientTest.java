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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsClientFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsClient}.
 */
@RunWith(JUnit4.class)
public class XdsClientTest {
  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void refCountedXdsClientObjectPool_getObjectShouldMatchReturnObject() {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(xdsClientFactory);

    // getObject once
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClient).isNotNull();
    // getObject twice
    assertThat(xdsClientPool.getObject()).isSameInstanceAs(xdsClient);
    // returnObject once
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    verify(xdsClient, never()).shutdown();
    // returnObject twice
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    verify(xdsClient).shutdown();
    assertThat(xdsClientPool.xdsClient).isNull();

    thrown.expect(IllegalStateException.class);
    // returnOject for the 3rd time
    xdsClientPool.returnObject(xdsClient);
  }

  @Test
  public void refCountedXdsClientObjectPool_returnWrongObjectShouldThrow() {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(xdsClientFactory);

    xdsClientPool.getObject();

    thrown.expect(IllegalStateException.class);
    xdsClientPool.returnObject(mock(XdsClient.class));
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectCreatesNewInstanceIfAlreadyShutdown() {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(xdsClientFactory);

    XdsClient xdsClient1 = xdsClientPool.getObject();
    verify(xdsClient1, never()).shutdown();
    assertThat(xdsClientPool.returnObject(xdsClient1)).isNull();
    verify(xdsClient1).shutdown();

    XdsClient xdsClient2 = xdsClientPool.getObject();
    assertThat(xdsClient2).isNotSameInstanceAs(xdsClient1);
  }
}
