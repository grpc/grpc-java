/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.NameResolver;
import io.grpc.ResolvedServerInfo;
import io.grpc.ResolvedServerInfoGroup;
import io.grpc.Status;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A DNS-based {@link NameResolver}.
 *
 * @see DnsNameResolverFactory
 */
class DnsNameResolver extends NameResolver {
  private final String authority;
  private final String host;
  private final int port;
  private final Resource<ScheduledExecutorService> timerServiceResource;
  private final Resource<ExecutorService> executorResource;
  @GuardedBy("this")
  private boolean shutdown;
  @GuardedBy("this")
  private ScheduledExecutorService timerService;
  @GuardedBy("this")
  private ExecutorService executor;
  @GuardedBy("this")
  private ScheduledFuture<?> resolutionTask;
  @GuardedBy("this")
  private boolean resolving;
  @GuardedBy("this")
  private Listener listener;

  DnsNameResolver(@Nullable String nsAuthority, String name, Attributes params,
      Resource<ScheduledExecutorService> timerServiceResource,
      Resource<ExecutorService> executorResource) {
    // TODO: if a DNS server is provided as nsAuthority, use it.
    // https://www.captechconsulting.com/blogs/accessing-the-dusty-corners-of-dns-with-java

    this.timerServiceResource = timerServiceResource;
    this.executorResource = executorResource;
    // Must prepend a "//" to the name when constructing a URI, otherwise it will be treated as an
    // opaque URI, thus the authority and host of the resulted URI would be null.
    URI nameUri = URI.create("//" + name);
    authority = Preconditions.checkNotNull(nameUri.getAuthority(),
        "nameUri (%s) doesn't have an authority", nameUri);
    host = Preconditions.checkNotNull(nameUri.getHost(), "host");
    if (nameUri.getPort() == -1) {
      Integer defaultPort = params.get(NameResolver.Factory.PARAMS_DEFAULT_PORT);
      if (defaultPort != null) {
        port = defaultPort;
      } else {
        throw new IllegalArgumentException(
            "name '" + name + "' doesn't contain a port, and default port is not set in params");
      }
    } else {
      port = nameUri.getPort();
    }
  }

  @Override
  public final String getServiceAuthority() {
    return authority;
  }

  @Override
  public final synchronized void start(Listener listener) {
    Preconditions.checkState(this.listener == null, "already started");
    timerService = SharedResourceHolder.get(timerServiceResource);
    executor = SharedResourceHolder.get(executorResource);
    this.listener = Preconditions.checkNotNull(listener, "listener");
    resolve();
  }

  @Override
  public final synchronized void refresh() {
    Preconditions.checkState(listener != null, "not started");
    resolve();
  }

  private final Runnable resolutionRunnable = new Runnable() {
      @Override
      public void run() {
        InetAddress[] inetAddrs;
        Listener savedListener;
        synchronized (DnsNameResolver.this) {
          // If this task is started by refresh(), there might already be a scheduled task.
          if (resolutionTask != null) {
            resolutionTask.cancel(false);
            resolutionTask = null;
          }
          if (shutdown) {
            return;
          }
          savedListener = listener;
          resolving = true;
        }
        try {
          if (System.getenv("GRPC_PROXY_EXP") != null) {
            ResolvedServerInfoGroup servers = ResolvedServerInfoGroup.builder()
                .add(new ResolvedServerInfo(
                    InetSocketAddress.createUnresolved(host, port), Attributes.EMPTY))
                .build();
            savedListener.onUpdate(Collections.singletonList(servers), Attributes.EMPTY);
            return;
          }

          try {
            inetAddrs = getAllByName(host);
          } catch (UnknownHostException e) {
            synchronized (DnsNameResolver.this) {
              if (shutdown) {
                return;
              }
              // Because timerService is the single-threaded GrpcUtil.TIMER_SERVICE in production,
              // we need to delegate the blocking work to the executor
              resolutionTask =
                  timerService.schedule(new LogExceptionRunnable(resolutionRunnableOnExecutor),
                      1, TimeUnit.MINUTES);
            }
            savedListener.onError(Status.UNAVAILABLE.withCause(e));
            return;
          }
          ResolvedServerInfoGroup.Builder servers = ResolvedServerInfoGroup.builder();
          for (int i = 0; i < inetAddrs.length; i++) {
            InetAddress inetAddr = inetAddrs[i];
            servers.add(
                new ResolvedServerInfo(new InetSocketAddress(inetAddr, port), Attributes.EMPTY));
          }
          savedListener.onUpdate(Collections.singletonList(servers.build()), Attributes.EMPTY);
        } finally {
          synchronized (DnsNameResolver.this) {
            resolving = false;
          }
        }
      }
    };

  private final Runnable resolutionRunnableOnExecutor = new Runnable() {
      @Override
      public void run() {
        synchronized (DnsNameResolver.this) {
          if (!shutdown) {
            executor.execute(resolutionRunnable);
          }
        }
      }
    };

  // To be mocked out in tests
  @VisibleForTesting
  InetAddress[] getAllByName(String host) throws UnknownHostException {
    return InetAddress.getAllByName(host);
  }

  @GuardedBy("this")
  private void resolve() {
    if (resolving || shutdown) {
      return;
    }
    executor.execute(resolutionRunnable);
  }

  @Override
  public final synchronized void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    if (resolutionTask != null) {
      resolutionTask.cancel(false);
    }
    if (timerService != null) {
      timerService = SharedResourceHolder.release(timerServiceResource, timerService);
    }
    if (executor != null) {
      executor = SharedResourceHolder.release(executorResource, executor);
    }
  }

  final int getPort() {
    return port;
  }
}
