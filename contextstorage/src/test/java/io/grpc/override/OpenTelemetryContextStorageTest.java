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

package io.grpc.override;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.common.util.concurrent.SettableFuture;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class OpenTelemetryContextStorageTest {
  @Rule
  public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();
  private Tracer tracerRule = openTelemetryRule.getOpenTelemetry().getTracer(
      "context-storage-test");
  private final io.grpc.Context.Key<String> username = io.grpc.Context.key("username");
  private final ContextKey<String> password = ContextKey.named("password");

  @Test
  public void grpcContextPropagation() throws Exception {
    final Span parentSpan = tracerRule.spanBuilder("test-context").startSpan();
    final SettableFuture<Span> spanPropagated = SettableFuture.create();
    final SettableFuture<String> grpcContextPropagated = SettableFuture.create();
    final SettableFuture<Span> spanDetached = SettableFuture.create();
    final SettableFuture<String> grpcContextDetached = SettableFuture.create();

    io.grpc.Context grpcContext;
    try (Scope scope = Context.current().with(parentSpan).makeCurrent()) {
      grpcContext = io.grpc.Context.current().withValue(username, "jeff");
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        io.grpc.Context previous = grpcContext.attach();
        try {
          grpcContextPropagated.set(username.get(io.grpc.Context.current()));
          spanPropagated.set(Span.fromContext(io.opentelemetry.context.Context.current()));
        } finally {
          grpcContext.detach(previous);
          spanDetached.set(Span.fromContext(io.opentelemetry.context.Context.current()));
          grpcContextDetached.set(username.get(io.grpc.Context.current()));
        }
      }
    }).start();
    Assert.assertEquals(spanPropagated.get(5, TimeUnit.SECONDS), parentSpan);
    Assert.assertEquals(grpcContextPropagated.get(5, TimeUnit.SECONDS), "jeff");
    Assert.assertEquals(spanDetached.get(5, TimeUnit.SECONDS), Span.getInvalid());
    Assert.assertNull(grpcContextDetached.get(5, TimeUnit.SECONDS));
  }

  @Test
  public void otelContextPropagation() throws Exception {
    final SettableFuture<String> grpcPropagated = SettableFuture.create();
    final AtomicReference<String> otelPropagation = new AtomicReference<>();

    io.grpc.Context grpcContext = io.grpc.Context.current().withValue(username, "jeff");
    io.grpc.Context previous = grpcContext.attach();
    Context original = Context.current().with(password, "valentine");
    try {
      new Thread(
          () -> {
            try (Scope scope = original.makeCurrent()) {
              otelPropagation.set(Context.current().get(password));
              grpcPropagated.set(username.get(io.grpc.Context.current()));
            }
          }
      ).start();
    } finally {
      grpcContext.detach(previous);
    }
    Assert.assertEquals(grpcPropagated.get(5, TimeUnit.SECONDS), "jeff");
    Assert.assertEquals(otelPropagation.get(), "valentine");
  }

  @Test
  public void grpcOtelMix() {
    io.grpc.Context grpcContext = io.grpc.Context.current().withValue(username, "jeff");
    Context otelContext = Context.current().with(password, "valentine");
    Assert.assertNull(username.get(io.grpc.Context.current()));
    Assert.assertNull(Context.current().get(password));
    io.grpc.Context previous = grpcContext.attach();
    try {
      assertEquals(username.get(io.grpc.Context.current()), "jeff");
      try (Scope scope = otelContext.makeCurrent()) {
        Assert.assertEquals(Context.current().get(password), "valentine");
        assertNull(username.get(io.grpc.Context.current()));

        io.grpc.Context grpcContext2 = io.grpc.Context.current().withValue(username, "frank");
        io.grpc.Context previous2 = grpcContext2.attach();
        try {
          assertEquals(username.get(io.grpc.Context.current()), "frank");
          Assert.assertEquals(Context.current().get(password), "valentine");
        } finally {
          grpcContext2.detach(previous2);
        }
        assertNull(username.get(io.grpc.Context.current()));
        Assert.assertEquals(Context.current().get(password), "valentine");
      }
    } finally {
      grpcContext.detach(previous);
    }
    Assert.assertNull(username.get(io.grpc.Context.current()));
    Assert.assertNull(Context.current().get(password));
  }

  @Test
  public void grpcContextDetachError() {
    io.grpc.Context grpcContext = io.grpc.Context.current().withValue(username, "jeff");
    io.grpc.Context previous = grpcContext.attach();
    try {
      previous.detach(grpcContext);
      assertEquals(username.get(io.grpc.Context.current()), "jeff");
    } finally {
      grpcContext.detach(previous);
    }
  }
}
