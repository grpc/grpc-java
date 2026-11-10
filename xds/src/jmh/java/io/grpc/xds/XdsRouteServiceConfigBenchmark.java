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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.util.Durations;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.Status.Code;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory;
import io.grpc.internal.ScParser;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Compares per-RPC method config generation/parsing with reading the precomputed route config.
 * This isolates config handling; it does not measure routing, interceptors, or transport latency.
 * Run with JMH's {@code -prof gc} to compare allocation per operation.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class XdsRouteServiceConfigBenchmark {
  @Param({"EMPTY", "TIMEOUT", "RETRY", "TIMEOUT_RETRY"})
  public String configType;

  private ServiceConfigParser parser;
  private Long timeoutNanos;
  private RetryPolicy retryPolicy;
  private XdsNameResolver.RouteData route;

  @Setup
  public void setUp() {
    parser = new ScParser(true, 5, 5, new AutoConfiguredLoadBalancerFactory("pick_first"));
    timeoutNanos = configType.contains("TIMEOUT") ? TimeUnit.SECONDS.toNanos(5) : null;
    retryPolicy = configType.contains("RETRY")
        ? RetryPolicy.create(3, ImmutableList.of(Code.UNAVAILABLE),
            Durations.fromMillis(100), Durations.fromSeconds(1), null)
        : null;
    route = new XdsNameResolver.RouteData(
        RouteMatch.withPathExactOnly("/service/method"),
        RouteAction.forCluster("cluster", ImmutableList.of(), timeoutNanos, retryPolicy, false),
        new XdsNameResolver.PassthroughClientInterceptor(),
        parser.parseServiceConfig(
            XdsNameResolver.generateServiceConfigWithMethodConfig(timeoutNanos, retryPolicy)));
    if (route.parsedServiceConfig.getError() != null) {
      throw new IllegalStateException(route.parsedServiceConfig.getError().toString());
    }
  }

  @Benchmark
  public Object generateAndParsePerCall() {
    return parser.parseServiceConfig(
        XdsNameResolver.generateServiceConfigWithMethodConfig(timeoutNanos, retryPolicy))
        .getConfig();
  }

  @Benchmark
  public Object precomputedConfig() {
    return route.parsedServiceConfig.getConfig();
  }
}
