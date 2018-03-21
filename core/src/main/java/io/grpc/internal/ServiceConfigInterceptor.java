/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modifies RPCs in in conformance with a Service Config.
 */
final class ServiceConfigInterceptor implements ClientInterceptor {

  private static final Logger logger = Logger.getLogger(ServiceConfigInterceptor.class.getName());

  // Map from method name to MethodInfo
  @VisibleForTesting
  final AtomicReference<Map<String, MethodInfo>> serviceMethodMap
      = new AtomicReference<Map<String, MethodInfo>>();
  @VisibleForTesting
  final AtomicReference<Map<String, MethodInfo>> serviceMap
      = new AtomicReference<Map<String, MethodInfo>>();

  ServiceConfigInterceptor() {}

  void handleUpdate(Map<String, Object> serviceConfig) {
    Map<String, MethodInfo> newServiceMethodConfigs = new HashMap<String, MethodInfo>();
    Map<String, MethodInfo> newServiceConfigs = new HashMap<String, MethodInfo>();

    // Try and do as much validation here before we swap out the existing configuration.  In case
    // the input is invalid, we don't want to lose the existing configuration.

    List<Map<String, Object>> methodConfigs =
        ServiceConfigUtil.getMethodConfigFromServiceConfig(serviceConfig);
    if (methodConfigs == null) {
      logger.log(Level.FINE, "No method configs found, skipping");
      return;
    }

    for (Map<String, Object> methodConfig : methodConfigs) {
      MethodInfo info = new MethodInfo(methodConfig);

      List<Map<String, Object>> nameList =
          ServiceConfigUtil.getNameListFromMethodConfig(methodConfig);

      checkArgument(
          nameList != null && !nameList.isEmpty(), "no names in method config %s", methodConfig);
      for (Map<String, Object> name : nameList) {
        String serviceName = ServiceConfigUtil.getServiceFromName(name);
        checkArgument(!Strings.isNullOrEmpty(serviceName), "missing service name");
        String methodName = ServiceConfigUtil.getMethodFromName(name);
        if (Strings.isNullOrEmpty(methodName)) {
          // Service scoped config
          checkArgument(
              !newServiceConfigs.containsKey(serviceName), "Duplicate service %s", serviceName);
          newServiceConfigs.put(serviceName, info);
        } else {
          // Method scoped config
          String fullMethodName = MethodDescriptor.generateFullMethodName(serviceName, methodName);
          checkArgument(
              !newServiceMethodConfigs.containsKey(fullMethodName),
              "Duplicate method name %s",
              fullMethodName);
          newServiceMethodConfigs.put(fullMethodName, info);
        }
      }
    }

    // Okay, service config is good, swap it.
    serviceMethodMap.set(Collections.unmodifiableMap(newServiceMethodConfigs));
    serviceMap.set(Collections.unmodifiableMap(newServiceConfigs));
  }

  /**
   * Equivalent of MethodConfig from a ServiceConfig.
   */
  @VisibleForTesting
  static final class MethodInfo {
    final Long timeoutNanos;
    final Boolean waitForReady;
    final Integer maxInboundMessageSize;
    final Integer maxOutboundMessageSize;

    MethodInfo(Map<String, Object> methodConfig) {
      timeoutNanos = ServiceConfigUtil.getTimeoutFromMethodConfig(methodConfig);
      waitForReady = ServiceConfigUtil.getWaitForReadyFromMethodConfig(methodConfig);
      maxInboundMessageSize =
          ServiceConfigUtil.getMaxResponseMessageBytesFromMethodConfig(methodConfig);
      if (maxInboundMessageSize != null) {
        checkArgument(maxInboundMessageSize >= 0, "%s exceeds bounds", maxInboundMessageSize);
      }
      maxOutboundMessageSize =
          ServiceConfigUtil.getMaxRequestMessageBytesFromMethodConfig(methodConfig);
      if (maxOutboundMessageSize != null) {
        checkArgument(maxOutboundMessageSize >= 0, "%s exceeds bounds", maxOutboundMessageSize);
      }
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          timeoutNanos, waitForReady, maxInboundMessageSize, maxOutboundMessageSize);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MethodInfo)) {
        return false;
      }
      MethodInfo that = (MethodInfo) other;
      return Objects.equal(this.timeoutNanos, that.timeoutNanos)
          && Objects.equal(this.waitForReady, that.waitForReady)
          && Objects.equal(this.maxInboundMessageSize, that.maxInboundMessageSize)
          && Objects.equal(this.maxOutboundMessageSize, that.maxOutboundMessageSize);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("timeoutNanos", timeoutNanos)
          .add("waitForReady", waitForReady)
          .add("maxInboundMessageSize", maxInboundMessageSize)
          .add("maxOutboundMessageSize", maxOutboundMessageSize)
          .toString();
    }
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    Map<String, MethodInfo> localServiceMethodMap = serviceMethodMap.get();
    MethodInfo info = null;
    if (localServiceMethodMap != null) {
      info = localServiceMethodMap.get(method.getFullMethodName());
    }
    if (info == null) {
      Map<String, MethodInfo> localServiceMap = serviceMap.get();
      if (localServiceMap != null) {
        info = localServiceMap.get(
            MethodDescriptor.extractFullServiceName(method.getFullMethodName()));
      }
    }
    if (info == null) {
      return next.newCall(method, callOptions);
    }

    if (info.timeoutNanos != null) {
      Deadline newDeadline = Deadline.after(info.timeoutNanos, TimeUnit.NANOSECONDS);
      Deadline existingDeadline = callOptions.getDeadline();
      // If the new deadline is sooner than the existing deadline, swap them.
      if (existingDeadline == null || newDeadline.compareTo(existingDeadline) < 0) {
        callOptions = callOptions.withDeadline(newDeadline);
      }
    }
    if (info.waitForReady != null) {
      callOptions =
          info.waitForReady ? callOptions.withWaitForReady() : callOptions.withoutWaitForReady();
    }
    if (info.maxInboundMessageSize != null) {
      callOptions = callOptions.withMaxInboundMessageSize(info.maxInboundMessageSize);
    }
    if (info.maxOutboundMessageSize != null) {
      callOptions = callOptions.withMaxOutboundMessageSize(info.maxOutboundMessageSize);
    }

    return next.newCall(method, callOptions);
  }
}
