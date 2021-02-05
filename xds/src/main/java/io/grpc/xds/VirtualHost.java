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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.grpc.xds.Matchers.FractionMatcher;
import io.grpc.xds.Matchers.HeaderMatcher;
import io.grpc.xds.Matchers.PathMatcher;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Reprsents an upstream virtual host. */
@AutoValue
abstract class VirtualHost {
  // The canonical name of this virtual host.
  abstract String name();

  // The list of domains (host/authority header) that will be matched to this virtual host.
  abstract ImmutableList<String> domains();

  // The list of routes that will be matched, in order, for incoming requests.
  abstract ImmutableList<Route> routes();

  @Nullable
  abstract HttpFault httpFault();

  public static VirtualHost create(String name, List<String> domains, List<Route> routes,
      @Nullable HttpFault httpFault) {
    return new AutoValue_VirtualHost(name, ImmutableList.copyOf(domains),
        ImmutableList.copyOf(routes), httpFault);
  }

  @AutoValue
  abstract static class Route {
    abstract RouteMatch routeMatch();

    abstract RouteAction routeAction();

    @Nullable
    abstract HttpFault httpFault();

    static Route create(RouteMatch routeMatch, RouteAction routeAction,
        @Nullable HttpFault httpFault) {
      return new AutoValue_VirtualHost_Route(routeMatch, routeAction, httpFault);
    }

    @AutoValue
    abstract static class RouteMatch {
      abstract PathMatcher pathMatcher();

      abstract ImmutableList<HeaderMatcher> headerMatchers();

      @Nullable
      abstract FractionMatcher fractionMatcher();

      // TODO(chengyuanzhang): maybe delete me.
      @VisibleForTesting
      static RouteMatch withPathExactOnly(String path) {
        return RouteMatch.create(PathMatcher.fromPath(path, true),
            Collections.<HeaderMatcher>emptyList(), null);
      }

      static RouteMatch create(PathMatcher pathMatcher,
          List<HeaderMatcher> headerMatchers, @Nullable FractionMatcher fractionMatcher) {
        return new AutoValue_VirtualHost_Route_RouteMatch(pathMatcher,
            ImmutableList.copyOf(headerMatchers), fractionMatcher);
      }
    }

    @AutoValue
    abstract static class RouteAction {
      @Nullable
      abstract Long timeoutNano();

      @Nullable
      abstract String cluster();

      @Nullable
      abstract ImmutableList<ClusterWeight> weightedClusters();

      static RouteAction forCluster(String cluster, @Nullable Long timeoutNano) {
        checkNotNull(cluster, "cluster");
        return RouteAction.create(timeoutNano, cluster, null);
      }

      static RouteAction forWeightedClusters(List<ClusterWeight> weightedClusters,
          @Nullable Long timeoutNano) {
        checkNotNull(weightedClusters, "weightedClusters");
        checkArgument(!weightedClusters.isEmpty(), "empty cluster list");
        return RouteAction.create(timeoutNano, null, weightedClusters);
      }

      private static RouteAction create(@Nullable Long timeoutNano, @Nullable String cluster,
          @Nullable List<ClusterWeight> weightedClusters) {
        return new AutoValue_VirtualHost_Route_RouteAction(timeoutNano, cluster,
            weightedClusters == null ? null : ImmutableList.copyOf(weightedClusters));
      }

      @AutoValue
      abstract static class ClusterWeight {
        abstract String name();

        abstract int weight();

        @Nullable
        abstract HttpFault httpFault();

        static ClusterWeight create(String name, int weight, @Nullable HttpFault httpFault) {
          return new AutoValue_VirtualHost_Route_RouteAction_ClusterWeight(name, weight,
              httpFault);
        }
      }
    }
  }
}
