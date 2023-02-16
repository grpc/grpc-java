/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A builder for {@link Server} instances.
 *
 * @param <T> The concrete type of this builder.
 * @since 1.0.0
 */
public abstract class ServerBuilder<T extends ServerBuilder<T>> {

  /**
   * Static factory for creating a new ServerBuilder.
   *
   * @param port the port to listen on
   * @since 1.0.0
   */
  public static ServerBuilder<?> forPort(int port) {
    return ServerProvider.provider().builderForPort(port);
  }

  /**
   * Execute application code directly in the transport thread.
   *
   * <p>Depending on the underlying transport, using a direct executor may lead to substantial
   * performance improvements. However, it also requires the application to not block under
   * any circumstances.
   *
   * <p>Calling this method is semantically equivalent to calling {@link #executor(Executor)} and
   * passing in a direct executor. However, this is the preferred way as it may allow the transport
   * to perform special optimizations.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T directExecutor();

  /**
   * Provides a custom executor.
   *
   * <p>It's an optional parameter. If the user has not provided an executor when the server is
   * built, the builder will use a static cached thread pool.
   *
   * <p>The server won't take ownership of the given executor. It's caller's responsibility to
   * shut down the executor when it's desired.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T executor(@Nullable Executor executor);


  /**
   * Allows for defining a way to provide a custom executor to handle the server call.
   * This executor is the result of calling
   * {@link ServerCallExecutorSupplier#getExecutor(ServerCall, Metadata)} per RPC.
   *
   * <p>It's an optional parameter. If it is provided, the {@link #executor(Executor)} would still
   * run necessary tasks before the {@link ServerCallExecutorSupplier} is ready to be called, then
   * it switches over. But if calling {@link ServerCallExecutorSupplier} returns null, the server
   * call is still handled by the default {@link #executor(Executor)} as a fallback.
   *
   * @param executorSupplier the server call executor provider
   * @return this
   * @since 1.39.0
   *
   * */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8274")
  public T callExecutor(ServerCallExecutorSupplier executorSupplier) {
    return thisT();
  }

  /**
   * Adds a service implementation to the handler registry.
   *
   * @param service ServerServiceDefinition object
   * @return this
   * @since 1.0.0
   */
  public abstract T addService(ServerServiceDefinition service);

  /**
   * Adds a service implementation to the handler registry.
   *
   * @param bindableService BindableService object
   * @return this
   * @since 1.0.0
   */
  public abstract T addService(BindableService bindableService);

