/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.auth;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auth.Credentials;
import com.google.auth.RequestMetadataCallback;
import com.google.auth.Retryable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.BaseEncoding;
import io.grpc.InternalMayRequireSpecificExecutor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.StatusException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Wraps {@link Credentials} as a {@link io.grpc.CallCredentials}.
 */
final class GoogleAuthLibraryCallCredentials extends io.grpc.CallCredentials
    implements InternalMayRequireSpecificExecutor {
  private static final Logger log
      = Logger.getLogger(GoogleAuthLibraryCallCredentials.class.getName());
  private static final JwtHelper jwtHelper
      = createJwtHelperOrNull(GoogleAuthLibraryCallCredentials.class.getClassLoader());
  private static final Class<? extends Credentials> GOOGLE_CREDENTIALS_CLASS
      = loadGoogleCredentialsClass();
  private static final Class<?> APP_ENGINE_CREDENTIALS_CLASS
      = loadAppEngineCredentials();

  private final boolean requirePrivacy;
  @VisibleForTesting
  final Credentials creds;

  private Metadata lastHeaders;
  private Map<String, List<String>> lastMetadata;

  private final boolean requiresSpecificExecutor;

  public GoogleAuthLibraryCallCredentials(Credentials creds) {
    this(creds, jwtHelper);
  }

  @VisibleForTesting
  GoogleAuthLibraryCallCredentials(Credentials creds, JwtHelper jwtHelper) {
    checkNotNull(creds, "creds");
    boolean requirePrivacy = false;
    if (GOOGLE_CREDENTIALS_CLASS != null) {
      // All GoogleCredentials instances are bearer tokens and should only be used on private
      // channels. This catches all return values from GoogleCredentials.getApplicationDefault().
      // This should be checked before upgrading the Service Account to JWT, as JWT is also a bearer
      // token.
      requirePrivacy = GOOGLE_CREDENTIALS_CLASS.isInstance(creds);
    }
    if (jwtHelper != null) {
      creds = jwtHelper.tryServiceAccountToJwt(creds);
    }
    this.requirePrivacy = requirePrivacy;
    this.creds = creds;

    // Cache the value so we only need to try to load the class once
    if (APP_ENGINE_CREDENTIALS_CLASS == null) {
      requiresSpecificExecutor = false;
    } else {
      requiresSpecificExecutor = APP_ENGINE_CREDENTIALS_CLASS.isInstance(creds);
    }
  }

  @Override
  public void applyRequestMetadata(
      RequestInfo info, Executor appExecutor, final MetadataApplier applier) {
    SecurityLevel security = info.getSecurityLevel();
    if (requirePrivacy && !allowedSecurityLevel(info, SecurityLevel.PRIVACY_AND_INTEGRITY)) {
      applier.fail(Status.UNAUTHENTICATED
          .withDescription("Credentials require channel with PRIVACY_AND_INTEGRITY security level. "
              + "Observed security level: " + security));
      return;
    }

    String authority = checkNotNull(info.getAuthority(), "authority");
    final URI uri;
    try {
      uri = serviceUri(authority, info.getMethodDescriptor());
    } catch (StatusException e) {
      applier.fail(e.getStatus());
      return;
    }
    // Credentials is expected to manage caching internally if the metadata is fetched over
    // the network.
    creds.getRequestMetadata(uri, appExecutor, new RequestMetadataCallback() {
      @Override
      public void onSuccess(Map<String, List<String>> metadata) {
        // Some implementations may pass null metadata.

        // Re-use the headers if getRequestMetadata() returns the same map. It may return a
        // different map based on the provided URI, i.e., for JWT. However, today it does not
        // cache JWT and so we won't bother tring to save its return value based on the URI.
        Metadata headers;
        try {
          synchronized (GoogleAuthLibraryCallCredentials.this) {
            if (lastMetadata == null || lastMetadata != metadata) {
              lastHeaders = toHeaders(metadata);
              lastMetadata = metadata;
            }
            headers = lastHeaders;
          }
        } catch (Throwable t) {
          applier.fail(Status.UNAUTHENTICATED
              .withDescription("Failed to convert credential metadata")
              .withCause(t));
          return;
        }
        applier.apply(headers);
      }

      @Override
      public void onFailure(Throwable e) {
        if (e instanceof Retryable && ((Retryable) e).isRetryable()) {
          // Let the call be retried with UNAVAILABLE.
          applier.fail(Status.UNAVAILABLE
              .withDescription("Credentials failed to obtain metadata")
              .withCause(e));
        } else {
          applier.fail(Status.UNAUTHENTICATED
              .withDescription("Failed computing credential metadata")
              .withCause(e));
        }
      }
    });
  }

  /**
   * Generate a JWT-specific service URI. The URI is simply an identifier with enough information
   * for a service to know that the JWT was intended for it. The URI will commonly be verified with
   * a simple string equality check.
   */
  private static URI serviceUri(String authority, MethodDescriptor<?, ?> method)
      throws StatusException {
    // Always use HTTPS, by definition.
    final String scheme = "https";
    final int defaultPort = 443;
    String path = "/" + method.getServiceName();
    URI uri;
    try {
      uri = new URI(scheme, authority, path, null, null);
    } catch (URISyntaxException e) {
      throw Status.UNAUTHENTICATED.withDescription("Unable to construct service URI for auth")
          .withCause(e).asException();
    }
    // The default port must not be present. Alternative ports should be present.
    if (uri.getPort() == defaultPort) {
      uri = removePort(uri);
    }
    return uri;
  }

  private static URI removePort(URI uri) throws StatusException {
    try {
      return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), -1 /* port */,
          uri.getPath(), uri.getQuery(), uri.getFragment());
    } catch (URISyntaxException e) {
      throw Status.UNAUTHENTICATED.withDescription(
           "Unable to construct service URI after removing port").withCause(e).asException();
    }
  }

  private static Metadata toHeaders(@Nullable Map<String, List<String>> metadata) {
    Metadata headers = new Metadata();
    if (metadata != null) {
      for (String key : metadata.keySet()) {
        if (key.endsWith("-bin")) {
          Metadata.Key<byte[]> headerKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
          for (String value : metadata.get(key)) {
            headers.put(headerKey, BaseEncoding.base64().decode(value));
          }
        } else {
          Metadata.Key<String> headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          for (String value : metadata.get(key)) {
            headers.put(headerKey, value);
          }
        }
      }
    }
    return headers;
  }

  @VisibleForTesting
  @Nullable
  static JwtHelper createJwtHelperOrNull(ClassLoader loader) {
    Class<?> rawServiceAccountClass;
    try {
      // Specify loader so it can be overridden in tests
      rawServiceAccountClass
          = Class.forName("com.google.auth.oauth2.ServiceAccountCredentials", false, loader);
    } catch (ClassNotFoundException ex) {
      return null;
    }
    Exception caughtException;
    try {
      return new JwtHelper(rawServiceAccountClass, loader);
    } catch (ClassNotFoundException ex) {
      caughtException = ex;
    } catch (NoSuchMethodException ex) {
      caughtException = ex;
    }
    if (caughtException != null) {
      // Failure is a bug in this class, but we still choose to gracefully recover
      log.log(Level.WARNING, "Failed to create JWT helper. This is unexpected", caughtException);
    }
    return null;
  }

  @Nullable
  private static Class<? extends Credentials> loadGoogleCredentialsClass() {
    Class<?> rawGoogleCredentialsClass;
    try {
      // Can't use a loader as it disables ProGuard's reference detection and would fail to rename
      // this reference. Unfortunately this will initialize the class.
      rawGoogleCredentialsClass = Class.forName("com.google.auth.oauth2.GoogleCredentials");
    } catch (ClassNotFoundException ex) {
      log.log(Level.FINE, "Failed to load GoogleCredentials", ex);
      return null;
    }
    return rawGoogleCredentialsClass.asSubclass(Credentials.class);
  }

  @Nullable
  private static Class<?> loadAppEngineCredentials() {
    try {
      return Class.forName("com.google.auth.appengine.AppEngineCredentials");
    } catch (ClassNotFoundException ex) {
      log.log(Level.FINE, "AppEngineCredentials not available in classloader", ex);
      return null;
    }
  }

  private static class MethodPair {
    private final Method getter;
    private final Method builderSetter;

    private MethodPair(Method getter, Method builderSetter) {
      this.getter = getter;
      this.builderSetter = builderSetter;
    }

    private void apply(Credentials credentials, Object builder)
        throws InvocationTargetException, IllegalAccessException {
      builderSetter.invoke(builder, getter.invoke(credentials));
    }
  }

  @VisibleForTesting
  static class JwtHelper {
    private final Class<? extends Credentials> serviceAccountClass;
    private final Method newJwtBuilder;
    private final Method build;
    private final Method getScopes;
    private final List<MethodPair> methodPairs;

    public JwtHelper(Class<?> rawServiceAccountClass, ClassLoader loader)
        throws ClassNotFoundException, NoSuchMethodException {
      serviceAccountClass = rawServiceAccountClass.asSubclass(Credentials.class);
      getScopes = serviceAccountClass.getMethod("getScopes");
      Class<? extends Credentials> jwtClass = Class.forName(
          "com.google.auth.oauth2.ServiceAccountJwtAccessCredentials", false, loader)
          .asSubclass(Credentials.class);
      newJwtBuilder = jwtClass.getDeclaredMethod("newBuilder");
      Class<?> builderClass = newJwtBuilder.getReturnType();
      build = builderClass.getMethod("build");

      methodPairs = new ArrayList<>();

      {
        Method getter = serviceAccountClass.getMethod("getClientId");
        Method setter = builderClass.getMethod("setClientId", getter.getReturnType());
        methodPairs.add(new MethodPair(getter, setter));
      }
      {
        Method getter = serviceAccountClass.getMethod("getClientEmail");
        Method setter = builderClass.getMethod("setClientEmail", getter.getReturnType());
        methodPairs.add(new MethodPair(getter, setter));
      }
      {
        Method getter = serviceAccountClass.getMethod("getPrivateKey");
        Method setter = builderClass.getMethod("setPrivateKey", getter.getReturnType());
        methodPairs.add(new MethodPair(getter, setter));
      }
      {
        Method getter = serviceAccountClass.getMethod("getPrivateKeyId");
        Method setter = builderClass.getMethod("setPrivateKeyId", getter.getReturnType());
        methodPairs.add(new MethodPair(getter, setter));
      }
      {
        Method getter = serviceAccountClass.getMethod("getQuotaProjectId");
        Method setter = builderClass.getMethod("setQuotaProjectId", getter.getReturnType());
        methodPairs.add(new MethodPair(getter, setter));
      }
    }

    /**
     * This method tries to convert a {@link Credentials} object to a
     * ServiceAccountJwtAccessCredentials.  The original credentials will be returned if:
     * <ul>
     *   <li> The Credentials is not a ServiceAccountCredentials</li>
     *   <li> The ServiceAccountCredentials has scopes</li>
     *   <li> Something unexpected happens </li>
     * </ul>
     * @param creds the Credentials to convert
     * @return either the original Credentials or a fully formed ServiceAccountJwtAccessCredentials.
     */
    public Credentials tryServiceAccountToJwt(Credentials creds) {
      if (!serviceAccountClass.isInstance(creds)) {
        return creds;
      }
      Exception caughtException;
      try {
        creds = serviceAccountClass.cast(creds);
        Collection<?> scopes = (Collection<?>) getScopes.invoke(creds);
        if (scopes.size() != 0) {
          // Leave as-is, since the scopes may limit access within the service.
          return creds;
        }
        // Create the JWT Credentials Builder
        Object builder = newJwtBuilder.invoke(null);

        // Get things from the credentials, and set them on the builder.
        for (MethodPair pair : this.methodPairs) {
          pair.apply(creds, builder);
        }

        // Call builder.build()
        return (Credentials) build.invoke(builder);
      } catch (IllegalAccessException ex) {
        caughtException = ex;
      } catch (InvocationTargetException ex) {
        caughtException = ex;
      }
      if (caughtException != null) {
        // Failure is a bug in this class, but we still choose to gracefully recover
        log.log(
            Level.WARNING,
            "Failed converting service account credential to JWT. This is unexpected",
            caughtException);
      }
      return creds;
    }
  }

  /**
   * This method is to support the hack for AppEngineCredentials which need to run on a
   * specific thread.
   * @return Whether a specific executor is needed or if any executor can be used
   */
  @Override
  public boolean isSpecificExecutorRequired() {
    return requiresSpecificExecutor;
  }

}
