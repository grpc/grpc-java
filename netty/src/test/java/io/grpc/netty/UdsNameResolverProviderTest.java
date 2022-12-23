/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.netty.channel.unix.DomainSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link UdsNameResolverProvider}. */
@RunWith(JUnit4.class)
public class UdsNameResolverProviderTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private NameResolver.Listener2 mockListener;

  @Captor
  private ArgumentCaptor<NameResolver.ResolutionResult> resultCaptor;

  UdsNameResolverProvider udsNameResolverProvider = new UdsNameResolverProvider();


  @Test
  public void testUnixRelativePath() {
    UdsNameResolver udsNameResolver =
        udsNameResolverProvider.newNameResolver(URI.create("unix:sock.sock"), null);
    assertThat(udsNameResolver).isNotNull();
    udsNameResolver.start(mockListener);
    verify(mockListener).onResult(resultCaptor.capture());
    NameResolver.ResolutionResult result = resultCaptor.getValue();
    List<EquivalentAddressGroup> list = result.getAddresses();
    assertThat(list).isNotNull();
    assertThat(list).hasSize(1);
    EquivalentAddressGroup eag = list.get(0);
    assertThat(eag).isNotNull();
    List<SocketAddress> addresses = eag.getAddresses();
    assertThat(addresses).hasSize(1);
    assertThat(addresses.get(0)).isInstanceOf(DomainSocketAddress.class);
    DomainSocketAddress domainSocketAddress = (DomainSocketAddress) addresses.get(0);
    assertThat(domainSocketAddress.path()).isEqualTo("sock.sock");
  }

  @Test
  public void testUnixAbsolutePath() {
    UdsNameResolver udsNameResolver =
        udsNameResolverProvider.newNameResolver(URI.create("unix:/sock.sock"), null);
    assertThat(udsNameResolver).isNotNull();
    udsNameResolver.start(mockListener);
    verify(mockListener).onResult(resultCaptor.capture());
    NameResolver.ResolutionResult result = resultCaptor.getValue();
    List<EquivalentAddressGroup> list = result.getAddresses();
    assertThat(list).isNotNull();
    assertThat(list).hasSize(1);
    EquivalentAddressGroup eag = list.get(0);
    assertThat(eag).isNotNull();
    List<SocketAddress> addresses = eag.getAddresses();
    assertThat(addresses).hasSize(1);
    assertThat(addresses.get(0)).isInstanceOf(DomainSocketAddress.class);
    DomainSocketAddress domainSocketAddress = (DomainSocketAddress) addresses.get(0);
    assertThat(domainSocketAddress.path()).isEqualTo("/sock.sock");
  }

  @Test
  public void testUnixAbsoluteAlternatePath() {
    UdsNameResolver udsNameResolver =
        udsNameResolverProvider.newNameResolver(URI.create("unix:///sock.sock"), null);
    assertThat(udsNameResolver).isNotNull();
    udsNameResolver.start(mockListener);
    verify(mockListener).onResult(resultCaptor.capture());
    NameResolver.ResolutionResult result = resultCaptor.getValue();
    List<EquivalentAddressGroup> list = result.getAddresses();
    assertThat(list).isNotNull();
    assertThat(list).hasSize(1);
    EquivalentAddressGroup eag = list.get(0);
    assertThat(eag).isNotNull();
    List<SocketAddress> addresses = eag.getAddresses();
    assertThat(addresses).hasSize(1);
    assertThat(addresses.get(0)).isInstanceOf(DomainSocketAddress.class);
    DomainSocketAddress domainSocketAddress = (DomainSocketAddress) addresses.get(0);
    assertThat(domainSocketAddress.path()).isEqualTo("/sock.sock");
  }

  @Test
  public void testUnixPathWithAuthority() {
    try {
      udsNameResolverProvider.newNameResolver(URI.create("unix://localhost/sock.sock"), null);
      fail("exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().isEqualTo("non-null authority not supported");
    }
  }
}