  /**
   * Adds a list of service implementations to the handler registry together.
   *
   * @param services the list of ServerServiceDefinition objects
   * @return this
   * @since 1.37.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7925")
  public final T addServices(List<ServerServiceDefinition> services) {
    checkNotNull(services, "services");
    for (ServerServiceDefinition service : services) {
      addService(service);
    }
    return thisT();
  }

  /**
   * Adds a {@link ServerInterceptor} that is run for all services on the server.  Interceptors
   * added through this method always run before per-service interceptors added through {@link
   * ServerInterceptors}.  Interceptors run in the reverse order in which they are added, just as
   * with consecutive calls to {@code ServerInterceptors.intercept()}.
   *
   * @param interceptor the all-service interceptor
   * @return this
   * @since 1.5.0
   */
  public T intercept(ServerInterceptor interceptor) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds a {@link ServerTransportFilter}. The order of filters being added is the order they will
   * be executed.
   *
   * @return this
   * @since 1.2.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2132")
  public T addTransportFilter(ServerTransportFilter filter) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds a {@link ServerStreamTracer.Factory} to measure server-side traffic.  The order of
   * factories being added is the order they will be executed.
   *
   * @return this
   * @since 1.3.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
  public T addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets a fallback handler registry that will be looked up in if a method is not found in the
   * primary registry. The primary registry (configured via {@code addService()}) is faster but
   * immutable. The fallback registry is more flexible and allows implementations to mutate over
   * time and load services on-demand.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry);

  /**
   * Makes the server use TLS.
   *
   * @param certChain file containing the full certificate chain
   * @param privateKey file containing the private key
   *
   * @return this
   * @throws UnsupportedOperationException if the server does not support TLS.
   * @since 1.0.0
   */
  public abstract T useTransportSecurity(File certChain, File privateKey);

  /**
   * Makes the server use TLS.
   *
   * @param certChain InputStream containing the full certificate chain
   * @param privateKey InputStream containing the private key
   *
   * @return this
   * @throws UnsupportedOperationException if the server does not support TLS, or does not support
   *         reading these files from an InputStream.
   * @since 1.12.0
   */
  public T useTransportSecurity(InputStream certChain, InputStream privateKey) {
    throw new UnsupportedOperationException();
  }


  /**
   * Set the decompression registry for use in the channel.  This is an advanced API call and
   * shouldn't be used unless you are using custom message encoding.   The default supported
   * decompressors are in {@code DecompressorRegistry.getDefaultInstance}.
   *
   * @return this
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
  public abstract T decompressorRegistry(@Nullable DecompressorRegistry registry);

  /**
   * Set the compression registry for use in the channel.  This is an advanced API call and
   * shouldn't be used unless you are using custom message encoding.   The default supported
   * compressors are in {@code CompressorRegistry.getDefaultInstance}.
   *
   * @return this
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
  public abstract T compressorRegistry(@Nullable CompressorRegistry registry);

  /**
   * Sets the permitted time for new connections to complete negotiation handshakes before being
   * killed.
   *
   * @return this
   * @throws IllegalArgumentException if timeout is negative
   * @throws UnsupportedOperationException if unsupported
   * @since 1.8.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3706")
  public T handshakeTimeout(long timeout, TimeUnit unit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the time without read activity before sending a keepalive ping. An unreasonably small
   * value might be increased, and {@code Long.MAX_VALUE} nano seconds or an unreasonably large
   * value will disable keepalive. The typical default is two hours when supported.
   *
   * @throws IllegalArgumentException if time is not positive
   * @throws UnsupportedOperationException if unsupported
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets a time waiting for read activity after sending a keepalive ping. If the time expires
   * without any read activity on the connection, the connection is considered dead. An unreasonably
   * small value might be increased. Defaults to 20 seconds when supported.
   *
   * <p>This value should be at least multiple times the RTT to allow for lost packets.
   *
   * @throws IllegalArgumentException if timeout is not positive
   * @throws UnsupportedOperationException if unsupported
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum connection idle time, connections being idle for longer than which will be
   * gracefully terminated. Idleness duration is defined since the most recent time the number of
   * outstanding RPCs became zero or the connection establishment. An unreasonably small value might
   * be increased. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
   * max connection idle.
   *
   * @throws IllegalArgumentException if idle is not positive
   * @throws UnsupportedOperationException if unsupported
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T maxConnectionIdle(long maxConnectionIdle, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum connection age, connections lasting longer than which will be gracefully
   * terminated. An unreasonably small value might be increased. A random jitter of +/-10% will be
   * added to it. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
   * max connection age.
   *
   * @throws IllegalArgumentException if age is not positive
   * @throws UnsupportedOperationException if unsupported
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T maxConnectionAge(long maxConnectionAge, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the grace time for the graceful connection termination. Once the max connection age
   * is reached, RPCs have the grace time to complete. RPCs that do not complete in time will be
   * cancelled, allowing the connection to terminate. {@code Long.MAX_VALUE} nano seconds or an
   * unreasonably large value are considered infinite.
   *
   * @throws IllegalArgumentException if grace is negative
   * @throws UnsupportedOperationException if unsupported
   * @see #maxConnectionAge(long, TimeUnit)
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T maxConnectionAgeGrace(long maxConnectionAgeGrace, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Specify the most aggressive keep-alive time clients are permitted to configure. The server will
   * try to detect clients exceeding this rate and when detected will forcefully close the
   * connection. The typical default is 5 minutes when supported.
   *
   * <p>Even though a default is defined that allows some keep-alives, clients must not use
   * keep-alive without approval from the service owner. Otherwise, they may experience failures in
   * the future if the service becomes more restrictive. When unthrottled, keep-alives can cause a
   * significant amount of traffic and CPU usage, so clients and servers should be conservative in
   * what they use and accept.
   *
   * @throws IllegalArgumentException if time is negative
   * @throws UnsupportedOperationException if unsupported
   * @see #permitKeepAliveWithoutCalls(boolean)
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T permitKeepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets whether to allow clients to send keep-alive HTTP/2 PINGs even if there are no outstanding
   * RPCs on the connection. Defaults to {@code false} when supported.
   *
   * @throws UnsupportedOperationException if unsupported
   * @see #permitKeepAliveTime(long, TimeUnit)
   * @since 1.47.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9009")
  public T permitKeepAliveWithoutCalls(boolean permit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum message size allowed to be received on the server. If not called,
   * defaults to 4 MiB. The default provides protection to servers who haven't considered the
   * possibility of receiving large messages while trying to be large enough to not be hit in normal
   * usage.
   *
   * <p>This method is advisory, and implementations may decide to not enforce this.  Currently,
   * the only known transport to not enforce this is {@code InProcessServer}.
   *
   * @param bytes the maximum number of bytes a single message can be.
   * @return this
   * @throws IllegalArgumentException if bytes is negative.
   * @throws UnsupportedOperationException if unsupported.
   * @since 1.13.0
   */
  public T maxInboundMessageSize(int bytes) {
    // intentional noop rather than throw, this method is only advisory.
    Preconditions.checkArgument(bytes >= 0, "bytes must be >= 0");
    return thisT();
  }

  /**
   * Sets the maximum size of metadata allowed to be received. {@code Integer.MAX_VALUE} disables
   * the enforcement. The default is implementation-dependent, but is not generally less than 8 KiB
   * and may be unlimited.
   *
   * <p>This is cumulative size of the metadata. The precise calculation is
   * implementation-dependent, but implementations are encouraged to follow the calculation used for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
   * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. It sums the bytes from each entry's key and value,
   * plus 32 bytes of overhead per entry.
   *
   * @param bytes the maximum size of received metadata
   * @return this
   * @throws IllegalArgumentException if bytes is non-positive
   * @since 1.17.0
   */
  public T maxInboundMetadataSize(int bytes) {
    Preconditions.checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
    // intentional noop rather than throw, this method is only advisory.
    return thisT();
  }

  /**
   * Sets the BinaryLog object that this server should log to. The server does not take
   * ownership of the object, and users are responsible for calling {@link BinaryLog#close()}.
   *
   * @param binaryLog the object to provide logging.
   * @return this
   * @since 1.13.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4017")
  public T setBinaryLog(BinaryLog binaryLog) {
    throw new UnsupportedOperationException();
  }

  /**
   * Builds a server using the given parameters.
   *
   * <p>The returned service will not been started or be bound a port. You will need to start it
   * with {@link Server#start()}.
   *
   * @return a new Server
   * @since 1.0.0
   */
  public abstract Server build();

  /**
   * Returns the correctly typed version of the builder.
   */
  private T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
