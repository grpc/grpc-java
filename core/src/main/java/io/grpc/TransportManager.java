/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import com.google.common.base.Supplier;
import java.util.Collection;

/**
 * Manages transport life-cycles and provide ready-to-use transports.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1781")
public abstract class TransportManager<T> {
  /**
   * Advises this {@code TransportManager} to retain transports only to these servers, for warming
   * up connections and discarding unused connections.
   */
  public abstract void updateRetainedTransports(Collection<EquivalentAddressGroup> addrs);

  /**
   * Returns a transport for any of the addresses from the given address group.
   *
   * <p>Never returns {@code null}
   */
  public abstract T getTransport(EquivalentAddressGroup addressGroup);

  /**
   * Creates a transport that would fail all RPCs with the given error.
   */
  public abstract T createFailingTransport(Status error);

  /**
   * Returns a transport that is not associated with any address. It holds RPCs until it's closed,
   * at which moment all held RPCs are transferred to actual transports.
   *
   * <p>This method is typically used in lieu of {@link #getTransport} before server addresses are
   * known.
   *
   * <p>The returned interim transport is tracked by this {@link TransportManager}. You must call
   * {@link InterimTransport#closeWithRealTransports} or {@link InterimTransport#closeWithError}
   * when it's no longer used, so that {@link TransportManager} can get rid of it.
   */
  public abstract InterimTransport<T> createInterimTransport();

  /**
   * Creates an {@link OobTransportProvider} with a specific authority.
   */
  public abstract OobTransportProvider<T> createOobTransportProvider(
      EquivalentAddressGroup addressGroup, String authority);

  /**
   * Returns a channel that uses {@code transport}; useful for issuing RPCs on a transport.
   */
  public abstract Channel makeChannel(T transport);

  /**
   * A transport provided as a temporary holder of new requests, which will be eventually
   * transferred to real transports or fail.
   *
   * @see #createInterimTransport
   */
  public interface InterimTransport<T> {
    /**
     * Returns the transport object.
     *
     * @throws IllegalStateException if {@link #closeWithRealTransports} or {@link #closeWithError}
     *     has been called
     */
    T transport();

    /**
     * Closes the interim transport by transferring pending RPCs to the given real transports.
     *
     * <p>Each pending RPC will result in an invocation to {@link Supplier#get} once.
     */
    void closeWithRealTransports(Supplier<T> realTransports);

    /**
     * Closes the interim transport by failing all pending RPCs with the given error.
     */
    void closeWithError(Status error);
  }

  /**
   * A provider for out-of-band transports, usually used by a load-balancer that needs to
   * communicate with an external load-balancing service which is under an authority different from
   * what the channel is associated with.
   */
  public interface OobTransportProvider<T> {
    /**
     * Returns an OOB transport.
     */
    T get();

    /**
     * Closes the provider and shuts down all associated transports.
     */
    void close();
  }
}
