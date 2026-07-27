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

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ClientCall;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.FixedPortProvider;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ManagedChannelImplAndDelayedTransportStressTest {

  private ExecutorService executor;
  private ScheduledExecutorService scheduledExecutor;
  private ClientTransportFactory mockTransportFactory;

  @Before
  public void setUp() {
    executor = Executors.newFixedThreadPool(16);
    scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    mockTransportFactory = mock(ClientTransportFactory.class);
    when(mockTransportFactory.getScheduledExecutorService()).thenReturn(scheduledExecutor);
    ConnectionClientTransport mockTransport = mock(ConnectionClientTransport.class);
    when(mockTransportFactory.newClientTransport(
            any(SocketAddress.class),
            any(ClientTransportFactory.ClientTransportOptions.class),
            any(ChannelLogger.class)))
        .thenReturn(mockTransport);
    when(mockTransportFactory.getSupportedSocketAddressTypes())
        .thenReturn(Collections.<Class<? extends SocketAddress>>singleton(
            InetSocketAddress.class));
  }

  @After
  public void tearDown() {
    if (executor != null) {
      executor.shutdownNow();
    }
    if (scheduledExecutor != null) {
      scheduledExecutor.shutdownNow();
    }
  }

  public static class StressCallDelayTracerFactory extends ClientStreamTracer.Factory {
    public final AtomicInteger callDelayStartedCount = new AtomicInteger();
    public final AtomicInteger callDelayEndedCount = new AtomicInteger();
    public final AtomicInteger activeCallDelaySpans = new AtomicInteger();
    public final AtomicInteger outOfOrderEnds = new AtomicInteger();

    @Override
    public ClientStreamTracer newClientStreamTracer(
        ClientStreamTracer.StreamInfo info, Metadata headers) {
      return new ClientStreamTracer() {};
    }

    @Override
    public void recordCallDelayStart(String delayType, String delayReason) {
      callDelayStartedCount.incrementAndGet();
      activeCallDelaySpans.incrementAndGet();
    }

    @Override
    public void recordCallDelayEnd() {
      callDelayEndedCount.incrementAndGet();
      int remaining = activeCallDelaySpans.decrementAndGet();
      if (remaining < 0) {
        outOfOrderEnds.incrementAndGet();
      }
    }
  }

  public static class StressAttemptDelayTracer extends ClientStreamTracer {
    public final AtomicInteger attemptDelayStartedCount = new AtomicInteger();
    public final AtomicInteger attemptDelayEndedCount = new AtomicInteger();
    public final AtomicInteger activeAttemptDelaySpans = new AtomicInteger();
    public final AtomicInteger outOfOrderEnds = new AtomicInteger();

    @Override
    public void recordAttemptDelayStart(String delayType, String delayReason) {
      attemptDelayStartedCount.incrementAndGet();
      activeAttemptDelaySpans.incrementAndGet();
    }

    @Override
    public void recordAttemptDelayEnd() {
      attemptDelayEndedCount.incrementAndGet();
      int remaining = activeAttemptDelaySpans.decrementAndGet();
      if (remaining < 0) {
        outOfOrderEnds.incrementAndGet();
      }
    }
  }

  private static class PendingNameResolver extends NameResolver {
    @Override
    public String getServiceAuthority() {
      return "fakeAuthority";
    }

    @Override
    public void start(Listener2 listener) {}

    @Override
    public void shutdown() {}
  }

  @Test
  public void testManagedChannelImplPendingCallConcurrency_40k() throws Exception {
    int totalIterations = 40_000;
    int numThreads = 8;
    int perThread = totalIterations / numThreads;

    MethodDescriptor<Void, Void> method =
        MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("service/method")
            .setRequestMarshaller(new MethodDescriptor.Marshaller<Void>() {
              @Override
              public InputStream stream(Void value) {
                return null;
              }

              @Override
              public Void parse(InputStream stream) {
                return null;
              }
            })
            .setResponseMarshaller(new MethodDescriptor.Marshaller<Void>() {
              @Override
              public InputStream stream(Void value) {
                return null;
              }

              @Override
              public Void parse(InputStream stream) {
                return null;
              }
            })
            .build();

    ManagedChannelBuilder<?> builder = new ManagedChannelImplBuilder(
        "pendingfake:///target",
        new ClientTransportFactoryBuilder() {
          @Override
          public ClientTransportFactory buildClientTransportFactory() {
            return mockTransportFactory;
          }
        },
        new FixedPortProvider(443));
    builder.executor(executor);
    ((ManagedChannelImplBuilder) builder).nameResolverFactory(new NameResolver.Factory() {
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new PendingNameResolver();
      }

      @Override
      public String getDefaultScheme() {
        return "pendingfake";
      }
    });

    ManagedChannel channel = builder.build();

    StressCallDelayTracerFactory[] factories = new StressCallDelayTracerFactory[totalIterations];
    for (int i = 0; i < totalIterations; i++) {
      factories[i] = new StressCallDelayTracerFactory();
    }

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numThreads);

    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      executor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            startLatch.await();
            int start = threadIdx * perThread;
            int end = start + perThread;
            for (int i = start; i < end; i++) {
              final StressCallDelayTracerFactory factory = factories[i];
              CallOptions options = CallOptions.DEFAULT.withStreamTracerFactory(factory);
              ClientCall<Void, Void> call = channel.newCall(method, options);
              call.start(new ClientCall.Listener<Void>() {}, new Metadata());
              call.cancel("cancelled for stress test", null);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            doneLatch.countDown();
          }
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(60, TimeUnit.SECONDS));

    channel.shutdownNow();
    assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS));

    int totalOrphaned = 0;
    int totalOutOfOrder = 0;
    for (int i = 0; i < totalIterations; i++) {
      totalOrphaned += factories[i].activeCallDelaySpans.get();
      totalOutOfOrder += factories[i].outOfOrderEnds.get();
    }

    assertEquals("Orphaned call delay spans leaked!", 0, totalOrphaned);
    assertEquals("Out-of-order call delay end calls!", 0, totalOutOfOrder);
  }

  @Test
  public void testDelayedClientTransportPendingStreamConcurrency_40k() throws Exception {
    int totalIterations = 40_000;
    int numThreads = 8;
    int perThread = totalIterations / numThreads;

    MethodDescriptor<Void, Void> method =
        MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("service/method")
            .setRequestMarshaller(new MethodDescriptor.Marshaller<Void>() {
              @Override
              public InputStream stream(Void value) {
                return null;
              }

              @Override
              public Void parse(InputStream stream) {
                return null;
              }
            })
            .setResponseMarshaller(new MethodDescriptor.Marshaller<Void>() {
              @Override
              public InputStream stream(Void value) {
                return null;
              }

              @Override
              public Void parse(InputStream stream) {
                return null;
              }
            })
            .build();

    SynchronizationContext syncContext =
        new SynchronizationContext(new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            e.printStackTrace();
          }
        });

    DelayedClientTransport transport = new DelayedClientTransport(executor, syncContext);
    transport.start(mock(ManagedClientTransport.Listener.class));

    StressAttemptDelayTracer[] tracers = new StressAttemptDelayTracer[totalIterations];
    for (int i = 0; i < totalIterations; i++) {
      tracers[i] = new StressAttemptDelayTracer();
    }

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numThreads);

    for (int t = 0; t < numThreads; t++) {
      final int threadIdx = t;
      executor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            startLatch.await();
            int start = threadIdx * perThread;
            int end = start + perThread;
            for (int i = start; i < end; i++) {
              final StressAttemptDelayTracer tracer = tracers[i];
              CallOptions options = CallOptions.DEFAULT;
              Metadata headers = new Metadata();
              ClientStream stream = transport.newStream(
                  method, headers, options, new ClientStreamTracer[] { tracer });
              stream.start(mock(ClientStreamListener.class));

              if (i % 2 == 0) {
                stream.cancel(Status.CANCELLED);
              } else {
                transport.reprocess(new SubchannelPicker() {
                  @Override
                  public PickResult pickSubchannel(PickSubchannelArgs args) {
                    return PickResult.withNoResult();
                  }
                });
                stream.cancel(Status.CANCELLED);
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            doneLatch.countDown();
          }
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(60, TimeUnit.SECONDS));

    int totalOrphaned = 0;
    int totalOutOfOrder = 0;
    for (int i = 0; i < totalIterations; i++) {
      totalOrphaned += tracers[i].activeAttemptDelaySpans.get();
      totalOutOfOrder += tracers[i].outOfOrderEnds.get();
    }

    assertEquals("Orphaned attempt delay spans leaked!", 0, totalOrphaned);
    assertEquals("Out-of-order attempt delay end calls!", 0, totalOutOfOrder);
  }
}
