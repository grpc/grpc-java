/*
 * Copyright 2021 The gRPC Authors
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ServerInterceptor;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.FilterChainSelectorManager.Closer;
import io.grpc.xds.XdsServerWrapper.ServerRoutingConfig;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FilterChainSelectorManagerTest {
  private FilterChainSelectorManager manager = new FilterChainSelectorManager();
  private AtomicReference<ServerRoutingConfig> noopConfig = new AtomicReference<>(
      ServerRoutingConfig.create(ImmutableList.<VirtualHost>of(),
      ImmutableMap.<VirtualHost.Route, ServerInterceptor>of()));
  private FilterChainSelector selector1 = new FilterChainSelector(
            Collections.<FilterChain,AtomicReference<ServerRoutingConfig>>emptyMap(),
      null, new AtomicReference<ServerRoutingConfig>());
  private FilterChainSelector selector2 = new FilterChainSelector(
            Collections.<FilterChain,AtomicReference<ServerRoutingConfig>>emptyMap(),
      null, noopConfig);
  private CounterRunnable runnable1 = new CounterRunnable();
  private CounterRunnable runnable2 = new CounterRunnable();

  @Test
  public void updateSelector_changesSelector() {
    assertThat(manager.getSelectorToUpdateSelector()).isNull();
    assertThat(manager.register(new Closer(runnable1))).isNull();

    manager.updateSelector(selector1);

    assertThat(runnable1.counter).isEqualTo(1);
    assertThat(manager.getSelectorToUpdateSelector()).isSameInstanceAs(selector1);
    assertThat(manager.register(new Closer(runnable2))).isSameInstanceAs(selector1);
    assertThat(runnable2.counter).isEqualTo(0);
  }

  @Test
  public void updateSelector_callsCloserOnce() {
    assertThat(manager.register(new Closer(runnable1))).isNull();

    manager.updateSelector(selector1);
    manager.updateSelector(selector2);

    assertThat(runnable1.counter).isEqualTo(1);
  }

  @Test
  public void deregister_removesCloser() {
    Closer closer1 = new Closer(runnable1);
    manager.updateSelector(selector1);
    assertThat(manager.register(closer1)).isSameInstanceAs(selector1);
    assertThat(manager.getRegisterCount()).isEqualTo(1);

    manager.deregister(closer1);

    assertThat(manager.getRegisterCount()).isEqualTo(0);
    manager.updateSelector(selector2);
    assertThat(runnable1.counter).isEqualTo(0);
  }

  @Test
  public void deregister_removesCorrectCloser() {
    Closer closer1 = new Closer(runnable1);
    Closer closer2 = new Closer(runnable2);
    manager.updateSelector(selector1);
    assertThat(manager.register(closer1)).isSameInstanceAs(selector1);
    assertThat(manager.register(closer2)).isSameInstanceAs(selector1);
    assertThat(manager.getRegisterCount()).isEqualTo(2);

    manager.deregister(closer1);

    assertThat(manager.getRegisterCount()).isEqualTo(1);
    manager.updateSelector(selector2);
    assertThat(runnable1.counter).isEqualTo(0);
    assertThat(runnable2.counter).isEqualTo(1);
  }

  private static class CounterRunnable implements Runnable {
    int counter;

    @Override public void run() {
      counter++;
    }
  }
}
