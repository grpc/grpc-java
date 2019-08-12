/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.JsonParser;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds" scheme.
 *
 * <p>The implementation is for load balancing alpha release only. No actual VHDS is involved. It
 * always returns a hard-coded service config that selects the xds_experimental LB policy with
 * round-robin as the child policy.
 *
 * @see XdsNameResolverProvider
 */
public final class XdsNameResolver extends NameResolver {

  // TODO(chengyuanzhang): figure out what the hard-coded balancer name should be.
  private static final String SERVICE_CONFIG_HARDCODED = "{"
          + "\"loadBalancingConfig\": ["
          + "{\"xds_experimental\" : {"
          + "\"balancerName\" : \"trafficdirector\","
          + "\"childPolicy\" : [{\"round_robin\" : {}}]"
          + "}}"
          + "]}";

  private final String authority;
  private final Resource<Executor> executorResource;
  private final SynchronizationContext syncContext;

  private boolean resolving;
  private boolean shutdown;
  private Executor executor;
  private Listener2 listener;

  XdsNameResolver(String nsAuthority, String name, Args args, Resource<Executor> executorResource) {
    URI nameUri = URI.create("//" + checkNotNull(name, "name"));
    Preconditions.checkArgument(nameUri.getHost() != null, "Invalid hostname: %s", name);
    authority =
        Preconditions.checkNotNull(
            nameUri.getAuthority(), "nameUri (%s) doesn't have an authority", nameUri);
    this.executorResource = Preconditions.checkNotNull(executorResource, "executorResource");
    this.syncContext = Preconditions.checkNotNull(args.getSynchronizationContext(), "syncContext");
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @Override
  public void start(final Listener2 listener) {
    this.listener = listener;
    executor = SharedResourceHolder.get(executorResource);
    resolve();
  }

  @Override
  public void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    if (executor != null) {
      executor = SharedResourceHolder.release(executorResource, executor);
    }
  }

  @Override
  public void refresh() {
    Preconditions.checkState(listener != null, "not started");
    resolve();
  }

  @SuppressWarnings("unchecked")
  private void resolve() {
    if (resolving || shutdown) {
      return;
    }
    resolving = true;
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            Map<String, ?> config;
            try {
              config = (Map<String, ?>) JsonParser.parse(SERVICE_CONFIG_HARDCODED);
            } catch (IOException e) {
              listener.onError(
                  Status.UNKNOWN.withDescription("Invalid service config").withCause(e));
              return;
            }
            Attributes attrs =
                Attributes.newBuilder()
                    .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, config)
                    .build();
            ResolutionResult result =
                ResolutionResult.newBuilder()
                    .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
                    .setAttributes(attrs)
                    .build();
            listener.onResult(result);
            syncContext.execute(
                new Runnable() {
                  @Override
                  public void run() {
                    resolving = false;
                  }
                });
          }
        });
  }
}
