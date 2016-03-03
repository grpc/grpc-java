package io.grpc.loadbalancers;

import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.RequestKey;
import io.grpc.ResolvedServerInfo;
import io.grpc.Status;
import io.grpc.TransportManager;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.Test;

import com.google.common.base.Supplier;

// TODO: This is a basic test just to verify basic functionality.  A more robust test
// suite will be provided once the overall approach is accepted
public class AffinityLoadBalancerTest {
  private final static Logger LOG = Logger.getLogger(AffinityLoadBalancerTest.class.getName());

  public static class StringTransportManager extends TransportManager<String> {
    @Override
    public void updateRetainedTransports(Collection<EquivalentAddressGroup> addrs) {
    }

    @Override
    public String getTransport(EquivalentAddressGroup addressGroup) {
      return addressGroup.getAddresses().get(0).toString();
    }

    @Override
    public Channel makeChannel(String transport) {
      return null;
    }

    @Override
    public String createFailingTransport(Status error) {
      return "Failed";
    }

    @Override
    public InterimTransport<String> createInterimTransport() {
      return new InterimTransport<String>() {
        @Override
        public String transport() {
          return "foo";
        }

        @Override
        public void closeWithRealTransports(Supplier<String> realTransports) {
          System.out.println("Closing: " + realTransports);
        }

        @Override
        public void closeWithError(Status error) {
          System.out.println("closeWithError: " + error);
        }
      };
    }
  }

  public static class RoundRobinLoadBalancer extends LoadBalancer.Factory {
    @Override
    public <T> LoadBalancer<T> newLoadBalancer(String serviceName, TransportManager<T> tm) {
      return new LoadBalancer<T>() {
        private volatile List<ResolvedServerInfo> servers = Collections.emptyList();
        private AtomicInteger counter = new AtomicInteger();

        @Override
        public void handleResolvedAddresses(List<ResolvedServerInfo> servers, Attributes config) {
          this.servers = servers;
        }

        @Override
        public T pickTransport(RequestKey requestKey) {
          List<ResolvedServerInfo> servers = this.servers;
          LOG.info(String.format("Pick transport for %s from servers %s ", requestKey, servers));
          if (servers.isEmpty()) {
            return tm.createFailingTransport(Status.UNAVAILABLE);
          }
          ResolvedServerInfo pickedServer = servers.get(counter.incrementAndGet() % servers.size());
          return tm.getTransport(new EquivalentAddressGroup(pickedServer.getAddress()));
        }
      };
    }
  }

  @Test
  public void test() throws InterruptedException, ExecutionException {
    LoadBalancer.Factory factory = new AffinityLoadBalancer.Factory(new RoundRobinLoadBalancer());

    LoadBalancer<String> loadBalancer = factory
        .newLoadBalancer("foo", new StringTransportManager());

    List<ResolvedServerInfo> servers = Arrays.asList(
        new ResolvedServerInfo(new InetSocketAddress("localhost", 80), Attributes.newBuilder()
              .set(Labels.ATTRIBUTE, Labels.newBuilder()
                  .add("vip", "vipA").build()).build()),
        new ResolvedServerInfo(new InetSocketAddress("localhost", 81), Attributes.newBuilder()
              .set(Labels.ATTRIBUTE, Labels.newBuilder()
                  .add("vip", "vipB")
                  .add("stack", "staging").build()).build()),
        new ResolvedServerInfo(new InetSocketAddress("localhost", 82), Attributes.newBuilder()
            .set(Labels.ATTRIBUTE, Labels.newBuilder()
                  .add("vip", "vipA").build()).build()),
        new ResolvedServerInfo(new InetSocketAddress("localhost", 83), Attributes.newBuilder()
            .set(Labels.ATTRIBUTE, Labels.newBuilder()
                  .add("vip", "vipA").build()).build()));

    loadBalancer.handleResolvedAddresses(
        servers,
        Attributes
            .newBuilder()
            .set(AffinityLoadBalancer.ATTR_CORE_AFFINITY,
                RequestKey.newBuilder().affinityTo("vip", "vipA").build()).build());

    for (int i = 0; i < 10; i++) {
      LOG.info(String.format("Picked : %s", loadBalancer.pickTransport(RequestKey.NONE)));
    }

    for (int i = 0; i < 10; i++) {
      LOG.info(String.format("Picked : %s",
          loadBalancer.pickTransport(RequestKey.newBuilder().affinityTo("vip", "vipA").build())));
    }

    for (int i = 0; i < 10; i++) {
      LOG.info(String.format("Picked : %s",
          loadBalancer.pickTransport(RequestKey.newBuilder().affinityTo("vip", "vipB").build())));
    }

    for (int i = 0; i < 10; i++) {
      LOG.info(String.format(
          "Picked : %s",
          loadBalancer.pickTransport(RequestKey.newBuilder().affinityTo("vip", "vipB")
              .affinityTo("stack", "staging").build())));
    }

    loadBalancer.handleResolvedAddresses(servers, Attributes.EMPTY);

    LOG.info(String.format("Picked : %s", loadBalancer.pickTransport(RequestKey.NONE)));
  }
}
