/*
 * Copyright 2022 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConfiguratorRegistryTest {

  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(
          getClass().getClassLoader(), Pattern.compile("io\\.grpc\\.[^.]+"));

  @Test
  public void setConfigurators() throws Exception {
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderSet.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void setGlobalConfigurators_twice() throws Exception {
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderSetTwice.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void getBeforeSet() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(
            StaticTestingClassLoaderGetBeforeSet.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  // UsedReflectively
  public static final class StaticTestingClassLoaderSet implements Runnable {
    @Override
    public void run() {
      List<Configurator> configurators = Arrays.asList(new NoopConfigurator());

      ConfiguratorRegistry.getDefaultRegistry().setConfigurators(configurators);

      assertThat(ConfiguratorRegistry.getDefaultRegistry().getConfigurators())
          .isEqualTo(configurators);
    }
  }

  public static final class StaticTestingClassLoaderSetTwice implements Runnable {
    @Override
    public void run() {
      ConfiguratorRegistry.getDefaultRegistry()
          .setConfigurators(Arrays.asList(new NoopConfigurator()));
      try {
        ConfiguratorRegistry.getDefaultRegistry()
            .setConfigurators(Arrays.asList(new NoopConfigurator()));
        fail("should have failed for calling setConfigurators() again");
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageThat().isEqualTo("Configurators are already set");
      }
    }
  }

  public static final class StaticTestingClassLoaderGetBeforeSet implements Runnable {
    @Override
    public void run() {
      assertThat(ConfiguratorRegistry.getDefaultRegistry().getConfigurators()).isEmpty();
      NoopConfigurator noopConfigurator = new NoopConfigurator();
      ConfiguratorRegistry.getDefaultRegistry()
              .setConfigurators(Arrays.asList(noopConfigurator));
      assertThat(ConfiguratorRegistry.getDefaultRegistry().getConfigurators())
              .containsExactly(noopConfigurator);
      assertThat(InternalConfiguratorRegistry.getConfiguratorsCallCountBeforeSet()).isEqualTo(1);
    }
  }

  private static class NoopConfigurator implements Configurator {}
}
