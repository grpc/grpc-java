/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;

import io.grpc.Attributes;
import io.grpc.ResolvedServerInfo;
import io.grpc.ResolvedServerInfoGroup;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * This class resolves service addresses from Eureka.
 *
 * @Author Peter Szanto
 */
public class EurekaNameResolver extends RefreshingNameResolver {

  private final String serviceName;
  private final String portMetaData;
  private final EurekaClient eurekaClient;

  /**
   * Creates an EurekaNameResolver with default settins.
   */
  public EurekaNameResolver(EurekaClientConfig clientConfig, URI targetUri, String portMetaData,
      SharedResourceHolder.Resource<ScheduledExecutorService> timerServiceResource,
      SharedResourceHolder.Resource<ExecutorService> executorResource) {
    super(timerServiceResource, executorResource);

    this.portMetaData = portMetaData;
    serviceName = targetUri.getAuthority();

    MyDataCenterInstanceConfig instanceConfig = new MyDataCenterInstanceConfig();

    ApplicationInfoManager applicationInfoManager =
        initializeApplicationInfoManager(instanceConfig);

    eurekaClient = new DiscoveryClient(applicationInfoManager, clientConfig);
  }

  private static synchronized ApplicationInfoManager initializeApplicationInfoManager(
      EurekaInstanceConfig instanceConfig) {
    InstanceInfo instanceInfo = new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get();
    return new ApplicationInfoManager(instanceConfig, instanceInfo);
  }

  @Override
  public String getServiceAuthority() {
    return serviceName;
  }

  @Override
  protected List<ResolvedServerInfoGroup> getResolvedServerInfoGroups() throws Exception {
    Application application = eurekaClient.getApplication(serviceName);

    ResolvedServerInfoGroup.Builder servers = ResolvedServerInfoGroup.builder();

    for (InstanceInfo instanceInfo : application.getInstances()) {

      int port;
      if (portMetaData != null) {
        String portString = instanceInfo.getMetadata().get(portMetaData);
        port = Integer.parseInt(instanceInfo.getMetadata().get(portMetaData));
      } else {
        port = instanceInfo.getPort();
      }
      servers.add(
          new ResolvedServerInfo(
            new InetSocketAddress(instanceInfo.getHostName(), port), Attributes.EMPTY));
    }

    return Collections.singletonList(servers.build());
  }

}

