/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds.internal.rbac.engine;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.io.BaseEncoding;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.xds.internal.Matchers;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Implementation of gRPC server access control based on envoy RBAC protocol:
 * https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto
 *
 * <p>One GrpcAuthorizationEngine is initialized with one action type and a list of policies.
 * Policies are examined sequentially in order in an any match fashion, and the first matched policy
 * will be returned. If not matched at all, the opposite action type is returned as a result.
 */
public final class GrpcAuthorizationEngine {
  private static final Logger log = Logger.getLogger(GrpcAuthorizationEngine.class.getName());

  private final AuthConfig authConfig;

  /** Instantiated with envoy policyMatcher configuration. */
  public GrpcAuthorizationEngine(AuthConfig authConfig) {
    this.authConfig = authConfig;
  }

  /** Return the auth decision for the request argument against the policies. */
  public AuthDecision evaluate(Metadata metadata, ServerCall<?,?> serverCall) {
    checkNotNull(metadata, "metadata");
    checkNotNull(serverCall, "serverCall");
    String firstMatch = null;
    EvaluateArgs args = new EvaluateArgs(metadata, serverCall);
    for (PolicyMatcher policyMatcher : authConfig.policies) {
      if (policyMatcher.matches(args)) {
        firstMatch = policyMatcher.name;
        break;
      }
    }
    Action decisionType = Action.DENY;
    if (Action.DENY.equals(authConfig.action) == (firstMatch == null)) {
      decisionType = Action.ALLOW;
    }
    log.log(Level.FINER, "RBAC decision: {0}, policy match: {1}.",
            new Object[]{decisionType, firstMatch});
    return AuthDecision.create(decisionType, firstMatch);
  }

  public enum Action {
    ALLOW,
    DENY,
  }

  /**
   * An authorization decision provides information about the decision type and the policy name
   * identifier based on the authorization engine evaluation. */
  @AutoValue
  public abstract static class AuthDecision {
    public abstract Action decision();

    @Nullable
    public abstract String matchingPolicyName();

    static AuthDecision create(Action decisionType, @Nullable String matchingPolicy) {
      return new AutoValue_GrpcAuthorizationEngine_AuthDecision(decisionType, matchingPolicy);
    }
  }

  /** Represents authorization config policy that the engine will evaluate against. */
  public static final class AuthConfig {
    private final List<PolicyMatcher> policies;
    private final Action action;

    public AuthConfig(List<PolicyMatcher> policies, Action action) {
      this.policies = Collections.unmodifiableList(new ArrayList<>(policies));
      this.action = action;
    }
  }

  /**
   * Implements a top level {@link Matcher} for a single RBAC policy configuration per envoy
   * protocol:
   * https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto#config-rbac-v3-policy.
   *
   * <p>Currently we only support matching some of the request fields. Those unsupported fields are
   * considered not match until we stop ignoring them.
   */
  public static final class PolicyMatcher implements Matcher {
    private final OrMatcher permissions;
    private final OrMatcher principals;
    private final String name;

    /** Constructs a matcher for one RBAC policy. */
    public PolicyMatcher(String name, OrMatcher permissions, OrMatcher principals) {
      this.name = name;
      this.permissions = permissions;
      this.principals = principals;
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return permissions.matches(args) && principals.matches(args);
    }
  }

  public static final class AuthenticatedMatcher implements Matcher {
    private final Matchers.StringMatcher delegate;

    /**
     * Passing in null will match all authenticated user, i.e. SSL session is present.
     * https://github.com/envoyproxy/envoy/blob/main/api/envoy/config/rbac/v3/rbac.proto#L240
     * */
    public AuthenticatedMatcher(@Nullable Matchers.StringMatcher delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      Collection<String> principalNames = args.getPrincipalNames();
      log.log(Level.FINER, "Matching principal names: {0}", new Object[]{principalNames});
      // Null means unauthenticated connection.
      if (principalNames == null) {
        return false;
      }
      // Connection is authenticated, so returns match when delegated string matcher is not present.
      if (delegate == null) {
        return true;
      }
      for (String name : principalNames) {
        if (delegate.matches(name)) {
          return true;
        }
      }
      return false;
    }
  }

