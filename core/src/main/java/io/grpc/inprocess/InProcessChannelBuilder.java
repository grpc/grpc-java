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

package io.grpc.inprocess;

import com.google.common.base.Preconditions;
import com.google.instrumentation.stats.StatsContextFactory;
import io.grpc.ExperimentalApi;
import io.grpc.Internal;
import io.grpc.internal.AbstractManagedChannelImplBuilder;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.NoopStatsContextFactory;
import java.net.SocketAddress;

/**
 * Builder for a channel that issues in-process requests. Clients identify the in-process server by
 * its name.
 *
 * <p>The channel is intended to be fully-featured, high performance, and useful in testing.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1783")
public class InProcessChannelBuilder extends
        AbstractManagedChannelImplBuilder<InProcessChannelBuilder> {
  /**
   * Create a channel builder that will connect to the server with the given name.
   *
   * @param name the identity of the server to connect to
   * @return a new builder
   */
  public static InProcessChannelBuilder forName(String name) {
    return new InProcessChannelBuilder(name);
  }

  private final String name;

  private InProcessChannelBuilder(String name) {
    super(new InProcessSocketAddress(name), "localhost");
    this.name = Preconditions.checkNotNull(name, "name");
    // TODO(zhangkun83): InProcessTransport by-passes framer and deframer, thus message sizses are
    // not counted.  Therefore, we disable stats for now.
    // (https://github.com/grpc/grpc-java/issues/2284)
    super.statsContextFactory(NoopStatsContextFactory.INSTANCE);
  }

  @Override
  public final InProcessChannelBuilder maxInboundMessageSize(int max) {
    // TODO(carl-mastrangelo): maybe throw an exception since this not enforced?
    return super.maxInboundMessageSize(max);
  }

  /**
   * Does nothing.
   */
  @Override
  public InProcessChannelBuilder usePlaintext(boolean skipNegotiation) {
    return this;
  }

  @Override
  protected ClientTransportFactory buildTransportFactory() {
    return new InProcessClientTransportFactory(name);
  }

  @Internal
  @Override
  public InProcessChannelBuilder statsContextFactory(StatsContextFactory statsFactory) {
    // TODO(zhangkun83): InProcessTransport by-passes framer and deframer, thus message sizses are
    // not counted.  Stats is disabled by using a NOOP stats factory in the constructor, and here
    // we prevent the user from overriding it.
    // (https://github.com/grpc/grpc-java/issues/2284)
    return this;
  }

  /**
   * Creates InProcess transports. Exposed for internal use, as it should be private.
   */
  @Internal
  static final class InProcessClientTransportFactory implements ClientTransportFactory {
    private final String name;

    private boolean closed;

    private InProcessClientTransportFactory(String name) {
      this.name = name;
    }

    @Override
    public ConnectionClientTransport newClientTransport(
        SocketAddress addr, String authority, String userAgent) {
      if (closed) {
        throw new IllegalStateException("The transport factory is closed.");
      }
      return new InProcessTransport(name, authority);
    }

    @Override
    public void close() {
      closed = true;
      // Do nothing.
    }
  }
}
