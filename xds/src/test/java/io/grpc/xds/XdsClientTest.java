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

import io.grpc.xds.XdsClient.ClusterUpdate;
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
  public void buildClusterUpdate_defaultToClusterNameWhenEdsServiceNameNotSet() {
    ClusterUpdate clusterUpdate1 =
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("bar.googleapis.com")
            .setLbPolicy("round_robin")
            .build();
    assertThat(clusterUpdate1.getEdsServiceName()).isEqualTo("bar.googleapis.com");

    ClusterUpdate clusterUpdate2 =
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setLbPolicy("round_robin")
            .build();
    assertThat(clusterUpdate2.getEdsServiceName()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectShouldMatchReturnObject() {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    RefCountedXdsClientObjectPool xdsClientRef =
        new RefCountedXdsClientObjectPool(xdsClientFactory);

    // getObject once
    XdsClient xdsClient = xdsClientRef.getObject();
    assertThat(xdsClient).isNotNull();
    // getObject twice
    assertThat(xdsClientRef.getObject()).isSameInstanceAs(xdsClient);
    // returnObject once
    assertThat(xdsClientRef.returnObject(xdsClient)).isNull();
    verify(xdsClient, never()).shutdown();
    // returnObject twice
    assertThat(xdsClientRef.returnObject(xdsClient)).isNull();
    verify(xdsClient).shutdown();
    assertThat(xdsClientRef.xdsClient).isNull();

    thrown.expect(IllegalStateException.class);
    // returnOject for the 3rd time
    xdsClientRef.returnObject(xdsClient);
  }

  @Test
  public void refCountedXdsClientObjectPool_returnWrongObjectShouldThrow() {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    RefCountedXdsClientObjectPool xdsClientRef =
        new RefCountedXdsClientObjectPool(xdsClientFactory);

    xdsClientRef.getObject();

    thrown.expect(IllegalStateException.class);
    xdsClientRef.returnObject(mock(XdsClient.class));
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectCreatesNewInstanceIfAlreadyShutdown() {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    RefCountedXdsClientObjectPool xdsClientRef =
        new RefCountedXdsClientObjectPool(xdsClientFactory);

    XdsClient xdsClient1 = xdsClientRef.getObject();
    verify(xdsClient1, never()).shutdown();
    assertThat(xdsClientRef.returnObject(xdsClient1)).isNull();
    verify(xdsClient1).shutdown();

    XdsClient xdsClient2 = xdsClientRef.getObject();
    assertThat(xdsClient2).isNotSameInstanceAs(xdsClient1);
  }
}
