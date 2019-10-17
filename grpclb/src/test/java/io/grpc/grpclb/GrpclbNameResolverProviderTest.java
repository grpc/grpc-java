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

package io.grpc.grpclb;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.BaseDnsNameResolverProvider.ENABLE_GRPCLB_PROPERTY_NAME;

import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpclbNameResolverProviderTest {

  @Nullable
  private String jndiSrvProperty;

  @Before
  public void memoizeSrvProperty() {
    jndiSrvProperty = System.getProperty(ENABLE_GRPCLB_PROPERTY_NAME);
  }

  @After
  public void restoreSrvProperty() {
    if (jndiSrvProperty == null) {
      System.clearProperty(ENABLE_GRPCLB_PROPERTY_NAME);
    } else {
      System.setProperty(ENABLE_GRPCLB_PROPERTY_NAME, jndiSrvProperty);
    }
  }

  @Test
  public void enableSrv_useDefault() {
    System.clearProperty(ENABLE_GRPCLB_PROPERTY_NAME);

    assertThat(new GrpclbNameResolverProvider().isSrvEnabled()).isTrue();
  }

  @Test
  public void enableSrv_defaultEnabled() {
    System.setProperty(ENABLE_GRPCLB_PROPERTY_NAME, "true");

    assertThat(new GrpclbNameResolverProvider().isSrvEnabled()).isTrue();
  }

  @Test
  public void enableSrv_defaultDisabled() {
    System.setProperty(ENABLE_GRPCLB_PROPERTY_NAME, "false");

    assertThat(new GrpclbNameResolverProvider().isSrvEnabled()).isFalse();
  }
}