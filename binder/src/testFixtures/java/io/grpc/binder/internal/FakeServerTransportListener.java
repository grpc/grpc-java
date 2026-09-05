/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.binder.internal;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.ServerTransportListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Fake {@link ServerTransportListener} capturing inbound stream creations and attributes.
 *
 * <p>This class is not thread-safe. Tests must externally synchronize their assertions with
 * callbacks to this listener from gRPC threads.
 */
public final class FakeServerTransportListener<L extends ServerStreamListener>
    implements ServerTransportListener {

  /** Encapsulates a recorded stream creation event on this transport. */
  public static final class CreatedStream<L extends ServerStreamListener> {
    private final ServerStream stream;
    private final String methodName;
    private final Metadata headers;
    private final L streamListener;

    public CreatedStream(
        ServerStream stream,
        String methodName,
        Metadata headers,
        L streamListener) {
      this.stream = requireNonNull(stream, "stream");
      this.methodName = requireNonNull(methodName, "methodName");
      this.headers = requireNonNull(headers, "headers");
      this.streamListener = requireNonNull(streamListener, "streamListener");
    }

    public ServerStream getStream() {
      return stream;
    }

    public String getMethodName() {
      return methodName;
    }

    public Metadata getHeaders() {
      return headers;
    }

    public L getStreamListener() {
      return streamListener;
    }
  }

  private final List<CreatedStream<L>> createdStreams = new ArrayList<>();
  private final Supplier<L> listenerFactory;

  public FakeServerTransportListener(Supplier<L> listenerFactory) {
    this.listenerFactory = requireNonNull(listenerFactory, "listenerFactory");
  }

  @Override
  public void streamCreated(ServerStream stream, String methodName, Metadata headers) {
    L streamListener = listenerFactory.get();
    // grpc-binder (incorrectly) assumes setListener() will be called before streamCreated() returns :(
    stream.setListener(streamListener);
    createdStreams.add(new CreatedStream<>(stream, methodName, headers, streamListener));
  }

  @Override
  public Attributes transportReady(Attributes attributes) {
    return attributes;
  }

  @Override
  public void transportTerminated() {}

  public List<CreatedStream<L>> getCreatedStreams() {
    return Collections.unmodifiableList(createdStreams);
  }

  public CreatedStream<L> getOnlyCreatedStream() {
    return Iterables.getOnlyElement(createdStreams);
  }
}

