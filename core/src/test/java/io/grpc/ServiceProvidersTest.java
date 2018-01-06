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

import java.util.Collections;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServiceProviders}. */
@RunWith(JUnit4.class)
public class ServiceProvidersTest {
  private static final List<String> NO_HARDCODED = Collections.emptyList();
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
  public void multipleProvider() {
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
    ClassLoader cl = new ReplacingClassLoader(getClass().getClassLoader(), serviceFile,
        "io/grpc/ServiceProvidersTest$FooProvider-failAtInitProvider.txt");
    assertNull(ServiceProviders.load(FooProvider.class, NO_HARDCODED, cl));
  }

  @Test
  public void getCandidatesViaHardCoded_triesToLoadClasses() throws Exception {
    ClassLoader cl = getClass().getClassLoader();
    final AtomicBoolean classLoaded = new AtomicBoolean();
    cl = new ClassLoader(cl) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(Available0Provider.class.getCanonicalName())) {
          classLoaded.set(true);
        }
        return super.loadClass(name, resolve);
      }
    };
    cl = new StaticTestingClassLoader(cl, Pattern.compile("io\\.grpc\\..*"));
    ServiceProviders.getCandidatesViaHardCoded(
        FooProvider.class,
        Collections.singletonList(Available0Provider.class.getCanonicalName()),
        cl);
    assertTrue(classLoaded.get());
  }

  @Test
  public void getCandidatesViaHardCoded_ignoresMissingClasses() throws Exception {
    ClassLoader cl = getClass().getClassLoader();
    cl = new ClassLoader(cl) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.contains(Available0Provider.class.getSimpleName())) {
          throw new ClassNotFoundException();
        } else {
          return super.loadClass(name, resolve);
        }
      }
    };
    cl = new StaticTestingClassLoader(cl, Pattern.compile("io\\.grpc\\.[^.]*"));
    Iterable<?> i = ServiceProviders.getCandidatesViaHardCoded(
        FooProvider.class,
        Collections.singletonList(Available0Provider.class.getCanonicalName()),
        cl);
    assertFalse("Iterator should be empty", i.iterator().hasNext());
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
  public abstract static class FooProvider implements ServiceProvider {}

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

  public static class Available0Provider extends BaseProvider {
    public Available0Provider() {
      super(true, 0);
    }
  }

  public static class Available5Provider extends BaseProvider {
    public Available5Provider() {
      super(true, 5);
    }
  }

  public static class Available7Provider extends BaseProvider {
    public Available7Provider() {
      super(true, 7);
    }
  }

  public static class UnavailableProvider extends BaseProvider {
    public UnavailableProvider() {
      super(false, 10);
    }
  }

  public static class FailAtInitProvider extends FooProvider {
    public FailAtInitProvider() {
      throw new RuntimeException("purposefully broken");
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
}
