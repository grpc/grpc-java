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
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.NameResolver.ConfigOrError;
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

  private final ManagedChannelServiceConfig serviceConfig1 = new ManagedChannelServiceConfig(
      Collections.<String, MethodInfo>emptyMap(),
      Collections.<String, MethodInfo>emptyMap(),
      null,
      null);
  private final ManagedChannelServiceConfig serviceConfig2 = new ManagedChannelServiceConfig(
      Collections.<String, MethodInfo>emptyMap(),
      Collections.<String, MethodInfo>emptyMap(),
      null,
      null);
  private final ConfigOrError config1 = ConfigOrError.fromConfig(serviceConfig1);
  private final ConfigOrError config2 = ConfigOrError.fromConfig(serviceConfig2);
  private final Status serviceConfigError1 = Status.UNKNOWN.withDescription("bang");
  private final Status serviceConfigError2 = Status.INTERNAL.withDescription("boom");
  private final ConfigOrError error1 = ConfigOrError.fromError(serviceConfigError1);
  private final ConfigOrError error2 = ConfigOrError.fromError(serviceConfigError2);
  private final ManagedChannelServiceConfig noServiceConfig = null;
  private final ConfigOrError noConfig = null;

  @Test
  public void noLookup_default() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, false);

    assertFalse(scs.expectUpdates());
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void noLookup_default_allUpdatesFail() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, false);

    try {
      scs.update(error1);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    try {
      scs.update(error1);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    try {
      scs.update(noConfig);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void noLookup_noDefault() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, false);

    assertFalse(scs.expectUpdates());
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());
  }

  @Test
  public void noLookup_noDefault_allUpdatesFail() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, false);

    try {
      scs.update(error1);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());

    try {
      scs.update(config2);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());

    try {
      scs.update(noConfig);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("unexpected service config update");
    }
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());
  }

  @Test
  public void lookup_noDefault() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    assertTrue(scs.expectUpdates());
    assertTrue(scs.shouldWaitOnServiceConfig());
    try {
      scs.getCurrent();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("still waiting on service config");
    }
  }

  @Test
  public void lookup_noDefault_onError_onError() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(error1, scs.getCurrent());

    scs.update(error2);
    assertSame(error2, scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onError_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(error1, scs.getCurrent());

    scs.update(noConfig);
    assertNull(scs.getCurrent());

    scs.update(error2);
    assertNull(scs.getCurrent()); //ignores future errors
  }

  @Test
  public void lookup_noDefault_onError_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(error1, scs.getCurrent());

    scs.update(config1);
    assertSame(config1, scs.getCurrent());

    scs.update(error2);
    assertSame(config1, scs.getCurrent()); //ignores future errors
  }

  @Test
  public void lookup_noDefault_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onAbsent_onError() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());

    scs.update(error1);
    assertNull(scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onAbsent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());

    scs.update(noConfig);
    assertNull(scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onAbsent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertNull(scs.getCurrent());

    scs.update(config1);
    assertSame(config1, scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onPresent_onError() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrent());

    scs.update(error1);
    assertSame(config1, scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onPresent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrent());

    scs.update(noConfig);
    assertNull(scs.getCurrent());
  }

  @Test
  public void lookup_noDefault_onPresent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(noServiceConfig, true);

    scs.update(config1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config1, scs.getCurrent());

    scs.update(config2);
    assertSame(config2, scs.getCurrent());
  }

  @Test
  public void lookup_default() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    assertTrue(scs.expectUpdates());
    assertTrue(scs.shouldWaitOnServiceConfig());
    try {
      scs.getCurrent();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("still waiting on service config");
    }
  }

  @Test
  public void lookup_default_onError() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onError_onError() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    scs.update(error2);
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onError_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    scs.update(noConfig);
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onError_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(error1);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    scs.update(config2);
    assertSame(config2, scs.getCurrent());
  }

  @Test
  public void lookup_default_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onAbsent_onError() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    scs.update(error1);
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onAbsent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    scs.update(noConfig);
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onAbsent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(noConfig);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSameConfig(config1, scs.getCurrent());

    scs.update(config2);
    assertSame(config2, scs.getCurrent());
  }

  @Test
  public void lookup_default_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrent());
  }

  @Test
  public void lookup_default_onPresent_onError() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrent());

    scs.update(error1);
    assertSame(config2, scs.getCurrent());
  }

  @Test
  public void lookup_default_onPresent_onAbsent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrent());

    scs.update(noConfig);
    assertSameConfig(config1, scs.getCurrent());
  }

  @Test
  public void lookup_default_onPresent_onPresent() {
    ServiceConfigState scs = new ServiceConfigState(serviceConfig1, true);
    ManagedChannelServiceConfig serviceConfig3 = new ManagedChannelServiceConfig(
        Collections.<String, MethodInfo>emptyMap(),
        Collections.<String, MethodInfo>emptyMap(),
        null,
        null);
    ConfigOrError config3 = ConfigOrError.fromConfig(serviceConfig3);

    scs.update(config2);
    assertFalse(scs.shouldWaitOnServiceConfig());
    assertSame(config2, scs.getCurrent());

    scs.update(config3);
    assertSame(config3, scs.getCurrent());
  }

  private static void assertSameConfig(ConfigOrError expected, ConfigOrError actual) {
    if (actual == null || expected == null) {
      assertSame(expected, actual);
    }
    assertWithMessage(actual.toString()).that(actual.getConfig())
        .isSameInstanceAs(expected.getConfig());
  }
}
