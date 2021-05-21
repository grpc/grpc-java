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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.Matchers.StringMatcher;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
  private final List<PolicyMatcher> policyMatchers;
  private final Action action;

  /** An authorization decision provides information about the decision type and the policy name
   * identifier based on the authorization engine evaluation. */
  @AutoValue
  public abstract static class AuthDecision {
    public enum DecisionType {
      ALLOW,
      DENY,
    }

    public abstract DecisionType decision();

    @Nullable
    public abstract String matchingPolicyName();

    static AuthDecision create(DecisionType decisionType, @Nullable String matchingPolicy) {
      return new AutoValue_GrpcAuthorizationEngine_AuthDecision(decisionType, matchingPolicy);
    }
  }

  /** Instantiated with envoy policyMatcher configuration. */
  public GrpcAuthorizationEngine(List<PolicyMatcher> policies, Action action) {
    this.action = action;
    this.policyMatchers = Collections.unmodifiableList(policies);
  }

  /** Return the auth decision for the request argument against the policies. */
  public AuthDecision evaluate(Metadata metadata, ServerCall<?,?> serverCall) {
    checkNotNull(metadata, "metadata");
    checkNotNull(serverCall, "serverCall");
    String firstMatch = null;
    EvaluateArgs args = new EvaluateArgs(metadata, serverCall);
    for (PolicyMatcher policyMatcher : policyMatchers) {
      if (policyMatcher.matches(args)) {
        firstMatch = policyMatcher.name;
        break;
      }
    }
    AuthDecision.DecisionType decisionType = AuthDecision.DecisionType.DENY;
    if (Action.DENY.equals(action) == (firstMatch == null)) {
      decisionType = AuthDecision.DecisionType.ALLOW;
    }
    log.log(Level.FINER, String.format("RBAC decision: {%s}, policy match: {%s}",
        decisionType, firstMatch));
    return AuthDecision.create(decisionType, firstMatch);
  }

  /**
   * Implements a top level {@link Matcher} for a single RBAC policy configuration per envoy
   * protocol:
   * https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto#config-rbac-v3-policy.
   *
   * <p>Currently we only support matching some of the request fields. Those unsupported fields are
   * considered not match until we stop ignoring them.
   */
  static class PolicyMatcher extends Matcher {
    private final Matcher permissions;
    private final Matcher principals;
    private final String name;

    /** Constructs a matcher for one RBAC policy. */
    PolicyMatcher(String name, OrMatcher permissions, OrMatcher principals) {
      this.name = name;
      this.permissions = permissions;
      this.principals = principals;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return permissions.matches(args) && principals.matches(args);
    }
  }

  static class AuthenticatedMatcher extends Matcher {
    private final StringMatcher delegate;

    AuthenticatedMatcher(StringMatcher delegate) {
      this.delegate = delegate;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getPrincipalName());
    }
  }

  static class DestinationIpMatcher extends Matcher {
    private final CidrMatcher delegate;

    DestinationIpMatcher(CidrMatcher delegate) {
      this.delegate = delegate;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getDestinationIp());
    }
  }

  static class SourceIpMatcher extends Matcher {
    private final CidrMatcher delegate;

    SourceIpMatcher(CidrMatcher delegate) {
      this.delegate = delegate;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getSourceIp());
    }
  }

  static class PathMatcher extends Matcher {
    private final StringMatcher delegate;

    PathMatcher(StringMatcher delegate) {
      this.delegate = delegate;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getPath());
    }
  }

  static class HeaderMatcher extends Matcher {
    private final Matchers.HeaderMatcher delegate;

    HeaderMatcher(Matchers.HeaderMatcher delegate) {
      this.delegate = delegate;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return delegate.matches(args.getHeader());
    }
  }

  static class DestinationPortMatcher extends Matcher {
    private final int port;

    DestinationPortMatcher(int port) {
      this.port = port;
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return port == args.getDestinationPort();
    }
  }

  private static class EvaluateArgs {
    private final Metadata metadata;
    private final ServerCall<?,?> serverCall;
    // ref: io.grpc.okhttp.internal.OkHostnameVerifier, sun.security.x509.GeneralNameInterface
    private static final ImmutableList<Integer> ALT_NAME_TYPES = ImmutableList.of(2, 6, 7);

    private EvaluateArgs(Metadata metadata, ServerCall<?,?> serverCall) {
      this.metadata = metadata;
      this.serverCall = serverCall;
    }

    private String getPath() {
      return serverCall.getMethodDescriptor().getFullMethodName();
    }

    @Nullable
    private String getPrincipalName() {
      SSLSession sslSession = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      try {
        Certificate[] certs = sslSession == null ? null : sslSession.getPeerCertificates();
        if (certs == null || certs.length < 1) {
          return null;
        }
        Collection<List<?>> names = ((X509Certificate)certs[0]).getSubjectAlternativeNames();
        if (names == null) {
          return null;
        }
        for (List<?> name : names) {
          Integer altNameType = (Integer) name.get(0);
          if (ALT_NAME_TYPES.contains(altNameType)) {
            String principalName = (String) name.get(1);
            log.log(Level.FINE, "Found Principal Name: " + principalName);
            return principalName;
          }
        }
      } catch (SSLPeerUnverifiedException | CertificateParsingException ex) {
        return null;
      }
      return null;
    }

    private Metadata getHeader() {
      return metadata;
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
  }

  abstract static class Matcher {
    protected Matcher() {
    }

    abstract boolean matches(EvaluateArgs args);
  }

  /** Matches when any of the matcher matches. */
  static class OrMatcher extends Matcher {
    private final List<? extends Matcher> anyMatch;

    OrMatcher(List<? extends Matcher> matchers) {
      checkNotNull(matchers, "matchers");
      this.anyMatch = Collections.unmodifiableList(matchers);
    }

    static OrMatcher create(Matcher...matchers) {
      return new OrMatcher(Collections.unmodifiableList(Arrays.asList(matchers)));
    }

    @Override
    boolean matches(EvaluateArgs args) {
      for (Matcher m : anyMatch) {
        if (m != null && m.matches(args)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Matches when all of the matchers match. */
  static class AndMatcher extends Matcher {
    private final List<? extends Matcher> allMatch;

    AndMatcher(List<? extends Matcher> matchers) {
      checkNotNull(matchers, "matchers");
      this.allMatch = Collections.unmodifiableList(matchers);
    }

    static AndMatcher create(Matcher...matchers) {
      return new AndMatcher(Collections.unmodifiableList(Arrays.asList(matchers)));
    }

    @Override
    boolean matches(EvaluateArgs args) {
      for (Matcher m : allMatch) {
        if (m == null || !m.matches(args)) {
          return false;
        }
      }
      return true;
    }
  }

  /** Always true matcher.*/
  static class AlwaysTrueMatcher extends Matcher {
    static AlwaysTrueMatcher INSTANCE = new AlwaysTrueMatcher();

    @Override
    boolean matches(EvaluateArgs args) {
      return true;
    }
  }

  /** Negate matcher.*/
  static class InvertMatcher extends Matcher {
    private final Matcher toInvertMatcher;

    InvertMatcher(Matcher matcher) {
      this.toInvertMatcher = checkNotNull(matcher);
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return !toInvertMatcher.matches(args);
    }
  }
}
