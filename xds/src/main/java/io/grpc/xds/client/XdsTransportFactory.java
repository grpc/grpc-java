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

package io.grpc.xds.client;

import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * A factory for creating new XdsTransport instances.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10823")
public interface XdsTransportFactory {
  XdsTransport create(Bootstrapper.ServerInfo serverInfo);

  /**
   * Represents transport for xDS communication (e.g., a gRPC channel).
   */
  interface XdsTransport {
    <ReqT, RespT> StreamingCall<ReqT, RespT> createStreamingCall(
         String fullMethodName, MethodDescriptor.Marshaller<ReqT> reqMarshaller,
         MethodDescriptor.Marshaller<RespT> respMarshaller);

    void shutdown();
  }

  /**
   * Represents a bidi streaming RPC call.
   */
  interface StreamingCall<ReqT, RespT> {
    void start(EventHandler<RespT> eventHandler);

    /**
     * Sends a message on the stream.
     * Only one message will be in flight at a time; subsequent
     * messages will not be sent until this one is done.
     */
    void sendMessage(ReqT message);

    /**
     * Requests a message to be received.
     */
    void startRecvMessage();

    /**
     * An error is encountered. Sends the error.
     */
    void sendError(Exception e);

    /** Indicates whether call is capable of sending additional messages without requiring
     * excessive buffering internally. Used for resource initial fetch timeout notification. See
     * also {@link EventHandler#onReady()}. Application is free to ignore it.
     */
    boolean isReady();
  }

  /**
   * An interface for handling events on a streaming call.
   **/
  interface EventHandler<RespT> {

    /**
     * Called when the stream is ready to send additional messages. If called the library use this
     * handler to trigger resource arrival timeout, also see {@link StreamingCall#isReady()}.
     * Application is free to ignore it.
     */
    void onReady();

    /**
     * Called when a message is received on the stream.
     */
    void onRecvMessage(RespT message);

    /**
     * Called when status is received on the stream.
     */
    void onStatusReceived(Status status);
  }
}
