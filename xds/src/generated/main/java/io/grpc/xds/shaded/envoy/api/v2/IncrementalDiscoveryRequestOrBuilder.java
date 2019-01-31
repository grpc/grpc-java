// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: envoy/api/v2/discovery.proto

package io.grpc.xds.shaded.envoy.api.v2;

public interface IncrementalDiscoveryRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:envoy.api.v2.IncrementalDiscoveryRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The node making the request.
   * </pre>
   *
   * <code>.envoy.api.v2.core.Node node = 1;</code>
   */
  boolean hasNode();
  /**
   * <pre>
   * The node making the request.
   * </pre>
   *
   * <code>.envoy.api.v2.core.Node node = 1;</code>
   */
  io.grpc.xds.shaded.envoy.api.v2.core.Node getNode();
  /**
   * <pre>
   * The node making the request.
   * </pre>
   *
   * <code>.envoy.api.v2.core.Node node = 1;</code>
   */
  io.grpc.xds.shaded.envoy.api.v2.core.NodeOrBuilder getNodeOrBuilder();

  /**
   * <pre>
   * Type of the resource that is being requested, e.g.
   * "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment". This is implicit
   * in requests made via singleton xDS APIs such as CDS, LDS, etc. but is
   * required for ADS.
   * </pre>
   *
   * <code>string type_url = 2;</code>
   */
  java.lang.String getTypeUrl();
  /**
   * <pre>
   * Type of the resource that is being requested, e.g.
   * "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment". This is implicit
   * in requests made via singleton xDS APIs such as CDS, LDS, etc. but is
   * required for ADS.
   * </pre>
   *
   * <code>string type_url = 2;</code>
   */
  com.google.protobuf.ByteString
      getTypeUrlBytes();

  /**
   * <pre>
   * IncrementalDiscoveryRequests allow the client to add or remove individual
   * resources to the set of tracked resources in the context of a stream.
   * All resource names in the resource_names_subscribe list are added to the
   * set of tracked resources and all resource names in the resource_names_unsubscribe
   * list are removed from the set of tracked resources.
   * Unlike in non incremental xDS, an empty resource_names_subscribe or
   * resource_names_unsubscribe list simply means that no resources are to be
   * added or removed to the resource list.
   * The xDS server must send updates for all tracked resources but can also
   * send updates for resources the client has not subscribed to. This behavior
   * is similar to non incremental xDS.
   * These two fields can be set for all types of IncrementalDiscoveryRequests
   * (initial, ACK/NACK or spontaneous).
   * A list of Resource names to add to the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_subscribe = 3;</code>
   */
  java.util.List<java.lang.String>
      getResourceNamesSubscribeList();
  /**
   * <pre>
   * IncrementalDiscoveryRequests allow the client to add or remove individual
   * resources to the set of tracked resources in the context of a stream.
   * All resource names in the resource_names_subscribe list are added to the
   * set of tracked resources and all resource names in the resource_names_unsubscribe
   * list are removed from the set of tracked resources.
   * Unlike in non incremental xDS, an empty resource_names_subscribe or
   * resource_names_unsubscribe list simply means that no resources are to be
   * added or removed to the resource list.
   * The xDS server must send updates for all tracked resources but can also
   * send updates for resources the client has not subscribed to. This behavior
   * is similar to non incremental xDS.
   * These two fields can be set for all types of IncrementalDiscoveryRequests
   * (initial, ACK/NACK or spontaneous).
   * A list of Resource names to add to the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_subscribe = 3;</code>
   */
  int getResourceNamesSubscribeCount();
  /**
   * <pre>
   * IncrementalDiscoveryRequests allow the client to add or remove individual
   * resources to the set of tracked resources in the context of a stream.
   * All resource names in the resource_names_subscribe list are added to the
   * set of tracked resources and all resource names in the resource_names_unsubscribe
   * list are removed from the set of tracked resources.
   * Unlike in non incremental xDS, an empty resource_names_subscribe or
   * resource_names_unsubscribe list simply means that no resources are to be
   * added or removed to the resource list.
   * The xDS server must send updates for all tracked resources but can also
   * send updates for resources the client has not subscribed to. This behavior
   * is similar to non incremental xDS.
   * These two fields can be set for all types of IncrementalDiscoveryRequests
   * (initial, ACK/NACK or spontaneous).
   * A list of Resource names to add to the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_subscribe = 3;</code>
   */
  java.lang.String getResourceNamesSubscribe(int index);
  /**
   * <pre>
   * IncrementalDiscoveryRequests allow the client to add or remove individual
   * resources to the set of tracked resources in the context of a stream.
   * All resource names in the resource_names_subscribe list are added to the
   * set of tracked resources and all resource names in the resource_names_unsubscribe
   * list are removed from the set of tracked resources.
   * Unlike in non incremental xDS, an empty resource_names_subscribe or
   * resource_names_unsubscribe list simply means that no resources are to be
   * added or removed to the resource list.
   * The xDS server must send updates for all tracked resources but can also
   * send updates for resources the client has not subscribed to. This behavior
   * is similar to non incremental xDS.
   * These two fields can be set for all types of IncrementalDiscoveryRequests
   * (initial, ACK/NACK or spontaneous).
   * A list of Resource names to add to the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_subscribe = 3;</code>
   */
  com.google.protobuf.ByteString
      getResourceNamesSubscribeBytes(int index);

  /**
   * <pre>
   * A list of Resource names to remove from the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_unsubscribe = 4;</code>
   */
  java.util.List<java.lang.String>
      getResourceNamesUnsubscribeList();
  /**
   * <pre>
   * A list of Resource names to remove from the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_unsubscribe = 4;</code>
   */
  int getResourceNamesUnsubscribeCount();
  /**
   * <pre>
   * A list of Resource names to remove from the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_unsubscribe = 4;</code>
   */
  java.lang.String getResourceNamesUnsubscribe(int index);
  /**
   * <pre>
   * A list of Resource names to remove from the list of tracked resources.
   * </pre>
   *
   * <code>repeated string resource_names_unsubscribe = 4;</code>
   */
  com.google.protobuf.ByteString
      getResourceNamesUnsubscribeBytes(int index);

  /**
   * <pre>
   * This map must be populated when the IncrementalDiscoveryRequest is the
   * first in a stream. The keys are the resources names of the xDS resources
   * known to the xDS client. The values in the map are the associated resource
   * level version info.
   * </pre>
   *
   * <code>map&lt;string, string&gt; initial_resource_versions = 5;</code>
   */
  int getInitialResourceVersionsCount();
  /**
   * <pre>
   * This map must be populated when the IncrementalDiscoveryRequest is the
   * first in a stream. The keys are the resources names of the xDS resources
   * known to the xDS client. The values in the map are the associated resource
   * level version info.
   * </pre>
   *
   * <code>map&lt;string, string&gt; initial_resource_versions = 5;</code>
   */
  boolean containsInitialResourceVersions(
      java.lang.String key);
  /**
   * Use {@link #getInitialResourceVersionsMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, java.lang.String>
  getInitialResourceVersions();
  /**
   * <pre>
   * This map must be populated when the IncrementalDiscoveryRequest is the
   * first in a stream. The keys are the resources names of the xDS resources
   * known to the xDS client. The values in the map are the associated resource
   * level version info.
   * </pre>
   *
   * <code>map&lt;string, string&gt; initial_resource_versions = 5;</code>
   */
  java.util.Map<java.lang.String, java.lang.String>
  getInitialResourceVersionsMap();
  /**
   * <pre>
   * This map must be populated when the IncrementalDiscoveryRequest is the
   * first in a stream. The keys are the resources names of the xDS resources
   * known to the xDS client. The values in the map are the associated resource
   * level version info.
   * </pre>
   *
   * <code>map&lt;string, string&gt; initial_resource_versions = 5;</code>
   */

  java.lang.String getInitialResourceVersionsOrDefault(
      java.lang.String key,
      java.lang.String defaultValue);
  /**
   * <pre>
   * This map must be populated when the IncrementalDiscoveryRequest is the
   * first in a stream. The keys are the resources names of the xDS resources
   * known to the xDS client. The values in the map are the associated resource
   * level version info.
   * </pre>
   *
   * <code>map&lt;string, string&gt; initial_resource_versions = 5;</code>
   */

  java.lang.String getInitialResourceVersionsOrThrow(
      java.lang.String key);

  /**
   * <pre>
   * When the IncrementalDiscoveryRequest is a ACK or NACK message in response
   * to a previous IncrementalDiscoveryResponse, the response_nonce must be the
   * nonce in the IncrementalDiscoveryResponse.
   * Otherwise response_nonce must be omitted.
   * </pre>
   *
   * <code>string response_nonce = 6;</code>
   */
  java.lang.String getResponseNonce();
  /**
   * <pre>
   * When the IncrementalDiscoveryRequest is a ACK or NACK message in response
   * to a previous IncrementalDiscoveryResponse, the response_nonce must be the
   * nonce in the IncrementalDiscoveryResponse.
   * Otherwise response_nonce must be omitted.
   * </pre>
   *
   * <code>string response_nonce = 6;</code>
   */
  com.google.protobuf.ByteString
      getResponseNonceBytes();

  /**
   * <pre>
   * This is populated when the previous :ref:`DiscoveryResponse &lt;envoy_api_msg_DiscoveryResponse&gt;`
   * failed to update configuration. The *message* field in *error_details*
   * provides the Envoy internal exception related to the failure.
   * </pre>
   *
   * <code>.google.rpc.Status error_detail = 7;</code>
   */
  boolean hasErrorDetail();
  /**
   * <pre>
   * This is populated when the previous :ref:`DiscoveryResponse &lt;envoy_api_msg_DiscoveryResponse&gt;`
   * failed to update configuration. The *message* field in *error_details*
   * provides the Envoy internal exception related to the failure.
   * </pre>
   *
   * <code>.google.rpc.Status error_detail = 7;</code>
   */
  com.google.rpc.Status getErrorDetail();
  /**
   * <pre>
   * This is populated when the previous :ref:`DiscoveryResponse &lt;envoy_api_msg_DiscoveryResponse&gt;`
   * failed to update configuration. The *message* field in *error_details*
   * provides the Envoy internal exception related to the failure.
   * </pre>
   *
   * <code>.google.rpc.Status error_detail = 7;</code>
   */
  com.google.rpc.StatusOrBuilder getErrorDetailOrBuilder();
}
