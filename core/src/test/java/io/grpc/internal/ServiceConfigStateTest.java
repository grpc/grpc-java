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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ServiceConfigState}.
 */
@RunWith(JUnit4.class)
public class ServiceConfigStateTest {

  @Rule public final ExpectedException thrown = ExpectedException.none();

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
  private final SynchronizationContext syncCtx = new SynchronizationContext(
      new UncaughtExceptionHandler() {

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  });

  @Test
  public void expectUpdates() {
    boolean lookupServiceConfig = true;
    ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    assertTrue(scs.expectUpdates());
  }

  @Test
  public void expectUpdates_noUpdates() {
    boolean lookupServiceConfig = false;
    ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    assertFalse(scs.expectUpdates());
  }

  @Test
  public void failsOnNullSync() {
    boolean lookupServiceConfig = true;
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("syncCtx");

    ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, null);
  }

  @Test
  public void waitOnServiceConfig_waitsNoDefault() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertTrue(scs.waitOnServiceConfig());
      }
    });
  }

  @Test
  public void waitOnServiceConfig_waitsWithDefault() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertTrue(scs.waitOnServiceConfig());
      }
    });
  }

  @Test
  public void waitOnServiceConfig_noWaitWithDefault() {
    boolean lookupServiceConfig = false;
    final ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertFalse(scs.waitOnServiceConfig());
        assertSame(config1, scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void waitOnServiceConfig_noWaitWithNoDefault() {
    boolean lookupServiceConfig = false;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertFalse(scs.waitOnServiceConfig());
        assertNull(scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void getCurrentServiceConfig_failsOnUnresolvedConfig() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("still waiting");

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        scs.getCurrentServiceConfig();
      }
    });
  }

  @Test
  public void getCurrentServiceConfig_defaultWithErrorWorks() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertFalse(scs.update(Status.INTERNAL.withDescription("bang")));
        assertSame(config1, scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void getCurrentServiceConfig_errorThenSuccess() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertFalse(scs.update(Status.INTERNAL.withDescription("bang")));
        scs.update(config2);
        assertSame(config2, scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void getCurrentServiceConfig_successThenError() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(config1, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        scs.update(config2);
        assertFalse(scs.update(Status.INTERNAL.withDescription("bang")));
        assertSame(config2, scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void getCurrentServiceConfig_noDefaultWithError() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        Status s = Status.INTERNAL.withDescription("bang");
        assertTrue(scs.update(s));
        assertSame(s, scs.getCurrentError());
        assertNull(scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void update_errorsOverwrite() {
    boolean lookupServiceConfig = true;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        assertTrue(scs.update(Status.INTERNAL.withDescription("boom")));

        Status s = Status.INTERNAL.withDescription("bang");
        assertTrue(scs.update(s));
        assertSame(s, scs.getCurrentError());
        assertNull(scs.getCurrentServiceConfig());
      }
    });
  }

  @Test
  public void update_failsOnUnexpectedError() {
    boolean lookupServiceConfig = false;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("unexpected service config update");

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        scs.update(Status.INTERNAL.withDescription("boom"));
      }
    });
  }

  @Test
  public void update_failsOnUnexpectedSuccess() {
    boolean lookupServiceConfig = false;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("unexpected service config update");

    syncCtx.execute(new Runnable() {
      @Override
      public void run() {
        scs.update(config2);
      }
    });
  }

  @Test
  public void update_failsOnOutsideOfSync() {
    boolean lookupServiceConfig = false;
    final ServiceConfigState scs = new ServiceConfigState(null, lookupServiceConfig, syncCtx);
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Not called from the SynchronizationContext");

    scs.update(config2);
  }
}
