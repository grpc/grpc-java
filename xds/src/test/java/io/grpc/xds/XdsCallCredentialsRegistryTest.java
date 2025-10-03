/*
 * Copyright 2025 The gRPC Authors
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsCallCredentialsRegistry}. */
@RunWith(JUnit4.class)
public class XdsCallCredentialsRegistryTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  XdsCallCredentialsProvider mockedProvider;

  String providerName = "test_creds_provider";

  XdsCallCredentialsRegistry unit;

  @Before
  public void setUp() {
    when(mockedProvider.getName()).thenReturn(providerName);
    unit = XdsCallCredentialsRegistry.newRegistry().register(mockedProvider);
  }

  @Test
  public void getDefaultRegistry_returnsSameInstance() {
    XdsCallCredentialsRegistry reg1 = XdsCallCredentialsRegistry.getDefaultRegistry();
    XdsCallCredentialsRegistry reg2 = XdsCallCredentialsRegistry.getDefaultRegistry();

    assertThat(reg1).isNotNull();
    assertThat(reg2).isNotNull();
    assertSame(reg1, reg2);
  }

  @Test
  public void getProvider_throwsWhenProviderNameIsNull() {
    try {
      unit.getProvider(null);
      fail("Should throw");
    } catch (NullPointerException e) {
      assertThat(e).hasMessageThat().contains("name");
    }
  }

  @Test
  public void getProvider_returnsRegisteredProvider() {
    XdsCallCredentialsProvider provider = unit.getProvider(providerName);
    assertThat(provider).isNotNull();
  }


  @Test
  public void getProvider_returnsNullForNotExistingProvider() {
    String notExistingProviderName = "gRPC";
    XdsCallCredentialsProvider provider = unit.getProvider(notExistingProviderName);
    assertThat(provider).isNull();
  }

  @Test
  public void defaultRegistry_providers() {
    Map<String, XdsCallCredentialsProvider> providers =
            XdsCallCredentialsRegistry.getDefaultRegistry().providers();
    assertThat(providers).hasSize(0);
  }
}
