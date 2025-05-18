/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.gcp.csm.observability;

import com.google.common.base.Preconditions;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.CallOptions;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.opentelemetry.InternalOpenTelemetryPlugin;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.xds.ClusterImplLoadBalancerProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.contrib.gcp.resource.GCPResourceProvider;
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenTelemetryPlugin implementing metadata-based workload property exchange for both client and
 * server. Is responsible for determining the metadata, communicating the metadata, and adding local
 * and remote details to metrics.
 */
final class MetadataExchanger implements InternalOpenTelemetryPlugin {

  private static final AttributeKey<String> CLOUD_PLATFORM =
      AttributeKey.stringKey("cloud.platform");
  private static final AttributeKey<String> K8S_NAMESPACE_NAME =
      AttributeKey.stringKey("k8s.namespace.name");
  private static final AttributeKey<String> K8S_CLUSTER_NAME =
      AttributeKey.stringKey("k8s.cluster.name");
  private static final AttributeKey<String> CLOUD_AVAILABILITY_ZONE =
      AttributeKey.stringKey("cloud.availability_zone");
  private static final AttributeKey<String> CLOUD_REGION =
      AttributeKey.stringKey("cloud.region");
  private static final AttributeKey<String> CLOUD_ACCOUNT_ID =
      AttributeKey.stringKey("cloud.account.id");

