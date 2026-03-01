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
import io.envoyproxy.envoy.extensions.filters.http.composite.v3.Composite;
import io.envoyproxy.envoy.extensions.filters.http.composite.v3.ExecuteFilterAction;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptor;
import io.grpc.xds.Filter.FilterConfig;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
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
    when(fakeProvider.parseFilterConfig(any(Any.class)))
        .thenReturn((ConfigOrError) configRes);
    when(fakeProvider.newInstance(any(String.class))).thenReturn(fakeFilter);
    when(fakeFilter.buildClientInterceptor(any(), any(), any())).thenReturn(fakeClientInterceptor);
    when(fakeFilter.buildServerInterceptor(any(), any())).thenReturn(fakeServerInterceptor);

    FilterRegistry.getDefaultRegistry().register(fakeProvider);
  }

  @After
  public void tearDown() {
    System.clearProperty("GRPC_EXPERIMENTAL_XDS_COMPOSITE_FILTER");
    FilterRegistry.getDefaultRegistry().deregister(fakeProvider);
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
        .parseFilterConfig(Any.pack(proto));

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
        .parseFilterConfig(Any.pack(proto));

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
        .parseFilterConfig(Any.pack(proto));

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
        .parseFilterConfig(Any.pack(proto));

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
        .parseFilterConfig(Any.pack(proto));

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
        .parseFilterConfig(Any.pack(proto));

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

}
