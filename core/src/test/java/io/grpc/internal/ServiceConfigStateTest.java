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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.Status;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ServiceConfigState}.
 */
@RunWith(JUnit4.class)
public class ServiceConfigStateTest {

  private final ManagedChannelServiceConfig config1 = new ManagedChannelServiceConfig(
      Collections.<String, MethodInfo>emptyMap(),
      Collections.<String, MethodInfo>emptyMap(),
      null,
      null);
  private final ManagedChannelServiceConfig config2 = new ManagedChannelServiceConfig(
      Collections.<String, MethodInfo>emptyMap(),
      Collections.<String, MethodInfo>emptyMap(),
      null,
      null);
  private final ManagedChannelServiceConfig noConfig = null;
  private final Status error1 = Status.UNKNOWN.withDescription("bang");
  private final Status error2 = Status.INTERNAL.withDescription("boom");

  @Test
  public void noLookup_default() {
    ServiceConfigState scs = new ServiceConfigState(config1, false);

    assertFalse(scs.expectUpdates());
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void noLookup_default_allUpdatesFail() {
    ServiceConfigState scs = new ServiceConfigState(config1, false);

    try {
      scs.update(error1);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    try {
      scs.update(config2);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    try {
      scs.update(noConfig);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void noLookup_noDefault() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, false);

    assertFalse(scs.expectUpdates());
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void noLookup_noDefault_allUpdatesFail() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, false);

    try {
      scs.update(error1);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    try {
      scs.update(config2);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    try {
      scs.update(noConfig);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    assertTrue(scs.expectUpdates());
    assertTrue(scs.shouldWaitOnServiceConfig());
    try {
      scs.getCurrentServiceConfig();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("still waiting on service config");
    }
    try {
      scs.getCurrentError();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("still waiting on service config");
    }
  }

  @Test
  public void lookup_noDefault_onError_onError() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    assertTrue(scs.update(error1));
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertSame(error1, scs.getCurrentError());

    assertTrue(scs.update(error2));
    assertNull(scs.getCurrentServiceConfig());
    assertSame(error2, scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onError_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    assertTrue(scs.update(error1));
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertSame(error1, scs.getCurrentError());

    scs.update(noConfig);
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
    assertFalse(scs.update(error2)); //ignores future errors
  }

  @Test
  public void lookup_noDefault_onError_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertSame(error1, scs.getCurrentError());

    scs.update(config1);
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
    assertFalse(scs.update(error2)); //ignores future errors
  }

  @Test
  public void lookup_noDefault_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onAbsent_onError() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    assertFalse(scs.update(error1));
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onAbsent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(noConfig);
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onAbsent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(config1);
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onPresent_onError() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    assertFalse(scs.update(error1));
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onPresent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(noConfig);
    assertNull(scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_noDefault_onPresent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(config2);
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    assertTrue(scs.expectUpdates());
    assertTrue(scs.shouldWaitOnServiceConfig());
    try {
      scs.getCurrentServiceConfig();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("still waiting on service config");
    }
    try {
      scs.getCurrentError();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("still waiting on service config");
    }
  }

  @Test
  public void lookup_default_onError() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    assertFalse(scs.update(error1));
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onError_onError() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    assertFalse(scs.update(error1));
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    assertFalse(scs.update(error1));
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onError_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    assertFalse(scs.update(error1));
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(noConfig);
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onError_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    assertFalse(scs.update(error1));
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(config2);
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onAbsent_onError() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    assertFalse(scs.update(error1));
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onAbsent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(noConfig);
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onAbsent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(config2);
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onPresent_onError() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    assertFalse(scs.update(error1));
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onPresent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(noConfig);
    assertSame(config1, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }

  @Test
  public void lookup_default_onPresent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(config1, true);
    ManagedChannelServiceConfig config3 = new ManagedChannelServiceConfig(
        Collections.<String, MethodInfo>emptyMap(),
        Collections.<String, MethodInfo>emptyMap(),
        null,
        null);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());

    scs.update(config3);
    assertSame(config3, scs.getCurrentServiceConfig());
    assertNull(scs.getCurrentError());
  }
}
