/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Traces {@link ClientCall} events.
 *
 * <p>Implementers use this class to trace events on {@link ClientCall} layer. As it extends {@link
 * ClientStreamTracer.Factory}, implementers can supply a {@link ClientStreamTracer} for tracing
 * events on client stream layer as well.
 *
 * <p>Similar functionality can be achieved with a {@link ClientInterceptor} that intercepts the
 * {@link Channel} by installing a {@link ClientStreamTracer.Factory} to {@link ClientCall}s it
 * creates. But this mechanism allows implementers to expose direct access to tracing attributes to
 * external components, such as {@link ClientInterceptor}s.
 *
 * @since 1.24.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/6080")
@ThreadSafe
public abstract class ClientCallTracer extends ClientStreamTracer.Factory {

  /**
   * Put tracer specific information as attributes to the provided builder. Tracer attributes are
   * implementation specific.
   *
   * @param builder receiver of tracer information.
   */
  public void getTracerAttributes(Attributes.Builder builder) {}

  /**
   * A request message has been sent to the server. May be called zero or more times depending on
   * how many messages the server is willing to accept for the operation.
   *
   * <p>This is called in {@link ClientCall#sendMessage(Object)}.
   *
   * @param message to be sent to the server.
   */
  public void outboundMessage(Object message) {}

  /**
   * A response message has been received. May be called zero or more times depending on whether the
   * call response is empty, a single message or a stream of messages.
   *
   * <p>This is called in {@link ClientCall.Listener#onMessage(Object)}, which is invoked in call
   * executor.
   *
   * @param message returned by the server
   */
  public void inboundMessage(Object message) {}

  /**
   * The {@link ClientCall} has been closed.
   *
   * <p>This is called in {@link ClientCall.Listener#onClose(Status, Metadata)}, which is invoked in
   * call executor.
   *
   * @param status the result of the remote call.
   */
  public void callEnded(Status status) {}

  /**
   * Factory class for {@link ClientCallTracer}.
   *
   * @since 1.24.0
   */
  public interface Factory {

    /**
     * Creates a {@link ClientCallTracer} for a new {@link ClientCall}. This is called inside the
     * channel when it's creating a client call.
     *
     * @param method information about the stream
     * @param callOptions the effective CallOptions of the call
     */
    ClientCallTracer newClientCallTracer(MethodDescriptor<?, ?> method, CallOptions callOptions);
  }
}
