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

package io.grpc.xds.client;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.client.XdsClient.canonifyResourceName;
import static io.grpc.xds.client.XdsClient.isResourceNameValid;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.ExperimentalApi;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.XdsClient.ResourceUpdate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10847")
public abstract class XdsResourceType<T extends ResourceUpdate> {
  static final String TYPE_URL_RESOURCE =
      "type.googleapis.com/envoy.service.discovery.v3.Resource";
  protected static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";
  @VisibleForTesting
  public static final String HASH_POLICY_FILTER_STATE_KEY = "io.grpc.channel_id";

  protected static final String TYPE_URL_CLUSTER_CONFIG =
      "type.googleapis.com/envoy.extensions.clusters.aggregate.v3.ClusterConfig";
  protected static final String TYPE_URL_TYPED_STRUCT_UDPA =
      "type.googleapis.com/udpa.type.v1.TypedStruct";
  protected static final String TYPE_URL_TYPED_STRUCT =
      "type.googleapis.com/xds.type.v3.TypedStruct";

  /**
   * Extract the resource name from an older resource type that included the name within the
   * resource contents itself. The newer approach has resources wrapped with {@code
   * envoy.service.discovery.v3.Resource} which then provides the name. This method is only called
   * for the old approach.
   *
   * @return the resource's name, or {@code null} if name is not stored within the resource contents
   */
  @Nullable
  protected String extractResourceName(Message unpackedResource) {
    return null;
  }

  protected abstract Class<? extends com.google.protobuf.Message> unpackedClassName();

  public abstract String typeName();

  public abstract String typeUrl();

  public abstract boolean shouldRetrieveResourceKeysForArgs();

  // Do not confuse with the SotW approach: it is the mechanism in which the client must specify all
  // resource names it is interested in with each request. Different resource types may behave
  // differently in this approach. For LDS and CDS resources, the server must return all resources
  // that the client has subscribed to in each request. For RDS and EDS, the server may only return
  // the resources that need an update.
  protected abstract boolean isFullStateOfTheWorld();

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10847")
  public static class Args {
    final ServerInfo serverInfo;
    final String versionInfo;
    final String nonce;
    final Bootstrapper.BootstrapInfo bootstrapInfo;
    final Object securityConfig;
    // Management server is required to always send newly requested resources, even if they
    // may have been sent previously (proactively). Thus, client does not need to cache
    // unrequested resources.
    // Only resources in the set needs to be parsed. Null means parse everything.
    final @Nullable Set<String> subscribedResources;

    public Args(ServerInfo serverInfo, String versionInfo, String nonce,
                Bootstrapper.BootstrapInfo bootstrapInfo,
                Object securityConfig,
                @Nullable Set<String> subscribedResources) {
      this.serverInfo = serverInfo;
      this.versionInfo = versionInfo;
      this.nonce = nonce;
      this.bootstrapInfo = bootstrapInfo;
      this.securityConfig = securityConfig;
      this.subscribedResources = subscribedResources;
    }

    public ServerInfo getServerInfo() {
      return serverInfo;
    }

    public String getNonce() {
      return nonce;
    }

    public Bootstrapper.BootstrapInfo getBootstrapInfo() {
      return bootstrapInfo;
    }

    public Object getSecurityConfig() {
      return securityConfig;
    }
  }

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10847")
  public static final class ResourceInvalidException extends Exception {
    private static final long serialVersionUID = 0L;

    public ResourceInvalidException(String message) {
      super(message, null, false, false);
    }

    public ResourceInvalidException(String message, Throwable cause) {
      super(cause != null ? message + ": " + cause.getMessage() : message, cause, false, false);
    }

    public static ResourceInvalidException ofResource(String resourceName, String reason) {
      return new ResourceInvalidException("Error parsing " + resourceName + ": " + reason);
    }

    public static ResourceInvalidException ofResource(Message proto, String reason) {
      return ResourceInvalidException.ofResource(proto.getClass().getCanonicalName(), reason);
    }
  }

  ValidatedResourceUpdate<T> parse(Args args, List<Any> resources) {
    Map<String, ParsedResource<T>> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      Message unpackedMessage;
      String name = "";
      try {
        if (resource.getTypeUrl().equals(TYPE_URL_RESOURCE)) {
          Resource wrappedResource = unpackCompatibleType(resource, Resource.class,
              TYPE_URL_RESOURCE, null);
          resource = wrappedResource.getResource();
          name = wrappedResource.getName();
        } 
        unpackedMessage = unpackCompatibleType(resource, unpackedClassName(), typeUrl(), null);
      } catch (InvalidProtocolBufferException e) {
        errors.add(String.format("%s response Resource index %d - can't decode %s: %s",
                typeName(), i, unpackedClassName().getSimpleName(), e.getMessage()));
        continue;
      }
      // Fallback to inner resource name if the outer resource didn't have a name.
      if (name.isEmpty()) {
        name = extractResourceName(unpackedMessage);
      }
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
        resourceUpdate = doParse(args, unpackedMessage);
      } catch (ResourceInvalidException e) {
        errors.add(String.format("%s response %s '%s' validation error: %s",
                typeName(), unpackedClassName().getSimpleName(), cname, e.getMessage()));
        invalidResources.add(cname);
        continue;
      }

      // Resource parsed successfully.
      parsedResources.put(cname, new ParsedResource<T>(resourceUpdate, resource));
    }
    return new ValidatedResourceUpdate<T>(parsedResources, unpackedResources, invalidResources,
        errors);

  }

  protected abstract T doParse(Args args, Message unpackedMessage) throws ResourceInvalidException;

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
  protected static <T extends com.google.protobuf.Message> T unpackCompatibleType(
      Any any, Class<T> clazz, String typeUrl, String compatibleTypeUrl)
      throws InvalidProtocolBufferException {
    if (any.getTypeUrl().equals(compatibleTypeUrl)) {
      any = any.toBuilder().setTypeUrl(typeUrl).build();
    }
    return any.unpack(clazz);
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

    // validated resource update
    public ValidatedResourceUpdate(Map<String, ParsedResource<T>> parsedResources,
                                   Set<String> unpackedResources,
                                   Set<String> invalidResources,
                                   List<String> errors) {
      this.parsedResources = parsedResources;
      this.unpackedResources = unpackedResources;
      this.invalidResources = invalidResources;
      this.errors = errors;
    }
  }

  @VisibleForTesting
  public static final class StructOrError<T> {

    /**
    * Returns a {@link StructOrError} for the successfully converted data object.
    */
    public static <T> StructOrError<T> fromStruct(T struct) {
      return new StructOrError<>(struct);
    }

    /**
     * Returns a {@link StructOrError} for the failure to convert the data object.
     */
    public static <T> StructOrError<T> fromError(String errorDetail) {
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
    public T getStruct() {
      return struct;
    }

    /**
     * Returns error detail if exists, otherwise null.
     */
    @VisibleForTesting
    @Nullable
    public String getErrorDetail() {
      return errorDetail;
    }
  }
}
