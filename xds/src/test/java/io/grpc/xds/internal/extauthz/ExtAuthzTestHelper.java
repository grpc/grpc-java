/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NoopClientCall;
import io.grpc.NoopServerCall;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Shared test fakes for the extauthz test package. These follow the established
 * grpc-java pattern of using {@link NoopClientCall}/{@link NoopServerCall} subclasses
 * instead of Mockito mocks for core gRPC interfaces.
 *
 * @see io.grpc.NoopClientCall
 * @see io.grpc.NoopServerCall
 */
final class ExtAuthzTestHelper {

  private ExtAuthzTestHelper() {}

  /**
   * A capturing fake that records {@link #start} arguments and {@link #sendMessage} calls.
   */
  static final class CapturingClientCall<ReqT, RespT>
      extends NoopClientCall<ReqT, RespT> {
    @Nullable private ClientCall.Listener<RespT> listener;
    @Nullable private Metadata headers;
    private boolean started;
    private final List<ReqT> sentMessages = new ArrayList<>();

    @Override
    public void start(ClientCall.Listener<RespT> listener, Metadata headers) {
      this.listener = listener;
      this.headers = headers;
      this.started = true;
    }

    @Override
    public void sendMessage(ReqT message) {
      sentMessages.add(message);
    }

    /** Returns the listener passed to {@link #start}, or null if not yet started. */
    @Nullable
    ClientCall.Listener<RespT> getListener() {
      return listener;
    }

    /** Returns the headers passed to {@link #start}, or null if not yet started. */
    @Nullable
    Metadata getHeaders() {
      return headers;
    }

    /** Returns true if {@link #start} has been called. */
    boolean isStarted() {
      return started;
    }

    /** Returns an unmodifiable view of all messages sent via {@link #sendMessage}. */
    List<ReqT> getSentMessages() {
      return Collections.unmodifiableList(sentMessages);
    }
  }

  /**
   * A capturing fake that records
   * {@link ClientCall.Listener#onHeaders onHeaders},
   * {@link ClientCall.Listener#onMessage onMessage},
   * {@link ClientCall.Listener#onReady onReady}, and
   * {@link ClientCall.Listener#onClose onClose} callbacks.
   */
  static final class CapturingListener<T>
      extends NoopClientCall.NoopClientCallListener<T> {
    @Nullable private Metadata headers;
    private final List<T> messages = new ArrayList<>();
    private boolean onReadyCalled;
    @Nullable private Status closeStatus;
    @Nullable private Metadata closeTrailers;

    @Override
    public void onHeaders(Metadata headers) {
      this.headers = headers;
    }

    @Override
    public void onMessage(T message) {
      this.messages.add(message);
    }

    @Override
    public void onReady() {
      this.onReadyCalled = true;
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      this.closeStatus = status;
      this.closeTrailers = trailers;
    }

    /** Returns the headers received via {@link #onHeaders}, or null if not yet called. */
    @Nullable
    Metadata getHeaders() {
      return headers;
    }

    /** Returns an unmodifiable view of all messages received via {@link #onMessage}. */
    List<T> getMessages() {
      return Collections.unmodifiableList(messages);
    }

    /** Returns true if {@link #onReady} has been called. */
    boolean isOnReadyCalled() {
      return onReadyCalled;
    }

    /** Returns the status received via {@link #onClose}, or null if not yet closed. */
    @Nullable
    Status getCloseStatus() {
      return closeStatus;
    }

    /** Returns the trailers received via {@link #onClose}, or null if not yet closed. */
    @Nullable
    Metadata getCloseTrailers() {
      return closeTrailers;
    }
  }

  /**
   * A fake {@link io.grpc.ServerCall} that provides {@link Attributes} and
   * {@link MethodDescriptor} without requiring Mockito.
   */
  static final class TestServerCall<ReqT, RespT>
      extends NoopServerCall<ReqT, RespT> {
    private final Attributes attributes;
    private final MethodDescriptor<ReqT, RespT> methodDescriptor;

    TestServerCall(Attributes attributes, MethodDescriptor<ReqT, RespT> methodDescriptor) {
      this.attributes = attributes;
      this.methodDescriptor = methodDescriptor;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
      return methodDescriptor;
    }
  }
}
