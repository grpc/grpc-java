/*
 * Copyright 2020 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.internal.ManagedChannelImplBuilder.ChannelBuilderDefaultPortProvider;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.FixedPortProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ManagedChannelImplBuilder}. */
@RunWith(JUnit4.class)
public class ManagedChannelImplBuilderTest {
  private static final int DUMMY_PORT = 42;
  private static final String DUMMY_TARGET = "fake-target";
  private static final String DUMMY_AUTHORITY_VALID = "valid:1234";
  private static final String DUMMY_AUTHORITY_INVALID = "[ : : 1]";

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Mock private ClientTransportFactoryBuilder mockClientTransportFactoryBuilder;
  @Mock private ChannelBuilderDefaultPortProvider mockChannelBuilderDefaultPortProvider;
  private ManagedChannelImplBuilder builder;

  @Before
  public void setUp() throws Exception {
    builder = new ManagedChannelImplBuilder(
        DUMMY_TARGET,
        mockClientTransportFactoryBuilder,
        mockChannelBuilderDefaultPortProvider);
  }

  /** Ensure buildTransportFactory() delegates to the custom implementation. */
  @Test
  public void buildTransportFactory() {
    final ClientTransportFactory clientTransportFactory = mock(ClientTransportFactory.class);
    when(mockClientTransportFactoryBuilder.buildClientTransportFactory())
        .thenReturn(clientTransportFactory);
    assertEquals(clientTransportFactory, builder.buildTransportFactory());
    verify(mockClientTransportFactoryBuilder).buildClientTransportFactory();
  }

  /** Ensure getDefaultPort() returns default port when no custom implementation provided. */
  @Test
  public void getDefaultPort_default() {
    final ManagedChannelImplBuilder builderNoPortProvider = new ManagedChannelImplBuilder(
        DUMMY_TARGET, mockClientTransportFactoryBuilder, null);
    assertEquals(GrpcUtil.DEFAULT_PORT_SSL, builderNoPortProvider.getDefaultPort());
  }

  /** Ensure getDefaultPort() delegates to the custom implementation. */
  @Test
  public void getDefaultPort_custom() {
    when(mockChannelBuilderDefaultPortProvider.getDefaultPort()).thenReturn(DUMMY_PORT);
    assertEquals(DUMMY_PORT, builder.getDefaultPort());
    verify(mockChannelBuilderDefaultPortProvider).getDefaultPort();
  }

  /** Test FixedPortProvider(int port). */
  @Test
  public void getDefaultPort_fixedPortProvider() {
    final ManagedChannelImplBuilder builderFixedPortProvider = new ManagedChannelImplBuilder(
        DUMMY_TARGET,
        mockClientTransportFactoryBuilder,
        new FixedPortProvider(DUMMY_PORT));
    assertEquals(DUMMY_PORT, builderFixedPortProvider.getDefaultPort());
  }

  @Test
  public void checkAuthority_validAuthorityAllowed() {
    assertEquals(DUMMY_AUTHORITY_VALID, builder.checkAuthority(DUMMY_AUTHORITY_VALID));
  }

  @Test
  public void checkAuthority_invalidAuthorityFailed() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority");

    builder.checkAuthority(DUMMY_AUTHORITY_INVALID);
  }

  @Test
  public void disableCheckAuthority_validAuthorityAllowed() {
    builder.disableCheckAuthority();
    assertEquals(DUMMY_AUTHORITY_VALID, builder.checkAuthority(DUMMY_AUTHORITY_VALID));
  }

  @Test
  public void disableCheckAuthority_invalidAuthorityAllowed() {
    builder.disableCheckAuthority();
    assertEquals(DUMMY_AUTHORITY_INVALID, builder.checkAuthority(DUMMY_AUTHORITY_INVALID));
  }

  @Test
  public void enableCheckAuthority_validAuthorityAllowed() {
    builder.disableCheckAuthority().enableCheckAuthority();
    assertEquals(DUMMY_AUTHORITY_VALID, builder.checkAuthority(DUMMY_AUTHORITY_VALID));
  }

  @Test
  public void disableCheckAuthority_invalidAuthorityFailed() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority");

    builder.disableCheckAuthority().enableCheckAuthority();
    builder.checkAuthority(DUMMY_AUTHORITY_INVALID);
  }

  /** Ensure authority check can disabled with custom authority check implementation. */
  @Test
  @SuppressWarnings("deprecation")
  public void overrideAuthorityChecker_default() {
    builder.overrideAuthorityChecker(
        new io.grpc.internal.ManagedChannelImplBuilder.OverrideAuthorityChecker() {
          @Override public String checkAuthority(String authority) {
            return authority;
          }
        });
    assertEquals(DUMMY_AUTHORITY_INVALID, builder.checkAuthority(DUMMY_AUTHORITY_INVALID));
  }

  /** Ensure custom authority is ignored after disableCheckAuthority(). */
  @Test
  @SuppressWarnings("deprecation")
  public void overrideAuthorityChecker_ignored() {
    builder.overrideAuthorityChecker(
        new io.grpc.internal.ManagedChannelImplBuilder.OverrideAuthorityChecker() {
          @Override public String checkAuthority(String authority) {
            throw new IllegalArgumentException();
          }
        });
    builder.disableCheckAuthority();
    assertEquals(DUMMY_AUTHORITY_INVALID, builder.checkAuthority(DUMMY_AUTHORITY_INVALID));
  }
}
