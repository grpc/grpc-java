/*
 * Copyright 2017 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.base.Objects;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.Listener2;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for the inner classes in {@link NameResolver}. */
@RunWith(JUnit4.class)
public class NameResolverTest {
  private static final List<EquivalentAddressGroup> ADDRESSES =
      Collections.singletonList(
          new EquivalentAddressGroup(new FakeSocketAddress("fake-address-1"), Attributes.EMPTY));
  private static final Attributes.Key<String> YOLO_ATTR_KEY = Attributes.Key.create("yolo");
  private static Attributes ATTRIBUTES =
      Attributes.newBuilder().set(YOLO_ATTR_KEY, "To be, or not to be?").build();
  private static final NameResolver.Args.Key<Integer> EXT_ARG_KEY =
      NameResolver.Args.Key.create("foo");
  private static ConfigOrError CONFIG = ConfigOrError.fromConfig("foo");

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private final int defaultPort = 293;
  private final ProxyDetector proxyDetector = mock(ProxyDetector.class);
  private final SynchronizationContext syncContext =
      new SynchronizationContext(mock(UncaughtExceptionHandler.class));
  private final ServiceConfigParser parser = mock(ServiceConfigParser.class);
  private final ScheduledExecutorService scheduledExecutorService =
      mock(ScheduledExecutorService.class);
  private final ChannelLogger channelLogger = mock(ChannelLogger.class);
  private final Executor executor = Executors.newSingleThreadExecutor();
  private final String overrideAuthority = "grpc.io";
  private final MetricRecorder metricRecorder = new MetricRecorder() {};
  private final int extensionArgValue = 42;
  @Mock NameResolver.Listener mockListener;

  @Test
  public void args() {
    NameResolver.Args args = createArgs();
    assertThat(args.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args.getSynchronizationContext()).isSameInstanceAs(syncContext);
    assertThat(args.getServiceConfigParser()).isSameInstanceAs(parser);
    assertThat(args.getScheduledExecutorService()).isSameInstanceAs(scheduledExecutorService);
    assertThat(args.getChannelLogger()).isSameInstanceAs(channelLogger);
    assertThat(args.getOffloadExecutor()).isSameInstanceAs(executor);
    assertThat(args.getOverrideAuthority()).isSameInstanceAs(overrideAuthority);
    assertThat(args.getMetricRecorder()).isSameInstanceAs(metricRecorder);
    assertThat(args.getExtension(EXT_ARG_KEY)).isEqualTo(extensionArgValue);

    NameResolver.Args args2 = args.toBuilder().build();
    assertThat(args2.getDefaultPort()).isEqualTo(defaultPort);
    assertThat(args2.getProxyDetector()).isSameInstanceAs(proxyDetector);
    assertThat(args2.getSynchronizationContext()).isSameInstanceAs(syncContext);
    assertThat(args2.getServiceConfigParser()).isSameInstanceAs(parser);
    assertThat(args2.getScheduledExecutorService()).isSameInstanceAs(scheduledExecutorService);
    assertThat(args2.getChannelLogger()).isSameInstanceAs(channelLogger);
    assertThat(args2.getOffloadExecutor()).isSameInstanceAs(executor);
    assertThat(args2.getOverrideAuthority()).isSameInstanceAs(overrideAuthority);
    assertThat(args.getMetricRecorder()).isSameInstanceAs(metricRecorder);
    assertThat(args.getExtension(EXT_ARG_KEY)).isEqualTo(extensionArgValue);

    assertThat(args2).isNotSameInstanceAs(args);
    assertThat(args2).isNotEqualTo(args);
  }

  private NameResolver.Args createArgs() {
    return NameResolver.Args.newBuilder()
        .setDefaultPort(defaultPort)
        .setProxyDetector(proxyDetector)
        .setSynchronizationContext(syncContext)
        .setServiceConfigParser(parser)
        .setScheduledExecutorService(scheduledExecutorService)
        .setChannelLogger(channelLogger)
        .setOffloadExecutor(executor)
        .setOverrideAuthority(overrideAuthority)
        .setMetricRecorder(metricRecorder)
        .setExtension(EXT_ARG_KEY, extensionArgValue)
        .build();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void startOnOldListener_wrapperListener2UsedToStart() {
    final Listener2[] listener2 = new Listener2[1];
    NameResolver nameResolver = new NameResolver() {
      @Override
      public String getServiceAuthority() {
        return null;
      }

      @Override
      public void shutdown() {}

      @Override
      public void start(Listener2 listener2Arg) {
        listener2[0] = listener2Arg;
      }
    };
    nameResolver.start(mockListener);

    listener2[0].onResult(ResolutionResult.newBuilder().setAddresses(ADDRESSES)
        .setAttributes(ATTRIBUTES).build());
    verify(mockListener).onAddresses(eq(ADDRESSES), eq(ATTRIBUTES));
    listener2[0].onError(Status.CANCELLED);
    verify(mockListener).onError(Status.CANCELLED);
  }

  @Test
  @SuppressWarnings({"deprecation", "InlineMeInliner"})
  public void listener2AddressesToListener2ResolutionResultConversion() {
    final ResolutionResult[] resolutionResult = new ResolutionResult[1];
    NameResolver.Listener2 listener2 = new Listener2() {
      @Override
      public void onResult(ResolutionResult resolutionResultArg) {
        resolutionResult[0] = resolutionResultArg;
      }

      @Override
      public void onError(Status error) {}
    };

    listener2.onAddresses(ADDRESSES, ATTRIBUTES);

    assertThat(resolutionResult[0].getAddressesOrError().getValue()).isEqualTo(ADDRESSES);
    assertThat(resolutionResult[0].getAttributes()).isEqualTo(ATTRIBUTES);
  }

  @Test
  public void resolutionResult_toString_addressesAttributesAndConfig() {
    ResolutionResult resolutionResult = ResolutionResult.newBuilder()
        .setAddressesOrError(StatusOr.fromValue(ADDRESSES))
        .setAttributes(ATTRIBUTES)
        .setServiceConfig(CONFIG)
        .build();

    assertThat(resolutionResult.toString()).isEqualTo(
        "ResolutionResult{addressesOrError=StatusOr{value="
            + "[[[FakeSocketAddress-fake-address-1]/{}]]}, attributes={yolo=To be, or not to be?}, "
            + "serviceConfigOrError=ConfigOrError{config=foo}}");
  }

  @Test
  public void resolutionResult_hashCode() {
    ResolutionResult resolutionResult = ResolutionResult.newBuilder()
        .setAddressesOrError(StatusOr.fromValue(ADDRESSES))
        .setAttributes(ATTRIBUTES)
        .setServiceConfig(CONFIG)
        .build();

    assertThat(resolutionResult.hashCode()).isEqualTo(
        Objects.hashCode(StatusOr.fromValue(ADDRESSES), ATTRIBUTES, CONFIG));
  }

  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" + name;
    }
  }
}
