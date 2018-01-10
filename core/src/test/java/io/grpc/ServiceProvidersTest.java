/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServiceProviders}. */
@RunWith(JUnit4.class)
public class ServiceProvidersTest {
  private static final List<Class<?>> NO_HARDCODED = Collections.emptyList();
  private final String serviceFile = "META-INF/services/io.grpc.ServiceProvidersTest$FooProvider";

  @Test
  public void contextClassLoaderProvider() {
    ClassLoader ccl = Thread.currentThread().getContextClassLoader();
    try {
      ClassLoader cl = new ReplacingClassLoader(
          getClass().getClassLoader(),
          serviceFile,
          "io/grpc/ServiceProvidersTest$FooProvider-multipleProvider.txt");

      // test that the context classloader is used as fallback
      ClassLoader rcll = new ReplacingClassLoader(
          getClass().getClassLoader(),
          serviceFile,
          "io/grpc/ServiceProvidersTest$FooProvider-empty.txt");
      Thread.currentThread().setContextClassLoader(rcll);
      assertEquals(
          Available7Provider.class,
          ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl).getClass());
    } finally {
      Thread.currentThread().setContextClassLoader(ccl);
    }
  }

  @Test
  public void noProvider() {
    ClassLoader ccl = Thread.currentThread().getContextClassLoader();
    try {
      ClassLoader cl = new ReplacingClassLoader(
          getClass().getClassLoader(),
          serviceFile,
          "io/grpc/ServiceProvidersTest$FooProvider-doesNotExist.txt");
      Thread.currentThread().setContextClassLoader(cl);
      assertNull(ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl));
    } finally {
      Thread.currentThread().setContextClassLoader(ccl);
    }
  }

  @Test
  public void multipleProvider() throws Exception {
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-multipleProvider.txt");
    assertSame(
        Available7Provider.class,
        ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl).getClass());

    List<FooProvider> providers = ServiceProviders.loadAll(
          FooProvider.class, NO_HARDCODED, cl);
    assertEquals(3, providers.size());
    assertEquals(Available7Provider.class, providers.get(0).getClass());
    assertEquals(Available5Provider.class, providers.get(1).getClass());
    assertEquals(Available0Provider.class, providers.get(2).getClass());
  }

  @Test
  public void unavailableProvider() {
    // tries to load Available7 and UnavailableProvider, which has priority 10
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-unavailableProvider.txt");
    assertEquals(
        Available7Provider.class,
        ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl).getClass());
  }

  @Test
  public void unknownClassProvider() {
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-unknownClassProvider.txt");
    assertNull(ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl));
  }

  @Test
  public void failAtInitProvider() {
    // Even though there is a working provider, if any providers fail then we should fail completely
    // to avoid returning something unexpected.
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-failAtInitProvider.txt");
    assertNull(ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl));
  }

  @Test
  public void failAtPriorityProvider() {
    // Even though there is a working provider, if any providers fail then we should fail completely
    // to avoid returning something unexpected.
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-failAtPriorityProvider.txt");
    assertNull(ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl));
  }

  @Test
  public void failAtAvailableProvider() {
    // Even though there is a working provider, if any providers fail then we should fail completely
    // to avoid returning something unexpected.
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-failAtAvailableProvider.txt");
    assertNull(ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl));
  }

  @Test
  public void getCandidatesViaHardCoded_multipleProvider() throws Exception {
    Iterator<FooProvider> candidates = ServiceProviders.getCandidatesViaHardCoded(
        FooProvider.class,
        ImmutableList.<Class<?>>of(
            Available7Provider.class,
            Available0Provider.class))
        .iterator();
    assertEquals(Available7Provider.class, candidates.next().getClass());
    assertEquals(Available0Provider.class, candidates.next().getClass());
    assertFalse(candidates.hasNext());
  }

  @Test
  public void getCandidatesViaHardCoded_failAtInitOnly() throws Exception {
    Iterable<FooProvider> i = ServiceProviders.getCandidatesViaHardCoded(
        FooProvider.class,
        Collections.<Class<?>>singletonList(FailAtInitProvider.class));
    assertFalse(i.iterator().hasNext());
  }

  @Test
  public void getCandidatesViaHardCoded_failAtInitSome() throws Exception {
    Iterable<FooProvider> i = ServiceProviders.getCandidatesViaHardCoded(
        FooProvider.class,
        ImmutableList.<Class<?>>of(FailAtInitProvider.class, Available0Provider.class));
    assertFalse(i.iterator().hasNext());
  }

  @Test
  public void create_throwsErrorOnMisconfiguration() throws Exception {
    class PrivateClass {}

    try {
      FooProvider ignored = ServiceProviders.create(FooProvider.class, PrivateClass.class);
      fail("Expected exception");
    } catch (ServiceConfigurationError e) {
      assertTrue("Expected ClassCastException cause: " + e.getCause(),
          e.getCause() instanceof ClassCastException);
    }
  }

  /**
   * A provider class for this unit test.
   */
  public abstract static class FooProvider extends ServiceProvider {}

  private static class BaseProvider extends FooProvider {
    private final boolean isAvailable;
    private final int priority;

    public BaseProvider(boolean isAvailable, int priority) {
      this.isAvailable = isAvailable;
      this.priority = priority;
    }

    @Override
    public boolean isAvailable() {
      return isAvailable;
    }

    @Override
    public int priority() {
      return priority;
    }
  }

  public static final class Available0Provider extends BaseProvider {
    public Available0Provider() {
      super(true, 0);
    }
  }

  public static final class Available5Provider extends BaseProvider {
    public Available5Provider() {
      super(true, 5);
    }
  }

  public static final class Available7Provider extends BaseProvider {
    public Available7Provider() {
      super(true, 7);
    }
  }

  public static final class UnavailableProvider extends BaseProvider {
    public UnavailableProvider() {
      super(false, 10);
    }
  }

  public static final class FailAtInitProvider extends FooProvider {
    public FailAtInitProvider() {
      throw new RuntimeException("intentionally broken");
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int priority() {
      return 0;
    }
  }

  public static final class FailAtPriorityProvider extends FooProvider {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int priority() {
      throw new RuntimeException("intentionally broken");
    }
  }

  public static final class FailAtAvailableProvider extends FooProvider {
    @Override
    public boolean isAvailable() {
      throw new RuntimeException("intentionally broken");
    }

    @Override
    public int priority() {
      return 0;
    }
  }
}
