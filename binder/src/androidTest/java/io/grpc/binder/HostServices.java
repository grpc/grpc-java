/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.lifecycle.LifecycleService;
import com.google.auto.value.AutoValue;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.grpc.NameResolver;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.internal.InternalServer;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A test helper class for creating android services to host gRPC servers.
 *
 * <p>Currently only supports two servers at a time. If more are required, define a new class, add
 * it to the manifest, and the hostServiceClasses array.
 */
public final class HostServices {

  private static final Logger logger = Logger.getLogger(HostServices.class.getName());

  private static final Class<?>[] hostServiceClasses =
      new Class<?>[] {
        HostService1.class, HostService2.class,
      };


  public interface ServerFactory {
    Server createServer(Service service, IBinderReceiver receiver);
  }

  @AutoValue
  public abstract static class ServiceParams {
    @Nullable
    abstract Executor transactionExecutor();

    @Nullable
    abstract Supplier<IBinder> rawBinderSupplier();

    @Nullable
    abstract ServerFactory serverFactory();

    public abstract Builder toBuilder();

    public static Builder builder() {
      return new AutoValue_HostServices_ServiceParams.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setRawBinderSupplier(Supplier<IBinder> binderSupplier);

      public abstract Builder setServerFactory(ServerFactory serverFactory);

      /**
       * If set, this executor will be used to pass any inbound transactions to the server. This can
       * be used to simulate delayed, re-ordered, or dropped packets.
       */
      public abstract Builder setTransactionExecutor(Executor transactionExecutor);

      public abstract ServiceParams build();
    }
  }

  @GuardedBy("HostServices.class")
  private static final Map<Class<?>, AndroidComponentAddress> serviceAddresses = new HashMap<>();

  @GuardedBy("HostServices.class")
  private static final Map<Class<?>, ServiceParams> serviceParams = new HashMap<>();

  @GuardedBy("HostServices.class")
  private static final Map<Class<?>, HostService> activeServices = new HashMap<>();

  @Nullable
  @GuardedBy("HostServices.class")
  private static CountDownLatch serviceShutdownLatch;

  private HostServices() {}

  /** Create a new {@link ServiceParams} builder. */
  public static ServiceParams.Builder serviceParamsBuilder() {
    return ServiceParams.builder();
  }

  /**
   * Wait for all services to shutdown. This should be called from a test's tearDown method, to
   * ensure the next test is able to use this class again (since Android itself is in control of the
   * services).
   */
  public static void awaitServiceShutdown() throws InterruptedException {
    CountDownLatch latch = null;
    synchronized (HostServices.class) {
      if (serviceShutdownLatch == null && !activeServices.isEmpty()) {
        latch = new CountDownLatch(activeServices.size());
        serviceShutdownLatch = latch;
      }
      serviceParams.clear();
      serviceAddresses.clear();
    }
    if (latch != null) {
      if (!latch.await(10, SECONDS)) {
        throw new AssertionError("Failed to shut down services");
      }
    }
    synchronized (HostServices.class) {
      checkState(activeServices.isEmpty());
      checkState(serviceParams.isEmpty());
      checkState(serviceAddresses.isEmpty());
      serviceShutdownLatch = null;
    }
  }

  /** Create the address for a host-service. */
  private static AndroidComponentAddress hostServiceAddress(Context appContext, Class<?> cls) {
    // NOTE: Even though we have a context object, we intentionally don't use a "local",
    // address, since doing so would mark the address with our UID for security purposes,
    // and that would limit the effectiveness of tests.
    // Using this API forces us to rely on Binder.getCallingUid.
    return AndroidComponentAddress.forRemoteComponent(appContext.getPackageName(), cls.getName());
  }

  /**
   * Allocate a new host service.
   *
   * @param appContext The application context.
   * @return The AndroidComponentAddress of the service.
   */
  public static synchronized AndroidComponentAddress allocateService(Context appContext) {
    for (Class<?> cls : hostServiceClasses) {
      if (!serviceAddresses.containsKey(cls)) {
        AndroidComponentAddress address = hostServiceAddress(appContext, cls);
        serviceAddresses.put(cls, address);
        return address;
      }
    }
    throw new AssertionError("This test helper only supports two services at a time.");
  }

  /**
   * Configure an allocated hosting service.
   *
   * @param androidComponentAddress The address of the service.
   * @param params The parameters used to build the service.
   */
  public static synchronized void configureService(
      AndroidComponentAddress androidComponentAddress, ServiceParams params) {
    for (Class<?> cls : hostServiceClasses) {
      if (serviceAddresses.get(cls).equals(androidComponentAddress)) {
        checkState(!serviceParams.containsKey(cls));
        serviceParams.put(cls, params);
        return;
      }
    }
    throw new AssertionError("Unable to find service for address " + androidComponentAddress);
  }

  /** An Android Service to host each gRPC server. */
  private abstract static class HostService extends LifecycleService {

    @Nullable private ServiceParams params;
    @Nullable private Supplier<IBinder> binderSupplier;
    @Nullable private Server server;

    @Override
    public final void onCreate() {
      super.onCreate();
      Class<?> cls = getClass();
      synchronized (HostServices.class) {
        checkState(!activeServices.containsKey(cls));
        activeServices.put(cls, this);
        checkState(serviceParams.containsKey(cls));
        params = serviceParams.get(cls);
        ServerFactory factory = params.serverFactory();
        if (factory != null) {
          IBinderReceiver receiver = new IBinderReceiver();
          server = factory.createServer(this, receiver);
          try {
            server.start();
          } catch (IOException ioe) {
            throw new AssertionError("Failed to start server", ioe);
          }
          binderSupplier = () -> receiver.get();
        } else {
          binderSupplier = params.rawBinderSupplier();
          if (binderSupplier == null) {
            throw new AssertionError("Insufficient params for host service");
          }
        }
      }
    }

    @Override
    public final IBinder onBind(Intent intent) {
      // Calling super here is a little weird (it returns null), but there's a @CallSuper
      // annotation.
      super.onBind(intent);
      synchronized (HostServices.class) {
        Executor executor = params.transactionExecutor();
        if (executor != null) {
          return new ProxyBinder(binderSupplier.get(), executor);
        } else {
          return binderSupplier.get();
        }
      }
    }

    @Override
    public final void onDestroy() {
      synchronized (HostServices.class) {
        if (server != null) {
          server.shutdown();
          server = null;
        }
        HostService removed = activeServices.remove(getClass());
        checkState(removed == this);
        serviceAddresses.remove(getClass());
        serviceParams.remove(getClass());
        if (serviceShutdownLatch != null) {
          serviceShutdownLatch.countDown();
        }
      }
      super.onDestroy();
    }
  }

  /** The first concrete host service */
  public static final class HostService1 extends HostService {}

  /** The second concrete host service */
  public static final class HostService2 extends HostService {}

  /** Wraps an IBinder to send incoming transactions to a different thread. */
  private static class ProxyBinder extends Binder {
    private final IBinder delegate;
    private final Executor executor;

    ProxyBinder(IBinder delegate, Executor executor) {
      this.delegate = delegate;
      this.executor = executor;
    }

    @Override
    protected boolean onTransact(int code, Parcel parcel, Parcel reply, int flags) {
      executor.execute(
          () -> {
            try {
              delegate.transact(code, parcel, reply, flags);
            } catch (RemoteException re) {
              logger.log(Level.WARNING, "Exception in proxybinder", re);
            }
          });
      return true;
    }
  }
}
