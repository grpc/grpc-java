package io.grpc.internal;

import com.google.common.base.Optional;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import com.google.common.base.Objects;
import javax.annotation.Nullable;

/**
 * A utility class to detect which proxy, if any, should be used for a given
 * {@link java.net.InetSocketAddress}.
 */
public interface ProxyDetector {
  ProxyDetector DEFAULT_INSTANCE = new ProxyDetectorImpl();

  /** A proxy detector that always claims no proxy is needed, for unit test convenience. */
  ProxyDetector NOOP_INSTANCE = new ProxyDetector() {
    @Override
    public Optional<ProxyParameters> proxyFor(SocketAddress targetServerAddress) {
      return Optional.absent();
    }
  };

  /**
   * Given a target address, returns which proxy address should be used. If no proxy should be
   * used, then return value will be an absent value.
   */
  Optional<ProxyDetectorImpl.ProxyParameters> proxyFor(SocketAddress targetServerAddress);

  class ProxyParameters {
    public final InetSocketAddress address;
    @Nullable public final String username;
    @Nullable public final String password;

    ProxyParameters(
        InetSocketAddress address,
        @Nullable String username,
        @Nullable String password) {
      this.address = address;
      this.username = username;
      this.password = password;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ProxyParameters)) {
        return false;
      }
      ProxyParameters that = (ProxyParameters) o;
      return Objects.equal(address, that.address)
          && Objects.equal(username, that.username)
          && Objects.equal(password, that.password);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(address, username, password);
    }
  }
}
