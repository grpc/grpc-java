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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelCredentials;
import io.grpc.xds.XdsCredentialsProvider;
import io.grpc.xds.XdsCredentialsRegistry;
import io.grpc.xds.internal.GoogleDefaultXdsCredentialsProvider;
import io.grpc.xds.internal.InsecureXdsCredentialsProvider;
import io.grpc.xds.internal.TlsXdsCredentialsProvider;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XdsCredentialsRegistry}. */
@RunWith(JUnit4.class)
public class XdsCredentialsRegistryTest {

  @Test
  public void register_unavailableProviderThrows() {
    XdsCredentialsRegistry reg = new XdsCredentialsRegistry();
    try {
      reg.register(new BaseCredsProvider(false, 5, "creds"));
      fail("Should throw");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("isAvailable() returned false");
    }
    assertThat(reg.providers()).isEmpty();
  }

  @Test
  public void deregister() {
    XdsCredentialsRegistry reg = new XdsCredentialsRegistry();
    String credsName = "sampleCredsName";
    XdsCredentialsProvider p1 = new BaseCredsProvider(true, 5, credsName);
    XdsCredentialsProvider p2 = new BaseCredsProvider(true, 5, credsName);
    XdsCredentialsProvider p3 = new BaseCredsProvider(true, 5, credsName);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    assertThat(reg.getProvider(credsName)).isSameInstanceAs(p1);
    reg.deregister(p2);
    assertThat(reg.getProvider(credsName)).isSameInstanceAs(p1);
    reg.deregister(p1);
    assertThat(reg.getProvider(credsName)).isSameInstanceAs(p3);

  }

  @Test
  public void provider_sorted() {
    XdsCredentialsRegistry reg = new XdsCredentialsRegistry();
    String credsName = "sampleCredsName";
    XdsCredentialsProvider p1 = new BaseCredsProvider(true, 5, credsName);
    XdsCredentialsProvider p2 = new BaseCredsProvider(true, 3, credsName);
    XdsCredentialsProvider p3 = new BaseCredsProvider(true, 8, credsName);
    XdsCredentialsProvider p4 = new BaseCredsProvider(true, 3, credsName);
    XdsCredentialsProvider p5 = new BaseCredsProvider(true, 8, credsName);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    reg.register(p4);
    reg.register(p5);
    assertThat(reg.getProvider(credsName)).isSameInstanceAs(p3);
  }

  @Test
  public void channelCredentials_successful() {
    XdsCredentialsRegistry registry = new XdsCredentialsRegistry();
    String credsName = "sampleCredsName";

    registry.register(
        new BaseCredsProvider(true, 5, credsName) {
        @Override
        public ChannelCredentials newChannelCredentials(Map<String, ?> config) {
          return new SampleChannelCredentials(config);
        }
      });

    ImmutableMap<String, String> sampleConfig = ImmutableMap.of("a", "b");
    ChannelCredentials creds = registry.providers().get(credsName)
        .newChannelCredentials(sampleConfig);
    assertSame(SampleChannelCredentials.class, creds.getClass());
    assertEquals(sampleConfig, ((SampleChannelCredentials)creds).getConfig());
  }

  @Test
  public void channelCredentials_multiSuccessful() {
    XdsCredentialsRegistry registry = new XdsCredentialsRegistry();
    String credsName1 = "sampleCreds1";
    String credsName2 = "sampleCreds2";
    registry.register(
        new BaseCredsProvider(true, 5, credsName1) {
        @Override
        public ChannelCredentials newChannelCredentials(Map<String, ?> config) {
          return null;
        }
      });

    registry.register(
        new BaseCredsProvider(true, 7, credsName2) {
        @Override
        public ChannelCredentials newChannelCredentials(Map<String, ?> config) {
          return new SampleChannelCredentials(config);
        }
      });

    assertThat(registry.getProvider(credsName1).newChannelCredentials(null)).isNull();
    assertThat(registry.getProvider(credsName1).getName()).isEqualTo(credsName1);
    assertThat(registry.getProvider(credsName2).newChannelCredentials(null)).isNotNull();
    assertThat(registry.getProvider(credsName2).getName()).isEqualTo(credsName2);
  }

  @Test
  public void defaultRegistry_providers() {
    Map<String, XdsCredentialsProvider> providers =
            XdsCredentialsRegistry.getDefaultRegistry().providers();
    assertThat(providers).hasSize(3);
    assertThat(providers.get("google_default").getClass())
        .isEqualTo(GoogleDefaultXdsCredentialsProvider.class);
    assertThat(providers.get("insecure").getClass())
        .isEqualTo(InsecureXdsCredentialsProvider.class);
    assertThat(providers.get("tls").getClass())
        .isEqualTo(TlsXdsCredentialsProvider.class);
  }

  @Test
  public void getClassesViaHardcoded_classesPresent() throws Exception {
    List<Class<?>> classes = XdsCredentialsRegistry.getHardCodedClasses();
    assertThat(classes).containsExactly(
        GoogleDefaultXdsCredentialsProvider.class,
        InsecureXdsCredentialsProvider.class,
        TlsXdsCredentialsProvider.class);
  }

  @Test
  public void getProvider_null() {
    try {
      XdsCredentialsRegistry.getDefaultRegistry().getProvider(null);
      fail("Should throw");
    } catch (NullPointerException e) {
      assertThat(e).hasMessageThat().contains("name");
    }
  }


  private static class BaseCredsProvider extends XdsCredentialsProvider {
    private final boolean isAvailable;
    private final int priority;
    private final String name;

    public BaseCredsProvider(boolean isAvailable, int priority, String name) {
      this.isAvailable = isAvailable;
      this.priority = priority;
      this.name = name;
    }

    @Override
    protected String getName() {
      return name;
    }

    @Override
    public boolean isAvailable() {
      return isAvailable;
    }

    @Override
    public int priority() {
      return priority;
    }

    @Override
    public ChannelCredentials newChannelCredentials(Map<String, ?> config) {
      throw new UnsupportedOperationException();
    }
  }

  private static class SampleChannelCredentials extends ChannelCredentials {
    private final Map<String, ?> config;

    SampleChannelCredentials(Map<String, ?> config) {
      this.config = config;
    }

    public Map<String, ?> getConfig() {
      return config;
    }

    @Override
    public ChannelCredentials withoutBearerTokens() {
      return this;
    }
  }
}
