/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.grpclb;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.internal.DnsNameResolver;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A DNS-based {@link NameResolver} with gRPC LB specific add-ons for resolving balancer
 * addresses via service records.
 *
 * @see SecretGrpclbNameResolverProvider
 */
final class GrpclbNameResolver extends DnsNameResolver {

  private static final Logger logger = Logger.getLogger(GrpclbNameResolver.class.getName());

  // From https://github.com/grpc/proposal/blob/master/A5-grpclb-in-dns.md
  private static final String GRPCLB_NAME_PREFIX = "_grpclb._tcp.";

  GrpclbNameResolver(
      @Nullable String nsAuthority,
      String name,
      Args args,
      Resource<Executor> executorResource,
      Stopwatch stopwatch,
      boolean isAndroid) {
    super(nsAuthority, name, args, executorResource, stopwatch, isAndroid);
  }

  @Override
  protected InternalResolutionResult doResolve(boolean forceTxt) {
    List<EquivalentAddressGroup> balancerAddrs = resolveBalancerAddresses();
    InternalResolutionResult result = super.doResolve(!balancerAddrs.isEmpty());
    if (!balancerAddrs.isEmpty()) {
      result.attributes =
          Attributes.newBuilder()
              .set(GrpclbConstants.ATTR_LB_ADDRS, balancerAddrs)
              .build();
    }
    return result;
  }

  private List<EquivalentAddressGroup> resolveBalancerAddresses() {
    List<SrvRecord> srvRecords = Collections.emptyList();
    Exception srvRecordsException = null;
    ResourceResolver resourceResolver = getResourceResolver();
    if (resourceResolver != null) {
      try {
        srvRecords = resourceResolver.resolveSrv(GRPCLB_NAME_PREFIX + getHost());
      } catch (Exception e) {
        srvRecordsException = e;
      }
    }
    List<EquivalentAddressGroup> balancerAddresses = new ArrayList<>(srvRecords.size());
    Exception balancerAddressesException = null;
    Level level = Level.WARNING;
    for (SrvRecord record : srvRecords) {
      try {
        // Strip trailing dot for appearance's sake. It _should_ be fine either way, but most
        // people expect to see it without the dot.
        String authority = record.host.substring(0, record.host.length() - 1);
        // But we want to use the trailing dot for the IP lookup. The dot makes the name absolute
        // instead of relative and so will avoid the search list like that in resolv.conf.
        List<? extends InetAddress> addrs = addressResolver.resolveAddress(record.host);
        List<SocketAddress> sockAddrs = new ArrayList<>(addrs.size());
        for (InetAddress addr : addrs) {
          sockAddrs.add(new InetSocketAddress(addr, record.port));
        }
        Attributes attrs =
            Attributes.newBuilder()
                .set(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY, authority)
                .build();
        balancerAddresses.add(
            new EquivalentAddressGroup(Collections.unmodifiableList(sockAddrs), attrs));
      } catch (Exception e) {
        logger.log(level, "Can't find address for SRV record " + record, e);
        if (balancerAddressesException == null) {
          balancerAddressesException = e;
          level = Level.FINE;
        }
      }
    }
    if (srvRecordsException != null) {
      logger.log(Level.FINE, "SRV lookup failure", srvRecordsException);
    } else if (balancerAddressesException != null && balancerAddresses.isEmpty()) {
      logger.log(Level.FINE, "SRV-provided hostname lookup failure", balancerAddressesException);
    }
    return Collections.unmodifiableList(balancerAddresses);
  }

  @VisibleForTesting
  @Override
  protected void setAddressResolver(AddressResolver addressResolver) {
    super.setAddressResolver(addressResolver);
  }

  @VisibleForTesting
  @Override
  protected void setResourceResolver(ResourceResolver resourceResolver) {
    super.setResourceResolver(resourceResolver);
  }

  @VisibleForTesting
  @Override
  protected String getHost() {
    return super.getHost();
  }

  @VisibleForTesting
  static void setEnableTxt(boolean enableTxt) {
    DnsNameResolver.enableTxt = enableTxt;
  }
}
