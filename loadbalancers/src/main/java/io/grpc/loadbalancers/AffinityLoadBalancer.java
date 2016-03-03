package io.grpc.loadbalancers;

import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.RequestKey;
import io.grpc.ResolvedServerInfo;
import io.grpc.Status;
import io.grpc.TransportManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

/**
 * Decorator for any LoadBalancer that adds simple server affinity functionality in
 * the form of simple key-value label matching. A default affinity is provided by the 
 * NameResolver using the ATTR_CORE_AFFINITY attribute.  Per request affinity
 * may also be provided for each request using the RequestKey.
 * 
 * Affinity can be used to partition servers in a service by,
 * 1. Consistent hashing (ex. each server has a label for the start token of the data it owns)
 * 2. Redirect a percentage of traffic to a subset of servers for testing or debugging
 * 3. Control shifting traffic to a new service deployment
 * 4. Logical grouping of servers based on SLA or rate limiting
 * 
 * TODO: Ensure we don't thrash connections when the server list is updated
 * TODO: Handle LoadBalancer shutdown
 */
@ExperimentalApi
public final class AffinityLoadBalancer<T> extends LoadBalancer<T> {
  private final static Logger LOG = Logger.getLogger(AffinityLoadBalancer.class.getName());

  public static final Attributes.Key<RequestKey> ATTR_CORE_AFFINITY = new Attributes.Key<>(
      "io.grpc.AffinityLoadBalancer.Affinity");

  public static final class Factory extends LoadBalancer.Factory {
    private final LoadBalancer.Factory delegate;

    public Factory(final LoadBalancer.Factory delegate) {
      this.delegate = delegate;
    }

    @Override
    public <T> LoadBalancer<T> newLoadBalancer(final String serviceName,
        final TransportManager<T> tm) {
      return new AffinityLoadBalancer<T>(serviceName, tm, delegate);
    }
  }

  final class FailingLoadBalancer extends LoadBalancer<T> {
    private final Status errorStatus;

    public FailingLoadBalancer(final Status errorStatus) {
      this.errorStatus = errorStatus;
    }
    
    @Override
    public T pickTransport(final RequestKey requestKey) {
      return transportManager.createFailingTransport(errorStatus);
    }
  }
  
  final class CachingLoaderBalancer extends LoadBalancer<T> {
    private final List<ResolvedServerInfo> servers;
    private final RequestKey coreAffinity;

    public CachingLoaderBalancer(final List<ResolvedServerInfo> servers, final Attributes config) {
      this.servers = servers;
      this.coreAffinity = MoreObjects.firstNonNull(config.get(ATTR_CORE_AFFINITY), RequestKey.NONE);
    }

    @Override
    public T pickTransport(final RequestKey requestKey) {
      Preconditions.checkNotNull(requestKey);
      final RequestKey affinity;
      if (requestKey != null && requestKey.hasAffinity()) {
        if (requestKey.isAdditive() && coreAffinity != null) {
          affinity = coreAffinity.extendWith(requestKey);
        } else {
          affinity = requestKey;
        }
      } else {
        affinity = coreAffinity;
      }

      try {
        return partitionCache.get(affinity, new Callable<LoadBalancer<T>>() {
          @Override
          public LoadBalancer<T> call() throws Exception {
            LOG.info(String.format("Generating load balancer for '%s'", affinity));
            List<ResolvedServerInfo> matchedServers = new ArrayList<ResolvedServerInfo>();
            for (ResolvedServerInfo server : servers) {
              Labels serverLabels = server.getAttributes().get(Labels.ATTRIBUTE);
              if (serverLabels != null) {
                for (Entry<String, String> affinity : affinity.getAffinities()
                    .entrySet()) {
                  if (!serverLabels.matches(affinity.getKey(),
                      affinity.getValue())) {
                    continue;
                  }
                }
              }
              matchedServers.add(server);
            }

            LoadBalancer<T> loadBalancer = loadBalancerFactory.newLoadBalancer(serviceName, transportManager);
            LOG.info(String.format("Load balancer for '%s' has servers %s",
                affinity, matchedServers));
            loadBalancer.handleResolvedAddresses(matchedServers, Attributes.EMPTY);
            return loadBalancer;
          }
        }).pickTransport(affinity);
      } catch (ExecutionException e) {
        return transportManager.createFailingTransport(Status.INTERNAL.withCause(e));
      }
    }
  }

  /**
   * Current 'LoadBalancer' wrapper based on the last set of servers received 
   * from the NameResolver
   */
  private volatile LoadBalancer<T> delegate;
  
  /**
   * Cache of partitioned LoadBalancer instances with each partition matching a unique affinity.
   * The cache is cleared after each update via handleResolvedAddresses.
   */
  private final Cache<RequestKey, LoadBalancer<T>> partitionCache;
  
  /**
   * Factory to the real LoadBalancer algorithm with the factory being called for each
   * update to handleResolvedAddresses.
   */
  private final LoadBalancer.Factory loadBalancerFactory;
  
  private final TransportManager<T> transportManager;
  
  private final String serviceName;

  public AffinityLoadBalancer(final String serviceName,
      final TransportManager<T> transportManager,
      final LoadBalancer.Factory loadBalancerFactory) {
    
    LOG.info(String.format("Creating for %s", serviceName));
    this.delegate = new FailingLoadBalancer(Status.UNAVAILABLE);
    this.loadBalancerFactory = Preconditions.checkNotNull(loadBalancerFactory);
    this.transportManager = Preconditions.checkNotNull(transportManager);
    this.serviceName = Preconditions.checkNotNull(serviceName);
    
    this.partitionCache = CacheBuilder.newBuilder()
        .removalListener(new RemovalListener<RequestKey, LoadBalancer<T>>() {
          @Override
          public void onRemoval(RemovalNotification<RequestKey, LoadBalancer<T>> notification) {
            LOG.info(String.format("Clearing load balancer for %s", notification.getKey()));
            notification.getValue().shutdown();
          }
        }).build();
  }

  @Override
  public T pickTransport(final RequestKey requestKey) {
    return delegate.pickTransport(requestKey);
  }

  @Override
  public void handleResolvedAddresses(final List<ResolvedServerInfo> servers, Attributes config) {
    this.delegate = new CachingLoaderBalancer(servers, config);
    this.partitionCache.invalidateAll();
    // TODO: Will this cause all open connections to terminate?
  }

  @Override
  public void handleNameResolutionError(Status error) {
    this.delegate = new FailingLoadBalancer(error);
  }
}
