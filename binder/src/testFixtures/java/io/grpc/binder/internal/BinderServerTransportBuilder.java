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

import android.os.IBinder;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ObjectPool;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/** Helps create {@link BinderServerTransport} instances without mentioning irrelevant details. */
public class BinderServerTransportBuilder {
  private ObjectPool<ScheduledExecutorService> executorServicePool =
      new FixedObjectPool<>(new MainThreadScheduledExecutorService());
  private Attributes attributes = Attributes.EMPTY;
  private List<ServerStreamTracer.Factory> streamTracerFactories = ImmutableList.of();
  private OneWayBinderProxy.Decorator binderDecorator = OneWayBinderProxy.IDENTITY_DECORATOR;
  private IBinder callbackBinder;

  public BinderServerTransportBuilder setExecutorServicePool(
      ObjectPool<ScheduledExecutorService> executorServicePool) {
    this.executorServicePool = executorServicePool;
    return this;
  }

  public BinderServerTransportBuilder setAttributes(Attributes attributes) {
    this.attributes = attributes;
    return this;
  }

  public BinderServerTransportBuilder setStreamTracerFactories(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    this.streamTracerFactories = streamTracerFactories;
    return this;
  }

  public BinderServerTransportBuilder setBinderDecorator(
      OneWayBinderProxy.Decorator binderDecorator) {
    this.binderDecorator = binderDecorator;
    return this;
  }

  public BinderServerTransportBuilder setCallbackBinder(IBinder callbackBinder) {
    this.callbackBinder = callbackBinder;
    return this;
  }

  public BinderServerTransport build() {
    return BinderServerTransport.create(
        executorServicePool, attributes, streamTracerFactories, binderDecorator, callbackBinder);
  }
}
