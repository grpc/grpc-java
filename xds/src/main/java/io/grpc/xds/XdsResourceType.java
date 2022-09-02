/*
 * Copyright 2022 The gRPC Authors
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
import static io.grpc.xds.AbstractXdsClient.ResourceType;
import static io.grpc.xds.Bootstrapper.ServerInfo;
import static io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import static io.grpc.xds.XdsClient.ResourceUpdate;
import static io.grpc.xds.XdsClient.canonifyResourceName;
import static io.grpc.xds.XdsClient.isResourceNameValid;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancerRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

abstract class XdsResourceType<T extends ResourceUpdate> {
  static final String TYPE_URL_RESOURCE_V2 = "type.googleapis.com/envoy.api.v2.Resource";
  static final String TYPE_URL_RESOURCE_V3 =
      "type.googleapis.com/envoy.service.discovery.v3.Resource";
  static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";
  @VisibleForTesting
  static final String AGGREGATE_CLUSTER_TYPE_NAME = "envoy.clusters.aggregate";
  @VisibleForTesting
  static final String HASH_POLICY_FILTER_STATE_KEY = "io.grpc.channel_id";
  @VisibleForTesting
  static boolean enableFaultInjection =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_FAULT_INJECTION"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_FAULT_INJECTION"));
  @VisibleForTesting
  static boolean enableRetry =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RETRY"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RETRY"));
  @VisibleForTesting
  static boolean enableRbac =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_RBAC"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_RBAC"));
  @VisibleForTesting
  static boolean enableRouteLookup =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_XDS_RLS_LB"))
          && Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_XDS_RLS_LB"));
  @VisibleForTesting
  static boolean enableLeastRequest =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          ? Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          : Boolean.parseBoolean(System.getProperty("io.grpc.xds.experimentalEnableLeastRequest"));
  @VisibleForTesting
  static boolean enableCustomLbConfig =
      Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_XDS_CUSTOM_LB_CONFIG"))
          || Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_XDS_CUSTOM_LB_CONFIG"));
  @VisibleForTesting
  static boolean enableOutlierDetection =
      Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_ENABLE_OUTLIER_DETECTION"))
          || Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_ENABLE_OUTLIER_DETECTION"));
  static final String TYPE_URL_CLUSTER_CONFIG_V2 =
      "type.googleapis.com/envoy.config.cluster.aggregate.v2alpha.ClusterConfig";
  static final String TYPE_URL_CLUSTER_CONFIG =
      "type.googleapis.com/envoy.extensions.clusters.aggregate.v3.ClusterConfig";
  static final String TYPE_URL_TYPED_STRUCT_UDPA =
      "type.googleapis.com/udpa.type.v1.TypedStruct";
  static final String TYPE_URL_TYPED_STRUCT =
      "type.googleapis.com/xds.type.v3.TypedStruct";

  private final InternalLogId logId;
  private final XdsLogger logger;

  @Nullable
  abstract String extractResourceName(Message unpackedResource);

  @SuppressWarnings("unchecked")
  abstract Class<? extends com.google.protobuf.Message> unpackedClassName();

  abstract ResourceType typeName();

  abstract String typeUrl();

  abstract String typeUrlV2();

  // Non-null for  State of the World resources.
  @Nullable
  abstract ResourceType dependentResource();

  public XdsResourceType() {
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogger.XdsLogLevel.INFO, "Created");
  }

  static class Args {
    ServerInfo serverInfo;
    String versionInfo;
    String nonce;
    Bootstrapper.BootstrapInfo bootstrapInfo;
    FilterRegistry filterRegistry;
    LoadBalancerRegistry loadBalancerRegistry;
    TlsContextManager tlsContextManager;
    // Management server is required to always send newly requested resources, even if they
    // may have been sent previously (proactively). Thus, client does not need to cache
    // unrequested resources.
    // Only resources in the set needs to be parsed. Null means parse everything.
    @Nullable Set<String> subscribedResources;

    public Args(ServerInfo serverInfo, String versionInfo, String nonce,
                Bootstrapper.BootstrapInfo bootstrapInfo,
                FilterRegistry filterRegistry,
                LoadBalancerRegistry loadBalancerRegistry,
                TlsContextManager tlsContextManager,
                @Nullable Set<String> subscribedResources) {
      this.serverInfo = serverInfo;
      this.versionInfo = versionInfo;
      this.nonce = nonce;
      this.bootstrapInfo = bootstrapInfo;
      this.filterRegistry = filterRegistry;
      this.loadBalancerRegistry = loadBalancerRegistry;
      this.tlsContextManager = tlsContextManager;
      this.subscribedResources = subscribedResources;
    }
  }

  ValidatedResourceUpdate<T> parse(Args args, List<Any> resources) {
    Map<String, ParsedResource<T>> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();
    Set<String> retainedRdsResources = new HashSet<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      boolean isResourceV3;
      Message unpackedMessage;
      try {
        resource = maybeUnwrapResources(resource);
        isResourceV3 = resource.getTypeUrl().equals(typeUrl());
        unpackedMessage = unpackCompatibleType(resource, unpackedClassName(),
            typeUrl(), typeUrlV2());
      } catch (InvalidProtocolBufferException e) {
        errors.add(String.format("%s response Resource index %d - can't decode %s: %s",
                typeName(), i, unpackedClassName().getSimpleName(), e.getMessage()));
        continue;
      }
      String name = extractResourceName(unpackedMessage);
      if (name == null || !isResourceNameValid(name, resource.getTypeUrl())) {
        errors.add(
            "Unsupported resource name: " + name + " for type: " + typeName());
        continue;
      }
      String cname = canonifyResourceName(name);
      if (args.subscribedResources != null && !args.subscribedResources.contains(name)) {
        continue;
      }
      unpackedResources.add(cname);

      T resourceUpdate;
      try {
        resourceUpdate = doParse(args, unpackedMessage, retainedRdsResources,
            isResourceV3);
      } catch (ClientXdsClient.ResourceInvalidException e) {
        errors.add(String.format("%s response %s '%s' validation error: %s",
                typeName(), unpackedClassName().getSimpleName(), cname, e.getMessage()));
        invalidResources.add(cname);
        continue;
      }

      // LdsUpdate parsed successfully.
      parsedResources.put(cname, new ParsedResource<T>(resourceUpdate, resource));
    }
    logger.log(XdsLogger.XdsLogLevel.INFO,
        "Received {0} Response version {1} nonce {2}. Parsed resources: {3}",
        typeName(), args.versionInfo, args.nonce, unpackedResources);
    return new ValidatedResourceUpdate<T>(parsedResources, unpackedResources, invalidResources,
        errors, retainedRdsResources);

  }

  abstract T doParse(Args args, Message unpackedMessage, Set<String> retainedResources,
                     boolean isResourceV3)
      throws ResourceInvalidException;

  /**
   * Helper method to unpack serialized {@link com.google.protobuf.Any} message, while replacing
   * Type URL {@code compatibleTypeUrl} with {@code typeUrl}.
   *
   * @param <T> The type of unpacked message
   * @param any serialized message to unpack
   * @param clazz the class to unpack the message to
   * @param typeUrl type URL to replace message Type URL, when it's compatible
   * @param compatibleTypeUrl compatible Type URL to be replaced with {@code typeUrl}
   * @return Unpacked message
   * @throws InvalidProtocolBufferException if the message couldn't be unpacked
   */
  static <T extends com.google.protobuf.Message> T unpackCompatibleType(
      Any any, Class<T> clazz, String typeUrl, String compatibleTypeUrl)
      throws InvalidProtocolBufferException {
    if (any.getTypeUrl().equals(compatibleTypeUrl)) {
      any = any.toBuilder().setTypeUrl(typeUrl).build();
    }
    return any.unpack(clazz);
  }

  private Any maybeUnwrapResources(Any resource)
      throws InvalidProtocolBufferException {
    if (resource.getTypeUrl().equals(TYPE_URL_RESOURCE_V2)
        || resource.getTypeUrl().equals(TYPE_URL_RESOURCE_V3)) {
      return unpackCompatibleType(resource, Resource.class, TYPE_URL_RESOURCE_V3,
          TYPE_URL_RESOURCE_V2).getResource();
    } else {
      return resource;
    }
  }

  static final class ParsedResource<T extends ResourceUpdate> {
    private final T resourceUpdate;
    private final Any rawResource;

    public ParsedResource(T resourceUpdate, Any rawResource) {
      this.resourceUpdate = checkNotNull(resourceUpdate, "resourceUpdate");
      this.rawResource = checkNotNull(rawResource, "rawResource");
    }

    T getResourceUpdate() {
      return resourceUpdate;
    }

    Any getRawResource() {
      return rawResource;
    }
  }

  static final class ValidatedResourceUpdate<T extends ResourceUpdate> {
    Map<String, ParsedResource<T>> parsedResources;
    Set<String> unpackedResources;
    Set<String> invalidResources;
    List<String> errors;
    Set<String> retainedRdsResources;

    // validated resource update
    public ValidatedResourceUpdate(Map<String, ParsedResource<T>> parsedResources,
                                   Set<String> unpackedResources,
                                   Set<String> invalidResources,
                                   List<String> errors,
                                   Set<String> retainedRdsResources) {
      this.parsedResources = parsedResources;
      this.unpackedResources = unpackedResources;
      this.invalidResources = invalidResources;
      this.errors = errors;
      this.retainedRdsResources = retainedRdsResources;
    }
  }

  @VisibleForTesting
  static final class StructOrError<T> {

    /**
    * Returns a {@link StructOrError} for the successfully converted data object.
    */
    static <T> StructOrError<T> fromStruct(T struct) {
      return new StructOrError<>(struct);
    }

    /**
     * Returns a {@link StructOrError} for the failure to convert the data object.
     */
    static <T> StructOrError<T> fromError(String errorDetail) {
      return new StructOrError<>(errorDetail);
    }

    private final String errorDetail;
    private final T struct;

    private StructOrError(T struct) {
      this.struct = checkNotNull(struct, "struct");
      this.errorDetail = null;
    }

    private StructOrError(String errorDetail) {
      this.struct = null;
      this.errorDetail = checkNotNull(errorDetail, "errorDetail");
    }

    /**
     * Returns struct if exists, otherwise null.
     */
    @VisibleForTesting
    @Nullable
    T getStruct() {
      return struct;
    }

    /**
     * Returns error detail if exists, otherwise null.
     */
    @VisibleForTesting
    @Nullable
    String getErrorDetail() {
      return errorDetail;
    }
  }
}
