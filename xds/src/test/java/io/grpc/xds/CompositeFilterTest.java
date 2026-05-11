/*
 * Copyright 2026 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.xds.type.matcher.v3.Matcher;
import com.github.xds.type.matcher.v3.StringMatcher;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.extensions.common.matching.v3.ExtensionWithMatcher;
import io.envoyproxy.envoy.extensions.common.matching.v3.ExtensionWithMatcherPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.composite.v3.Composite;
import io.envoyproxy.envoy.extensions.filters.http.composite.v3.ExecuteFilterAction;
import io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.internal.UnifiedMatcher;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CompositeFilterTest {

  private final CompositeFilter.Provider provider = new CompositeFilter.Provider();

  @Mock
  private Filter.Provider fakeProvider;
  @Mock
  private Filter fakeFilter;
  @Mock
  private ClientInterceptor fakeClientInterceptor;
  @Mock
  private ServerInterceptor fakeServerInterceptor;
  @Mock
  private FilterConfig fakeConfig;

  private static final String FAKE_TYPE_URL = "type.googleapis.com/fake";

  @Before
  @SuppressWarnings("deprecation")
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    System.setProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER", "true");

    when(fakeProvider.typeUrls()).thenReturn(new String[]{FAKE_TYPE_URL});
    ConfigOrError<? extends FilterConfig> configRes = ConfigOrError.fromConfig(fakeConfig);
    when(fakeProvider.parseFilterConfig(any(Any.class), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn((ConfigOrError) configRes);
    when(fakeProvider.newInstance(any(String.class))).thenReturn(fakeFilter);
    when(fakeFilter.buildClientInterceptor(any(), any(), any())).thenReturn(fakeClientInterceptor);
    when(fakeFilter.buildServerInterceptor(any(), any())).thenReturn(fakeServerInterceptor);

    CompositeFilter.Provider.registryLookup = typeUrl -> {
      if (FAKE_TYPE_URL.equals(typeUrl)) {
        return fakeProvider;
      }
      return FilterRegistry.getDefaultRegistry().get(typeUrl);
    };
  }

  @After
  public void tearDown() {
    System.clearProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER");
    CompositeFilter.Provider.registryLookup =
        typeUrl -> FilterRegistry.getDefaultRegistry().get(typeUrl);
  }

  @Test
  public void parseConfig() {
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http"
                    + ".composite.v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig
                        .newBuilder()
                        .setName("child")
                        .setTypedConfig(Any
                            .newBuilder()
                            .setTypeUrl(FAKE_TYPE_URL)
                            .setValue(Composite
                                .newBuilder()
                                .build()
                                .toByteString())
                            .build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(
                        Matcher.MatcherList.Predicate.SinglePredicate
                            .newBuilder()
                            .setInput(com.github.xds.core.v3.TypedExtensionConfig
                                .newBuilder()
                                .setName("request_headers")
                                .setTypedConfig(
                                    Any.pack(
                                        io.envoyproxy.envoy.type.matcher.v3
                                            .HttpRequestHeaderMatchInput
                                            .newBuilder()
                                            .setHeaderName("foo")
                                            .build()))
                                .build())
                            .setValueMatch(StringMatcher
                                .newBuilder()
                                .setExact("bar")
                                .build())
                            .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    assertThat(result.errorDetail).isNull();
    assertThat(result.config).isNotNull();
    assertThat(result.config.matcher).isNotNull();
  }

  @Test
  public void clientInterceptorDelegates() {
    // Setup Config with simple matcher equivalent logic
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig
                        .newBuilder()
                        .setName("child")
                        .setTypedConfig(Any
                            .newBuilder()
                            .setTypeUrl(FAKE_TYPE_URL)
                            .setValue(Composite
                                .newBuilder()
                                .build()
                                .toByteString())
                            .build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(
                        Matcher.MatcherList.Predicate.SinglePredicate
                            .newBuilder()
                            .setInput(com.github.xds.core.v3.TypedExtensionConfig
                                .newBuilder()
                                .setName("request_headers")
                                .setTypedConfig(
                                    Any.pack(
                                        io.envoyproxy.envoy.type.matcher.v3
                                            .HttpRequestHeaderMatchInput
                                            .newBuilder()
                                            .setHeaderName("foo")
                                            .build()))
                                .build())
                            .setValueMatch(StringMatcher
                                .newBuilder()
                                .setExact("bar")
                                .build())
                            .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    assertThat(result.errorDetail).isNull();
    assertThat(result.config).isNotNull();

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();
    CallOptions callOptions = CallOptions.DEFAULT;

    ClientCall<Void, Void> call = interceptor.interceptCall(method, callOptions, next);

    // Setup Fake Child Interceptor behavior
    ClientCall childCall = mock(ClientCall.class);
    when(fakeClientInterceptor.interceptCall(any(), any(), any())).thenReturn(childCall);

    // Start with matching headers
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    call.start(mock(ClientCall.Listener.class), headers);

    verify(fakeClientInterceptor).interceptCall(any(), any(), any());
    verify(childCall).start(any(), eq(headers));
  }

  @Test
  public void clientInterceptorSkips() {
    // Setup Config with simple matcher equivalent logic with no match
    Matcher matcher = Matcher.newBuilder().build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall nextCall = mock(ClientCall.class);
    when(next.newCall(any(), any())).thenReturn(nextCall);

    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();

    ClientCall<Void, Void> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);

    Metadata headers = new Metadata();
    call.start(mock(ClientCall.Listener.class), headers);

    verify(fakeClientInterceptor, org.mockito.Mockito.never()).interceptCall(any(), any(), any());
    verify(next).newCall(any(), any());
    verify(nextCall).start(any(), eq(headers));
  }

  @Test
  public void clientInterceptorDelegatesChain() {
    // Setup Chain Action
    TypedExtensionConfig child1 = TypedExtensionConfig.newBuilder()
        .setName("child1")
        .setTypedConfig(Any.newBuilder()
            .setTypeUrl(FAKE_TYPE_URL)
            .setValue(Composite.newBuilder().build().toByteString())
            .build())
        .build();
    TypedExtensionConfig child2 = TypedExtensionConfig.newBuilder()
        .setName("child2")
        .setTypedConfig(Any.newBuilder()
            .setTypeUrl(FAKE_TYPE_URL)
            .setValue(Composite.newBuilder().build().toByteString())
            .build())
        .build();

    io.envoyproxy.envoy.extensions.filters.http.composite.v3.FilterChainConfiguration filterChain =
        io.envoyproxy.envoy.extensions.filters.http.composite.v3.FilterChainConfiguration
            .newBuilder()
            .addTypedConfig(child1)
            .addTypedConfig(child2)
            .build();

    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setFilterChain(filterChain)
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(
                        Matcher.MatcherList.Predicate.SinglePredicate
                            .newBuilder()
                            .setInput(com.github.xds.core.v3.TypedExtensionConfig
                                .newBuilder()
                                .setName("request_headers")
                                .setTypedConfig(
                                    Any.pack(
                                        io.envoyproxy.envoy.type.matcher.v3
                                            .HttpRequestHeaderMatchInput
                                            .newBuilder()
                                            .setHeaderName("foo")
                                            .build()))
                                .build())
                            .setValueMatch(StringMatcher
                                .newBuilder()
                                .setExact("bar")
                                .build())
                            .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    assertThat(result.errorDetail).isNull();
    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall nextCall = mock(ClientCall.class);
    when(next.newCall(any(), any())).thenReturn(nextCall);
    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();
    CallOptions callOptions = CallOptions.DEFAULT;

    ClientCall<Void, Void> call = interceptor.interceptCall(method, callOptions, next);

    // Setup Fake Child Interceptor behavior
    // Re-create mock to ensure we have control
    fakeClientInterceptor = mock(ClientInterceptor.class);
    when(fakeFilter.buildClientInterceptor(any(), any(), any())).thenReturn(fakeClientInterceptor);

    org.mockito.Mockito.doAnswer(invocation -> {
      io.grpc.Channel nextArg = (io.grpc.Channel) invocation.getArguments()[2];
      return nextArg.newCall(
          (io.grpc.MethodDescriptor<?, ?>) invocation.getArguments()[0],
          (io.grpc.CallOptions) invocation.getArguments()[1]);
    }).when(fakeClientInterceptor).interceptCall(any(), any(), any());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    call.start(mock(ClientCall.Listener.class), headers);

    // Verify buildClientInterceptor was called twice (once per child in chain)
    verify(fakeFilter, org.mockito.Mockito.times(2))
        .buildClientInterceptor(any(), any(), any());
    // Verify interceptCall was called twice on the fake interceptor (since it is
    // reused)
    // Actually, if we reuse the same interceptor instance "fakeClientInterceptor",
    // interceptCall is called on IT.
    // But wait, chaining wraps them.
    // int1(next=int2) -> int2(next=channel)
    // If int1 and int2 are SAME instance.
    // call.start calls int1.interceptCall
    // int1 calls next.newCall
    // next is int2-channel-wrapper? No.
    // ClientInterceptors.intercept(channel, [i1, i2])
    // Sequence: i2 intercepts channel. i1 intercepts i2-channel.
    // i1.interceptCall(next=i2-channel) called.
    // i1 calls next.newCall() -> i2.interceptCall(next=channel) called.
    // So yes, interceptCall should be called twice on the interceptor instance.
    verify(fakeClientInterceptor, org.mockito.Mockito.times(2))
        .interceptCall(any(), any(), any());
  }

  @Test
  public void serverNameInputMatch() {
    // Setup matcher with ServerNameInput
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig
                        .newBuilder()
                        .setName("child")
                        .setTypedConfig(Any
                            .newBuilder()
                            .setTypeUrl(FAKE_TYPE_URL)
                            .setValue(Composite
                                .newBuilder()
                                .build()
                                .toByteString())
                            .build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(
                        Matcher.MatcherList.Predicate.SinglePredicate
                            .newBuilder()
                            .setInput(com.github.xds.core.v3.TypedExtensionConfig
                                .newBuilder()
                                .setName("server_name")
                                .setTypedConfig(
                                    Any.pack(
                                        io.envoyproxy.envoy.extensions.matching.common_inputs
                                            .network.v3.ServerNameInput
                                            .newBuilder()
                                            .build()))
                                .build())
                            .setValueMatch(StringMatcher
                                .newBuilder()
                                .setExact("foo.com")
                                .build())
                            .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    assertThat(result.errorDetail).isNull();

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall nextCall = mock(ClientCall.class);
    when(next.newCall(any(), any())).thenReturn(nextCall);

    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();
    CallOptions callOptions = CallOptions.DEFAULT.withAuthority("foo.com");

    ClientCall<Void, Void> call = interceptor.interceptCall(method, callOptions, next);

    // Setup Fake Child Interceptor behavior
    ClientCall childCall = mock(ClientCall.class);
    when(fakeClientInterceptor.interceptCall(any(), any(), any())).thenReturn(childCall);

    Metadata headers = new Metadata();
    call.start(mock(ClientCall.Listener.class), headers);

    // Should match and call the child interceptor
    verify(fakeClientInterceptor).interceptCall(any(), any(), any());
    verify(childCall).start(any(), eq(headers));
  }

  @Test
  public void samplePercentAlwaysFalse() {
    // Mock random to always return false (fail sampling)
    // We can't easily mock ThreadLocalRandom.current() without Powermock or
    // similar.
    // But we can rely on 0% sampling.

    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig
                        .newBuilder()
                        .setName("child")
                        .setTypedConfig(Any
                            .newBuilder()
                            .setTypeUrl(FAKE_TYPE_URL)
                            .setValue(Composite
                                .newBuilder()
                                .build()
                                .toByteString())
                            .build())
                        .build())
                    .setSamplePercent(
                        io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent
                            .newBuilder()
                            .setDefaultValue(
                                io.envoyproxy.envoy.type.v3.FractionalPercent
                                    .newBuilder()
                                    .setNumerator(0)
                                    .setDenominator(
                                        io.envoyproxy.envoy.type.v3.FractionalPercent
                                            .DenominatorType.HUNDRED)
                                    .build())
                            .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(
                        Matcher.MatcherList.Predicate.SinglePredicate
                            .newBuilder()
                            .setInput(com.github.xds.core.v3.TypedExtensionConfig
                                .newBuilder()
                                .setName("request_headers")
                                .setTypedConfig(
                                    Any.pack(
                                        io.envoyproxy.envoy.type.matcher.v3
                                            .HttpRequestHeaderMatchInput
                                            .newBuilder()
                                            .setHeaderName("foo")
                                            .build()))
                                .build())
                            .setValueMatch(StringMatcher
                                .newBuilder()
                                .setExact("bar")
                                .build())
                            .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    assertThat(result.errorDetail).isNull();

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall nextCall = mock(ClientCall.class);
    when(next.newCall(any(), any())).thenReturn(nextCall);

    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();

    // Match the header, but should fail sampling
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    ClientCall<Void, Void> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);
    call.start(mock(ClientCall.Listener.class), headers);

    verify(fakeClientInterceptor, org.mockito.Mockito.never()).interceptCall(any(), any(), any());
    verify(next).newCall(any(), any());
  }

  @Test
  public void clientCallMethodsThrowBeforeStart() {
    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    UnifiedMatcher<CompositeFilter.FilterDelegate> mockMatcher = mock(UnifiedMatcher.class);
    ClientInterceptor interceptor = filter.buildClientInterceptor(
        new CompositeFilter.CompositeFilterConfig(mockMatcher), null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();

    ClientCall<Void, Void> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);

    // Call request before start should throw IllegalStateException
    try {
      call.request(1);
      org.junit.Assert.fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Not started");
    }

    // Call halfClose before start should throw IllegalStateException
    try {
      call.halfClose();
      org.junit.Assert.fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Not started");
    }

    // Call sendMessage before start should throw IllegalStateException
    try {
      call.sendMessage(null);
      org.junit.Assert.fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Not started");
    }

    // Call setMessageCompression before start should throw IllegalStateException
    try {
      call.setMessageCompression(true);
      org.junit.Assert.fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Not started");
    }

    // Cancel is allowed before start and should not throw
    call.cancel("message", null);
  }

  @Test
  public void parseFilterConfigRejectsOverrideMessage() {
    ExtensionWithMatcherPerRoute overrideProto = ExtensionWithMatcherPerRoute.newBuilder()
        .setXdsMatcher(Matcher.newBuilder().build())
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(overrideProto), 0);

    assertThat(result.errorDetail).contains("Unsupported message type in parseFilterConfig");
  }

  @Test
  public void parseFilterConfigOverrideRejectsConfigMessage() {
    ExtensionWithMatcher configProto = ExtensionWithMatcher.newBuilder()
        .setXdsMatcher(Matcher.newBuilder().build())
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfigOverride(Any.pack(configProto), 0);

    assertThat(result.errorDetail).contains("Unsupported message type in"
        + " parseFilterConfigOverride");
  }

  @Test
  public void parseFilterConfigFailsWhenDisabled() {
    System.clearProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER");
    try {
      ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
          .parseFilterConfig(Any.pack(Composite.getDefaultInstance()), 0);
      assertThat(result.errorDetail).contains("Composite Filter is experimental");
    } finally {
      System.setProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER", "true");
    }
  }

  @Test
  public void parseFilterConfigWithEmptyConfig() {
    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(Composite.getDefaultInstance()), 0);

    assertThat(result.errorDetail).isNull();
    assertThat(result.config.matcher).isNull();
  }

  @Test
  public void clientInterceptorUsesOverrideMatcher() {
    // Base matcher that matches "foo=bar"
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig.newBuilder()
                        .setName("child")
                        .setTypedConfig(Any.newBuilder().setTypeUrl(FAKE_TYPE_URL).build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher baseMatcherProto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
                            .setName("request_headers")
                            .setTypedConfig(Any.pack(
                                HttpRequestHeaderMatchInput.newBuilder()
                                    .setHeaderName("foo")
                                    .build()))
                            .build())
                        .setValueMatch(StringMatcher.newBuilder().setExact("bar").build())
                        .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher baseProto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(baseMatcherProto)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> baseResult = provider
        .parseFilterConfig(Any.pack(baseProto), 0);

    // Override matcher that is empty (skips everything)
    Matcher overrideMatcherProto = Matcher.newBuilder().build();
    ExtensionWithMatcherPerRoute overrideProto = ExtensionWithMatcherPerRoute.newBuilder()
        .setXdsMatcher(overrideMatcherProto)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> overrideResult = provider
        .parseFilterConfigOverride(Any.pack(overrideProto), 0);

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(
        baseResult.config, overrideResult.config, mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall nextCall = mock(ClientCall.class);
    when(next.newCall(any(), any())).thenReturn(nextCall);

    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();

    ClientCall<Void, Void> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");
    // This would match base, but should be overridden

    call.start(mock(ClientCall.Listener.class), headers);

    // Verify it SKIPS (calls next directly, never calls fakeClientInterceptor)
    verify(fakeClientInterceptor, org.mockito.Mockito.never()).interceptCall(any(), any(), any());
    verify(next).newCall(any(), any());
    verify(nextCall).start(any(), eq(headers));
  }

  @Test
  public void serverInterceptorDelegates() {
    // Setup Config with simple matcher equivalent logic
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig.newBuilder()
                        .setName("child")
                        .setTypedConfig(Any.newBuilder().setTypeUrl(FAKE_TYPE_URL).build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
                            .setName("request_headers")
                            .setTypedConfig(Any.pack(
                                HttpRequestHeaderMatchInput.newBuilder()
                                    .setHeaderName("foo")
                                    .build()))
                            .build())
                        .setValueMatch(StringMatcher.newBuilder().setExact("bar").build())
                        .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ServerInterceptor interceptor = filter.buildServerInterceptor(result.config, null);

    ServerCall call = mock(ServerCall.class);
    when(call.getAttributes()).thenReturn(io.grpc.Attributes.EMPTY);
    
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    ServerCallHandler next = mock(ServerCallHandler.class);
    ServerCall.Listener listener = mock(ServerCall.Listener.class);
    when(next.startCall(any(), any())).thenReturn(listener);

    interceptor.interceptCall(call, headers, next);

    verify(fakeServerInterceptor).interceptCall(eq(call), eq(headers), any());
  }

  @Test
  public void clientInterceptorClosesFiltersOnClose() {
    // Setup Config with simple matcher equivalent logic
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig.newBuilder()
                        .setName("child")
                        .setTypedConfig(Any.newBuilder().setTypeUrl(FAKE_TYPE_URL).build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
                            .setName("request_headers")
                            .setTypedConfig(Any.pack(
                                HttpRequestHeaderMatchInput.newBuilder()
                                    .setHeaderName("foo")
                                    .build()))
                            .build())
                        .setValueMatch(StringMatcher.newBuilder().setExact("bar").build())
                        .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall childCall = mock(ClientCall.class);
    when(fakeClientInterceptor.interceptCall(any(), any(), any())).thenReturn(childCall);

    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();

    ClientCall<Void, Void> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    ClientCall.Listener responseListener = mock(ClientCall.Listener.class);
    call.start(responseListener, headers);

    // Capture the listener passed to childCall
    ArgumentCaptor<ClientCall.Listener> listenerCaptor =
        ArgumentCaptor.forClass(ClientCall.Listener.class);
    verify(childCall).start(listenerCaptor.capture(), eq(headers));

    ClientCall.Listener capturedListener = listenerCaptor.getValue();
    
    // Trigger onClose
    capturedListener.onClose(Status.OK, new Metadata());

    // Verify filter.close() was called
    verify(fakeFilter).close();
  }

  @Test
  public void parseFilterConfigExceedsRecursionLimit() {
    // Setup matcher that resolves to fakeProvider
    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.composite"
                    + ".v3.ExecuteFilterAction")
                .setValue(ExecuteFilterAction.newBuilder()
                    .setTypedConfig(TypedExtensionConfig.newBuilder()
                        .setName("child")
                        .setTypedConfig(Any.newBuilder().setTypeUrl(FAKE_TYPE_URL).build())
                        .build())
                    .build().toByteString())
                .build())
            .build())
        .build();

    Matcher matcherProto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher configProto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcherProto)
        .build();

    final Any configAny = Any.pack(configProto);

    // Mock fakeProvider to call provider.parseFilterConfig recursively
    when(fakeProvider.parseFilterConfig(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(new org.mockito.stubbing.Answer<ConfigOrError>() {
          @Override
          public ConfigOrError answer(
              org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            int passedDepth = invocation.getArgument(1);
            return Filter.Parser.parseFilterConfig(provider, configAny, passedDepth + 1);
          }
        });

    ConfigOrError<? extends Filter.FilterConfig> result = Filter.Parser
        .parseFilterConfig(provider, configAny, 0);

    assertThat(result.errorDetail).contains("Maximum recursion depth of 8 exceeded");
  }

  @Test
  public void clientInterceptorSkipsOnSkipFilter() {
    // Setup Config with a matcher that MATCHES, but action is SkipFilter
    
    Any skipActionAny = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.common.matcher.action"
            + ".v3.SkipFilter")
        .setValue(com.google.protobuf.ByteString.EMPTY)
        .build();

    Matcher.OnMatch matchAction = Matcher.OnMatch.newBuilder()
        .setAction(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
            .setName("action")
            .setTypedConfig(skipActionAny)
            .build())
        .build();

    Matcher matcher = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
                            .setName("request_headers")
                            .setTypedConfig(Any.pack(
                                HttpRequestHeaderMatchInput.newBuilder()
                                    .setHeaderName("foo")
                                    .build()))
                            .build())
                        .setValueMatch(StringMatcher.newBuilder().setExact("bar").build())
                        .build())
                    .build())
                .setOnMatch(matchAction)
                .build())
            .build())
        .build();

    ExtensionWithMatcher proto = ExtensionWithMatcher.newBuilder()
        .setExtensionConfig(TypedExtensionConfig.newBuilder().setName("composite").build())
        .setXdsMatcher(matcher)
        .build();

    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(Any.pack(proto), 0);

    assertThat(result.errorDetail).isNull();

    CompositeFilter filter = (CompositeFilter) provider.newInstance("composite");
    ClientInterceptor interceptor = filter.buildClientInterceptor(result.config, null,
        mock(ScheduledExecutorService.class));

    Channel next = mock(Channel.class);
    ClientCall nextCall = mock(ClientCall.class);
    when(next.newCall(any(), any())).thenReturn(nextCall);

    MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();

    ClientCall<Void, Void> call = interceptor.interceptCall(method, CallOptions.DEFAULT, next);

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar"); // This matches

    call.start(mock(ClientCall.Listener.class), headers);

    // Verify it SKIPS (calls next directly, never calls fakeClientInterceptor)
    verify(fakeClientInterceptor, org.mockito.Mockito.never()).interceptCall(any(), any(), any());
    verify(next).newCall(any(), any());
    verify(nextCall).start(any(), eq(headers));
  }

  @Test
  public void samplePercentDeterministic() {
    FractionalPercent percent = FractionalPercent.newBuilder()
        .setNumerator(50)
        .setDenominator(FractionalPercent.DenominatorType.HUNDRED)
        .build();
        
    ThreadSafeRandom mockRandom = mock(ThreadSafeRandom.class);
    
    CompositeFilter.FilterDelegate delegate = new CompositeFilter.FilterDelegate(
        Collections.emptyList(), percent, mockRandom);
        
    // Test value < threshold (0.5)
    when(mockRandom.nextDouble()).thenReturn(0.4);
    assertThat(delegate.shouldExecute()).isTrue();
    
    // Test value > threshold (0.5)
    when(mockRandom.nextDouble()).thenReturn(0.6);
    assertThat(delegate.shouldExecute()).isFalse();
  }

  @Test
  public void matchingDataInputSourceIp() {
    Metadata headers = new Metadata();
    InetSocketAddress addr = new InetSocketAddress("192.168.1.1", 1234);
    io.grpc.Attributes attributes = io.grpc.Attributes.newBuilder()
        .set(io.grpc.Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
        .build();
        
    CompositeFilter.MatchingDataImpl data =
        new CompositeFilter.MatchingDataImpl(headers, null, attributes);
    
    com.github.xds.core.v3.TypedExtensionConfig inputConfig =
        com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.extensions.matching.common_inputs.network"
                + ".v3.SourceIPInput")
            .build())
        .build();
        
    assertThat(data.getRelayedInput(inputConfig)).isEqualTo("192.168.1.1");
  }

  @Test
  public void matchingDataInputSourcePort() {
    Metadata headers = new Metadata();
    InetSocketAddress addr = new InetSocketAddress("192.168.1.1", 1234);
    io.grpc.Attributes attributes = io.grpc.Attributes.newBuilder()
        .set(io.grpc.Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
        .build();
        
    CompositeFilter.MatchingDataImpl data =
        new CompositeFilter.MatchingDataImpl(headers, null, attributes);
    
    com.github.xds.core.v3.TypedExtensionConfig inputConfig =
        com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.extensions.matching.common_inputs.network"
                + ".v3.SourcePortInput")
            .build())
        .build();
        
    assertThat(data.getRelayedInput(inputConfig)).isEqualTo("1234");
  }

  @Test
  public void matchingDataInputDirectSourceIp() {
    Metadata headers = new Metadata();
    InetSocketAddress addr = new InetSocketAddress("192.168.1.1", 1234);
    io.grpc.Attributes attributes = io.grpc.Attributes.newBuilder()
        .set(io.grpc.Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
        .build();
        
    CompositeFilter.MatchingDataImpl data =
        new CompositeFilter.MatchingDataImpl(headers, null, attributes);
    
    com.github.xds.core.v3.TypedExtensionConfig inputConfig =
        com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.extensions.matching.common_inputs.network"
                + ".v3.DirectSourceIPInput")
            .build())
        .build();
        
    assertThat(data.getRelayedInput(inputConfig)).isEqualTo("192.168.1.1");
  }

  @Test
  public void matchingDataInputSourceIpNonInet() {
    Metadata headers = new Metadata();
    SocketAddress addr = new SocketAddress() {};
    io.grpc.Attributes attributes = io.grpc.Attributes.newBuilder()
        .set(io.grpc.Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
        .build();
        
    CompositeFilter.MatchingDataImpl data =
        new CompositeFilter.MatchingDataImpl(headers, null, attributes);
    
    com.github.xds.core.v3.TypedExtensionConfig inputConfig =
        com.github.xds.core.v3.TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.extensions.matching.common_inputs.network"
                + ".v3.SourceIPInput")
            .build())
        .build();
        
    assertThat(data.getRelayedInput(inputConfig)).isNull();
  }

  @Test
  public void providerMethodsCovered() {
    assertThat(provider.typeUrls()).asList().containsExactly(
        "type.googleapis.com/envoy.extensions.common.matching.v3.ExtensionWithMatcher",
        "type.googleapis.com/envoy.extensions.filters.http.composite.v3.Composite",
        "type.googleapis.com/envoy.extensions.common.matching.v3.ExtensionWithMatcherPerRoute"
    );
    assertThat(provider.isClientFilter()).isTrue();
    assertThat(provider.isServerFilter()).isTrue();
  }

  @Test
  public void parseFilterConfigInvalidMessageType() {
    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(com.google.protobuf.Empty.getDefaultInstance(), 0);
        
    assertThat(result.errorDetail).contains("Invalid message type");
  }

  @Test
  public void parseFilterConfigOverrideInvalidMessageType() {
    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfigOverride(com.google.protobuf.Empty.getDefaultInstance(), 0);
        
    assertThat(result.errorDetail).contains("Invalid message type");
  }

  @Test
  public void parseFilterConfigOverrideFailsWhenDisabled() {
    System.clearProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER");
    try {
      ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
          .parseFilterConfigOverride(
              Any.pack(ExtensionWithMatcherPerRoute.getDefaultInstance()), 0);
      assertThat(result.errorDetail).contains("Composite Filter is experimental");
    } finally {
      System.setProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER", "true");
    }
  }

  @Test
  public void parseFilterConfigInvalidProtoBytes() {
    com.google.protobuf.Any invalidAny = com.google.protobuf.Any.newBuilder()
        .setTypeUrl(CompositeFilter.TYPE_URL_EXTENSION_WITH_MATCHER)
        .setValue(com.google.protobuf.ByteString.copyFrom(
            new byte[]{(byte) 0x80})) // Invalid proto bytes
        .build();
    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfig(invalidAny, 0);
    assertThat(result.errorDetail).contains("Invalid proto:");
  }

  @Test
  public void parseFilterConfigOverrideInvalidProtoBytes() {
    com.google.protobuf.Any invalidAny = com.google.protobuf.Any.newBuilder()
        .setTypeUrl(CompositeFilter.TYPE_URL_EXTENSION_WITH_MATCHER_PER_ROUTE)
        .setValue(com.google.protobuf.ByteString.copyFrom(
            new byte[]{(byte) 0x80})) // Invalid proto bytes
        .build();
    ConfigOrError<CompositeFilter.CompositeFilterConfig> result = provider
        .parseFilterConfigOverride(invalidAny, 0);
    assertThat(result.errorDetail).contains("Invalid proto:");
  }
}
