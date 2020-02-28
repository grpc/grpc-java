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

package io.grpc.grpclb;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.DnsNameResolver.AddressResolver;
import io.grpc.internal.DnsNameResolver.ResourceResolver;
import io.grpc.internal.DnsNameResolver.SrvRecord;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link GrpclbNameResolver}. */
@RunWith(JUnit4.class)
public class GrpclbNameResolverTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String NAME = "foo.googleapis.com";
  private static final int DEFAULT_PORT = 887;

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private final FakeExecutorResource fakeExecutorResource = new FakeExecutorResource();

  private final class FakeExecutorResource implements Resource<Executor> {

    @Override
    public Executor create() {
      return fakeClock.getScheduledExecutorService();
    }

    @Override
    public void close(Executor instance) {}
  }

  @Captor private ArgumentCaptor<ResolutionResult> resultCaptor;
  @Captor private ArgumentCaptor<Status> errorCaptor;
  @Mock private ServiceConfigParser serviceConfigParser;
  @Mock private NameResolver.Listener2 mockListener;

  private GrpclbNameResolver resolver;
  private String hostName;

  @Before
  public void setUp() {
    GrpclbNameResolver.setEnableTxt(true);
    NameResolver.Args args =
        NameResolver.Args.newBuilder()
            .setDefaultPort(DEFAULT_PORT)
            .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
            .setSynchronizationContext(syncContext)
            .setServiceConfigParser(serviceConfigParser)
            .setChannelLogger(mock(ChannelLogger.class))
            .build();
    resolver =
        new GrpclbNameResolver(
            null, NAME, args, fakeExecutorResource, fakeClock.getStopwatchSupplier().get(),
            /* isAndroid */false);
    hostName = resolver.getHost();
    assertThat(hostName).isEqualTo(NAME);
  }

  @Test
  public void resolve_emptyResult() {
    resolver.setAddressResolver(new AddressResolver() {
      @Override
      public List<InetAddress> resolveAddress(String host) throws Exception {
        return Collections.emptyList();
      }
    });
    resolver.setResourceResolver(new ResourceResolver() {
      @Override
      public List<String> resolveTxt(String host) throws Exception {
        return Collections.emptyList();
      }

      @Override
      public List<SrvRecord> resolveSrv(String host) throws Exception {
        return Collections.emptyList();
      }
    });

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);

    verify(mockListener).onResult(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    assertThat(result.getAttributes()).isEqualTo(Attributes.EMPTY);
    assertThat(result.getServiceConfig()).isNull();
  }

  @Test
  public void resolve_presentResourceResolver() throws Exception {
    InetAddress backendAddr = InetAddress.getByAddress(new byte[] {127, 0, 0, 0});
    InetAddress lbAddr = InetAddress.getByAddress(new byte[] {10, 1, 0, 0});
    int lbPort = 8080;
    String lbName = "foo.example.com.";  // original name in SRV record
    SrvRecord srvRecord = new SrvRecord(lbName, 8080);
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(hostName))
        .thenReturn(Collections.singletonList(backendAddr));
    when(mockAddressResolver.resolveAddress(lbName))
        .thenReturn(Collections.singletonList(lbAddr));
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(
            Collections.singletonList(
                "grpc_config=[{\"clientLanguage\": [\"java\"], \"serviceConfig\": {}}]"));
    when(mockResourceResolver.resolveSrv(anyString()))
        .thenReturn(Collections.singletonList(srvRecord));
    when(serviceConfigParser.parseServiceConfig(ArgumentMatchers.<String, Object>anyMap()))
        .thenAnswer(new Answer<ConfigOrError>() {
          @Override
          public ConfigOrError answer(InvocationOnMock invocation) {
            Object[] args = invocation.getArguments();
            return ConfigOrError.fromConfig(args[0]);
          }
        });

    resolver.setAddressResolver(mockAddressResolver);
    resolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);
    verify(mockListener).onResult(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    InetSocketAddress resolvedBackendAddr =
        (InetSocketAddress) Iterables.getOnlyElement(
            Iterables.getOnlyElement(result.getAddresses()).getAddresses());
    assertThat(resolvedBackendAddr.getAddress()).isEqualTo(backendAddr);
    EquivalentAddressGroup resolvedBalancerAddr =
        Iterables.getOnlyElement(result.getAttributes().get(GrpclbConstants.ATTR_LB_ADDRS));
    assertThat(resolvedBalancerAddr.getAttributes().get(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY))
        .isEqualTo("foo.example.com");
    InetSocketAddress resolvedBalancerSockAddr =
        (InetSocketAddress) Iterables.getOnlyElement(resolvedBalancerAddr.getAddresses());
    assertThat(resolvedBalancerSockAddr.getAddress()).isEqualTo(lbAddr);
    assertThat(resolvedBalancerSockAddr.getPort()).isEqualTo(lbPort);
    assertThat(result.getServiceConfig().getConfig()).isNotNull();
    verify(mockAddressResolver).resolveAddress(hostName);
    verify(mockResourceResolver).resolveTxt("_grpc_config." + hostName);
    verify(mockResourceResolver).resolveSrv("_grpclb._tcp." + hostName);
  }

  @Test
  public void resolve_nullResourceResolver() throws Exception {
    InetAddress backendAddr = InetAddress.getByAddress(new byte[] {127, 0, 0, 0});
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenReturn(Collections.singletonList(backendAddr));
    ResourceResolver resourceResolver = null;

    resolver.setAddressResolver(mockAddressResolver);
    resolver.setResourceResolver(resourceResolver);

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);
    verify(mockListener).onResult(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    assertThat(result.getAddresses())
        .containsExactly(
            new EquivalentAddressGroup(new InetSocketAddress(backendAddr, DEFAULT_PORT)));
    assertThat(result.getAttributes()).isEqualTo(Attributes.EMPTY);
    assertThat(result.getServiceConfig()).isNull();
  }

  @Test
  public void resolve_nullResourceResolver_addressFailure() throws Exception {
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString())).thenThrow(new IOException("no addr"));
    ResourceResolver resourceResolver = null;

    resolver.setAddressResolver(mockAddressResolver);
    resolver.setResourceResolver(resourceResolver);

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);
    verify(mockListener).onError(errorCaptor.capture());
    Status errorStatus = errorCaptor.getValue();
    assertThat(errorStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(errorStatus.getCause()).hasMessageThat().contains("no addr");
  }

  @Test
  public void resolve_addressFailure_stillLookUpBalancersAndServiceConfig() throws Exception {
    InetAddress lbAddr = InetAddress.getByAddress(new byte[] {10, 1, 0, 0});
    int lbPort = 8080;
    String lbName = "foo.example.com.";  // original name in SRV record
    SrvRecord srvRecord = new SrvRecord(lbName, 8080);
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(hostName))
        .thenThrow(new UnknownHostException("I really tried"));
    when(mockAddressResolver.resolveAddress(lbName))
        .thenReturn(Collections.singletonList(lbAddr));
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString())).thenReturn(Collections.<String>emptyList());
    when(mockResourceResolver.resolveSrv(anyString()))
        .thenReturn(Collections.singletonList(srvRecord));

    resolver.setAddressResolver(mockAddressResolver);
    resolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);
    verify(mockListener).onResult(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    EquivalentAddressGroup resolvedBalancerAddr =
        Iterables.getOnlyElement(result.getAttributes().get(GrpclbConstants.ATTR_LB_ADDRS));
    assertThat(resolvedBalancerAddr.getAttributes().get(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY))
        .isEqualTo("foo.example.com");
    InetSocketAddress resolvedBalancerSockAddr =
        (InetSocketAddress) Iterables.getOnlyElement(resolvedBalancerAddr.getAddresses());
    assertThat(resolvedBalancerSockAddr.getAddress()).isEqualTo(lbAddr);
    assertThat(resolvedBalancerSockAddr.getPort()).isEqualTo(lbPort);
    assertThat(result.getServiceConfig()).isNull();
    verify(mockAddressResolver).resolveAddress(hostName);
    verify(mockResourceResolver).resolveTxt("_grpc_config." + hostName);
    verify(mockResourceResolver).resolveSrv("_grpclb._tcp." + hostName);
  }

  @Test
  public void resolveAll_balancerLookupFails_stillLookUpServiceConfig() throws Exception {
    InetAddress backendAddr = InetAddress.getByAddress(new byte[] {127, 0, 0, 0});
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(hostName))
        .thenReturn(Collections.singletonList(backendAddr));
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(Collections.<String>emptyList());
    when(mockResourceResolver.resolveSrv(anyString()))
        .thenThrow(new Exception("something like javax.naming.NamingException"));

    resolver.setAddressResolver(mockAddressResolver);
    resolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);
    verify(mockListener).onResult(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();

    InetSocketAddress resolvedBackendAddr =
        (InetSocketAddress) Iterables.getOnlyElement(
            Iterables.getOnlyElement(result.getAddresses()).getAddresses());
    assertThat(resolvedBackendAddr.getAddress()).isEqualTo(backendAddr);
    assertThat(result.getAttributes().get(GrpclbConstants.ATTR_LB_ADDRS)).isNull();
    verify(mockAddressResolver).resolveAddress(hostName);
    verify(mockResourceResolver).resolveTxt("_grpc_config." + hostName);
    verify(mockResourceResolver).resolveSrv("_grpclb._tcp." + hostName);
  }

  @Test
  public void resolve_addressAndBalancersLookupFail_neverLookupServiceConfig() throws Exception {
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenThrow(new UnknownHostException("I really tried"));
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    lenient().when(mockResourceResolver.resolveTxt(anyString()))
        .thenThrow(new Exception("something like javax.naming.NamingException"));
    when(mockResourceResolver.resolveSrv(anyString()))
        .thenThrow(new Exception("something like javax.naming.NamingException"));

    resolver.setAddressResolver(mockAddressResolver);
    resolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertThat(fakeClock.runDueTasks()).isEqualTo(1);
    verify(mockListener).onError(errorCaptor.capture());
    Status errorStatus = errorCaptor.getValue();
    assertThat(errorStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(mockAddressResolver).resolveAddress(hostName);
    verify(mockResourceResolver, never()).resolveTxt("_grpc_config." + hostName);
    verify(mockResourceResolver).resolveSrv("_grpclb._tcp." + hostName);
  }
}