  public static final class DestinationIpMatcher implements Matcher {
    private final Matchers.CidrMatcher delegate;

    public DestinationIpMatcher(Matchers.CidrMatcher delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getDestinationIp());
    }
  }

  public static final class SourceIpMatcher implements Matcher {
    private final Matchers.CidrMatcher delegate;

    public SourceIpMatcher(Matchers.CidrMatcher delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getSourceIp());
    }
  }

  public static final class PathMatcher implements Matcher {
    private final Matchers.StringMatcher delegate;

    public PathMatcher(Matchers.StringMatcher delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getPath());
    }
  }

  public static final class AuthHeaderMatcher implements Matcher {
    private final Matchers.HeaderMatcher delegate;

    public AuthHeaderMatcher(Matchers.HeaderMatcher delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getHeader(delegate.name()));
    }
  }

  public static final class DestinationPortMatcher implements Matcher {
    private final int port;

    public DestinationPortMatcher(int port) {
      this.port = port;
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return port == args.getDestinationPort();
    }
  }

  public static final class DestinationPortRangeMatcher implements Matcher {
    private final int start;
    private final int end;

    /** Start of the range is inclusive. End of the range is exclusive.*/
    public DestinationPortRangeMatcher(int start, int end) {
      this.start = start;
      this.end = end;
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      int port = args.getDestinationPort();
      return  port >= start && port < end;
    }
  }

  public static final class RequestedServerNameMatcher implements Matcher {
    private final Matchers.StringMatcher delegate;

    public RequestedServerNameMatcher(Matchers.StringMatcher delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getRequestedServerName());
    }
  }

  private static final class EvaluateArgs {
    private final Metadata metadata;
    private final ServerCall<?,?> serverCall;
    // https://github.com/envoyproxy/envoy/blob/63619d578e1abe0c1725ea28ba02f361466662e1/api/envoy/config/rbac/v3/rbac.proto#L238-L240
    private static final int URI_SAN = 6;
    private static final int DNS_SAN = 2;

    private EvaluateArgs(Metadata metadata, ServerCall<?,?> serverCall) {
      this.metadata = metadata;
      this.serverCall = serverCall;
    }

    private String getPath() {
      return "/" + serverCall.getMethodDescriptor().getFullMethodName();
    }

    /**
     * Returns null for unauthenticated connection.
     * Returns empty string collection if no valid certificate and no
     * principal names we are interested in.
     * https://github.com/envoyproxy/envoy/blob/0fae6970ddaf93f024908ba304bbd2b34e997a51/envoy/ssl/connection.h#L70
     */
    @Nullable
    private Collection<String> getPrincipalNames() {
      SSLSession sslSession = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      if (sslSession == null) {
        return null;
      }
      try {
        Certificate[] certs = sslSession.getPeerCertificates();
        if (certs == null || certs.length < 1) {
          return Collections.singleton("");
        }
        X509Certificate cert = (X509Certificate)certs[0];
        if (cert == null) {
          return Collections.singleton("");
        }
        Collection<List<?>> names = cert.getSubjectAlternativeNames();
        List<String> principalNames = new ArrayList<>();
        if (names != null) {
          for (List<?> name : names) {
            if (URI_SAN == (Integer) name.get(0)) {
              principalNames.add((String) name.get(1));
            }
          }
          if (!principalNames.isEmpty()) {
            return Collections.unmodifiableCollection(principalNames);
          }
          for (List<?> name : names) {
            if (DNS_SAN == (Integer) name.get(0)) {
              principalNames.add((String) name.get(1));
            }
          }
          if (!principalNames.isEmpty()) {
            return Collections.unmodifiableCollection(principalNames);
          }
        }
        if (cert.getSubjectDN() == null || cert.getSubjectDN().getName() == null) {
          return Collections.singleton("");
        }
        return Collections.singleton(cert.getSubjectDN().getName());
      } catch (SSLPeerUnverifiedException | CertificateParsingException ex) {
        log.log(Level.FINE, "Unexpected getPrincipalNames error.", ex);
        return Collections.singleton("");
      }
    }

    @Nullable
    private String getHeader(String headerName) {
      headerName = headerName.toLowerCase(Locale.ROOT);
      if ("te".equals(headerName)) {
        return null;
      }
      if (":authority".equals(headerName)) {
        headerName = "host";
      }
      if ("host".equals(headerName)) {
        return serverCall.getAuthority();
      }
      if (":path".equals(headerName)) {
        return getPath();
      }
      if (":method".equals(headerName)) {
        return "POST";
      }
      return deserializeHeader(headerName);
    }

    @Nullable
    private String deserializeHeader(String headerName) {
      if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Metadata.Key<byte[]> key;
        try {
          key = Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER);
        } catch (IllegalArgumentException e) {
          return null;
        }
        Iterable<byte[]> values = metadata.getAll(key);
        if (values == null) {
          return null;
        }
        List<String> encoded = new ArrayList<>();
        for (byte[] v : values) {
          encoded.add(BaseEncoding.base64().omitPadding().encode(v));
        }
        return Joiner.on(",").join(encoded);
      }
      Metadata.Key<String> key;
      try {
        key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
      } catch (IllegalArgumentException e) {
        return null;
      }
      Iterable<String> values = metadata.getAll(key);
      return values == null ? null : Joiner.on(",").join(values);
    }

    private InetAddress getDestinationIp() {
      SocketAddress addr = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR);
      return addr == null ? null : ((InetSocketAddress) addr).getAddress();
    }

    private InetAddress getSourceIp() {
      SocketAddress addr = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      return addr == null ? null : ((InetSocketAddress) addr).getAddress();
    }

    private int getDestinationPort() {
      SocketAddress addr = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR);
      return addr == null ? -1 : ((InetSocketAddress) addr).getPort();
    }

    private String getRequestedServerName() {
      return "";
    }
  }

  public interface Matcher {
    boolean matches(EvaluateArgs args);
  }

  public static final class OrMatcher implements Matcher {
    private final List<? extends Matcher> anyMatch;

    /** Matches when any of the matcher matches. */
    public OrMatcher(List<? extends Matcher> matchers) {
      checkNotNull(matchers, "matchers");
      for (Matcher matcher : matchers) {
        checkNotNull(matcher, "matcher");
      }
      this.anyMatch = Collections.unmodifiableList(new ArrayList<>(matchers));
    }

    public static OrMatcher create(Matcher...matchers) {
      return new OrMatcher(Arrays.asList(matchers));
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      for (Matcher m : anyMatch) {
        if (m.matches(args)) {
          return true;
        }
      }
      return false;
    }
  }

  public static final class AndMatcher implements Matcher {
    private final List<? extends Matcher> allMatch;

    /** Matches when all of the matchers match. */
    public AndMatcher(List<? extends Matcher> matchers) {
      checkNotNull(matchers, "matchers");
      for (Matcher matcher : matchers) {
        checkNotNull(matcher, "matcher");
      }
      this.allMatch = Collections.unmodifiableList(new ArrayList<>(matchers));
    }

    public static AndMatcher create(Matcher...matchers) {
      return new AndMatcher(Arrays.asList(matchers));
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      for (Matcher m : allMatch) {
        if (!m.matches(args)) {
          return false;
        }
      }
      return true;
    }
  }

  /** Always true matcher.*/
  public static final class AlwaysTrueMatcher implements Matcher {
    public static AlwaysTrueMatcher INSTANCE = new AlwaysTrueMatcher();

    @Override
    public boolean matches(EvaluateArgs args) {
      return true;
    }
  }

  /** Negate matcher.*/
  public static final class InvertMatcher implements Matcher {
    private final Matcher toInvertMatcher;

    public InvertMatcher(Matcher matcher) {
      this.toInvertMatcher = checkNotNull(matcher, "matcher");
    }

    @Override
    public boolean matches(EvaluateArgs args) {
      return !toInvertMatcher.matches(args);
    }
  }
}