  private static final Metadata.Key<String> SEND_KEY =
      Metadata.Key.of("x-envoy-peer-metadata", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<Struct> RECV_KEY =
      Metadata.Key.of("x-envoy-peer-metadata", new BinaryToAsciiMarshaller<>(
          ProtoUtils.metadataMarshaller(Struct.getDefaultInstance())));

  private static final String EXCHANGE_TYPE = "type";
  private static final String EXCHANGE_CANONICAL_SERVICE = "canonical_service";
  private static final String EXCHANGE_PROJECT_ID = "project_id";
  private static final String EXCHANGE_LOCATION = "location";
  private static final String EXCHANGE_CLUSTER_NAME = "cluster_name";
  private static final String EXCHANGE_NAMESPACE_NAME = "namespace_name";
  private static final String EXCHANGE_WORKLOAD_NAME = "workload_name";
  private static final String TYPE_GKE = "gcp_kubernetes_engine";
  private static final String TYPE_GCE = "gcp_compute_engine";

  private final String localMetadata;
  private final Attributes localAttributes;

  public MetadataExchanger() {
    this(
        addOtelResourceAttributes(new GCPResourceProvider().getAttributes()),
        System::getenv);
  }

  MetadataExchanger(Attributes platformAttributes, Lookup env) {
    String type = platformAttributes.get(CLOUD_PLATFORM);
    String canonicalService = env.get("CSM_CANONICAL_SERVICE_NAME");
    Struct.Builder struct = Struct.newBuilder();
    put(struct, EXCHANGE_TYPE, type);
    put(struct, EXCHANGE_CANONICAL_SERVICE, canonicalService);
    if (TYPE_GKE.equals(type)) {
      String location = platformAttributes.get(CLOUD_AVAILABILITY_ZONE);
      if (location == null) {
        location = platformAttributes.get(CLOUD_REGION);
      }
      put(struct, EXCHANGE_WORKLOAD_NAME,  env.get("CSM_WORKLOAD_NAME"));
      put(struct, EXCHANGE_NAMESPACE_NAME, platformAttributes.get(K8S_NAMESPACE_NAME));
      put(struct, EXCHANGE_CLUSTER_NAME,   platformAttributes.get(K8S_CLUSTER_NAME));
      put(struct, EXCHANGE_LOCATION,       location);
      put(struct, EXCHANGE_PROJECT_ID,     platformAttributes.get(CLOUD_ACCOUNT_ID));
    } else if (TYPE_GCE.equals(type)) {
      String location = platformAttributes.get(CLOUD_AVAILABILITY_ZONE);
      if (location == null) {
        location = platformAttributes.get(CLOUD_REGION);
      }
      put(struct, EXCHANGE_WORKLOAD_NAME, env.get("CSM_WORKLOAD_NAME"));
      put(struct, EXCHANGE_LOCATION,      location);
      put(struct, EXCHANGE_PROJECT_ID,    platformAttributes.get(CLOUD_ACCOUNT_ID));
    }
    localMetadata = BaseEncoding.base64().encode(struct.build().toByteArray());

    localAttributes = Attributes.builder()
        .put("csm.mesh_id", nullIsUnknown(env.get("CSM_MESH_ID")))
        .put("csm.workload_canonical_service", nullIsUnknown(canonicalService))
        .build();
  }

  private static String nullIsUnknown(String value) {
    return value == null ? "unknown" : value;
  }

  private static void put(Struct.Builder struct, String key, String value) {
    value = nullIsUnknown(value);
    struct.putFields(key, Value.newBuilder().setStringValue(value).build());
  }

  private static void put(AttributesBuilder attributes, String key, Value value) {
    attributes.put(key, nullIsUnknown(fromValue(value)));
  }

  private static String fromValue(Value value) {
    if (value == null) {
      return null;
    }
    if (value.getKindCase() != Value.KindCase.STRING_VALUE) {
      return null;
    }
    return value.getStringValue();
  }

  private static Attributes addOtelResourceAttributes(Attributes platformAttributes) {
    // Can't inject env variables as ResourceConfiguration requires the large ConfigProperties API
    // to inject our own values and a default implementation isn't provided. So this reads directly
    // from System.getenv().
    Attributes envAttributes = ResourceConfiguration
        .createEnvironmentResource()
        .getAttributes();

    AttributesBuilder builder = platformAttributes.toBuilder();
    builder.putAll(envAttributes);
    return builder.build();
  }

  private void addLabels(AttributesBuilder to, Struct struct) {
    to.putAll(localAttributes);
    Map<String, Value> remote = struct.getFieldsMap();
    Value typeValue = remote.get(EXCHANGE_TYPE);
    String type = fromValue(typeValue);
    put(to, "csm.remote_workload_type", typeValue);
    put(to, "csm.remote_workload_canonical_service", remote.get(EXCHANGE_CANONICAL_SERVICE));
    if (TYPE_GKE.equals(type)) {
      put(to, "csm.remote_workload_project_id",     remote.get(EXCHANGE_PROJECT_ID));
      put(to, "csm.remote_workload_location",       remote.get(EXCHANGE_LOCATION));
      put(to, "csm.remote_workload_cluster_name",   remote.get(EXCHANGE_CLUSTER_NAME));
      put(to, "csm.remote_workload_namespace_name", remote.get(EXCHANGE_NAMESPACE_NAME));
      put(to, "csm.remote_workload_name",           remote.get(EXCHANGE_WORKLOAD_NAME));
    } else if (TYPE_GCE.equals(type)) {
      put(to, "csm.remote_workload_project_id",     remote.get(EXCHANGE_PROJECT_ID));
      put(to, "csm.remote_workload_location",       remote.get(EXCHANGE_LOCATION));
      put(to, "csm.remote_workload_name",           remote.get(EXCHANGE_WORKLOAD_NAME));
    }
  }

  @Override
  public boolean enablePluginForChannel(String target) {
    URI uri;
    try {
      uri = new URI(target);
    } catch (Exception ex) {
      return false;
    }
    String authority = uri.getAuthority();
    return "xds".equals(uri.getScheme())
        && (authority == null || "traffic-director-global.xds.googleapis.com".equals(authority));
  }

  @Override
  public ClientCallPlugin newClientCallPlugin() {
    return new ClientCallState();
  }

  public void configureServerBuilder(ServerBuilder<?> serverBuilder) {
    serverBuilder.intercept(new ServerCallInterceptor());
  }

  @Override
  public ServerStreamPlugin newServerStreamPlugin(Metadata inboundMetadata) {
    return new ServerStreamState(inboundMetadata.get(RECV_KEY));
  }

  final class ClientCallState implements ClientCallPlugin {
    private volatile Value serviceName;
    private volatile Value serviceNamespace;

    @Override
    public ClientStreamPlugin newClientStreamPlugin() {
      return new ClientStreamState();
    }

    @Override
    public CallOptions filterCallOptions(CallOptions options) {
      Consumer<Map<String, Struct>> existingConsumer =
          options.getOption(ClusterImplLoadBalancerProvider.FILTER_METADATA_CONSUMER);
      return options.withOption(
          ClusterImplLoadBalancerProvider.FILTER_METADATA_CONSUMER,
          (Map<String, Struct> clusterMetadata) -> {
            metadataConsumer(clusterMetadata);
            existingConsumer.accept(clusterMetadata);
          });
    }

    private void metadataConsumer(Map<String, Struct> clusterMetadata) {
      Struct struct = clusterMetadata.get("com.google.csm.telemetry_labels");
      if (struct == null) {
        struct = Struct.getDefaultInstance();
      }
      serviceName = struct.getFieldsMap().get("service_name");
      serviceNamespace = struct.getFieldsMap().get("service_namespace");
    }

    @Override
    public void addMetadata(Metadata toMetadata) {
      toMetadata.put(SEND_KEY, localMetadata);
    }

    class ClientStreamState implements ClientStreamPlugin {
      private Struct receivedExchange;

      @Override
      public void inboundHeaders(Metadata headers) {
        setExchange(headers);
      }

      @Override
      public void inboundTrailers(Metadata trailers) {
        if (receivedExchange != null) {
          return; // Received headers
        }
        setExchange(trailers);
      }

      private void setExchange(Metadata metadata) {
        Struct received = metadata.get(RECV_KEY);
        if (received == null) {
          receivedExchange = Struct.getDefaultInstance();
        } else {
          receivedExchange = received;
        }
      }

      @Override
      public void addLabels(AttributesBuilder to) {
        put(to, "csm.service_name",           serviceName);
        put(to, "csm.service_namespace_name", serviceNamespace);
        Struct exchange = receivedExchange;
        if (exchange == null) {
          exchange = Struct.getDefaultInstance();
        }
        MetadataExchanger.this.addLabels(to, exchange);
      }
    }
  }

  final class ServerCallInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      if (!headers.containsKey(RECV_KEY)) {
        return next.startCall(call, headers);
      } else {
        return next.startCall(new SimpleForwardingServerCall<ReqT, RespT>(call) {
          private boolean headersSent;

          @Override
          public void sendHeaders(Metadata headers) {
            headersSent = true;
            headers.put(SEND_KEY, localMetadata);
            super.sendHeaders(headers);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            if (!headersSent) {
              trailers.put(SEND_KEY, localMetadata);
            }
            super.close(status, trailers);
          }
        }, headers);
      }
    }
  }

  final class ServerStreamState implements ServerStreamPlugin {
    private final Struct receivedExchange;

    ServerStreamState(Struct exchange) {
      if (exchange == null) {
        exchange = Struct.getDefaultInstance();
      }
      receivedExchange = exchange;
    }

    @Override
    public void addLabels(AttributesBuilder to) {
      MetadataExchanger.this.addLabels(to, receivedExchange);
    }
  }

  interface Lookup {
    String get(String name);
  }

  interface Supplier<T> {
    T get() throws Exception;
  }

  static final class BinaryToAsciiMarshaller<T> implements Metadata.AsciiMarshaller<T> {
    private final Metadata.BinaryMarshaller<T> delegate;

    public BinaryToAsciiMarshaller(Metadata.BinaryMarshaller<T> delegate) {
      this.delegate = Preconditions.checkNotNull(delegate, "delegate");
    }

    @Override
    public T parseAsciiString(String serialized) {
      return delegate.parseBytes(BaseEncoding.base64().decode(serialized));
    }

    @Override
    public String toAsciiString(T value) {
      return BaseEncoding.base64().encode(delegate.toBytes(value));
    }
  }
}
