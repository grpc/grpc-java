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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.ProxiedSocketAddress;
import io.grpc.ProxyDetector;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A DNS-based {@link NameResolver}.
 *
 * <p>Each {@code A} or {@code AAAA} record emits an {@link EquivalentAddressGroup} in the list
 * passed to {@link NameResolver.Listener2#onResult2(ResolutionResult)}.
 *
 * @see DnsNameResolverProvider
 */
public class DnsNameResolver extends NameResolver {

  private static final Logger logger = Logger.getLogger(DnsNameResolver.class.getName());

  private static final String SERVICE_CONFIG_CHOICE_CLIENT_LANGUAGE_KEY = "clientLanguage";
  private static final String SERVICE_CONFIG_CHOICE_PERCENTAGE_KEY = "percentage";
  private static final String SERVICE_CONFIG_CHOICE_CLIENT_HOSTNAME_KEY = "clientHostname";
  private static final String SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY = "serviceConfig";

  // From https://github.com/grpc/proposal/blob/master/A2-service-configs-in-dns.md
  static final String SERVICE_CONFIG_PREFIX = "grpc_config=";
  private static final Set<String> SERVICE_CONFIG_CHOICE_KEYS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  SERVICE_CONFIG_CHOICE_CLIENT_LANGUAGE_KEY,
                  SERVICE_CONFIG_CHOICE_PERCENTAGE_KEY,
                  SERVICE_CONFIG_CHOICE_CLIENT_HOSTNAME_KEY,
                  SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY)));

  // From https://github.com/grpc/proposal/blob/master/A2-service-configs-in-dns.md
  private static final String SERVICE_CONFIG_NAME_PREFIX = "_grpc_config.";

  private static final String JNDI_PROPERTY =
      System.getProperty("io.grpc.internal.DnsNameResolverProvider.enable_jndi", "true");
  private static final String JNDI_LOCALHOST_PROPERTY =
      System.getProperty("io.grpc.internal.DnsNameResolverProvider.enable_jndi_localhost", "false");
  private static final String JNDI_TXT_PROPERTY =
      System.getProperty("io.grpc.internal.DnsNameResolverProvider.enable_service_config", "false");

  /**
   * Java networking system properties name for caching DNS result.
   *
   * <p>Default value is -1 (cache forever) if security manager is installed. If security manager is
   * not installed, the ttl value is {@code null} which falls back to {@link
   * #DEFAULT_NETWORK_CACHE_TTL_SECONDS gRPC default value}.
   *
   * <p>For android, gRPC doesn't attempt to cache; this property value will be ignored.
   */
  @VisibleForTesting
  static final String NETWORKADDRESS_CACHE_TTL_PROPERTY = "networkaddress.cache.ttl";
  /** Default DNS cache duration if network cache ttl value is not specified ({@code null}). */
  @VisibleForTesting
  static final long DEFAULT_NETWORK_CACHE_TTL_SECONDS = 30;

  @VisibleForTesting
  static boolean enableJndi = Boolean.parseBoolean(JNDI_PROPERTY);
  @VisibleForTesting
  static boolean enableJndiLocalhost = Boolean.parseBoolean(JNDI_LOCALHOST_PROPERTY);
  @VisibleForTesting
  protected static boolean enableTxt = Boolean.parseBoolean(JNDI_TXT_PROPERTY);

  private static final ResourceResolverFactory resourceResolverFactory =
      getResourceResolverFactory(DnsNameResolver.class.getClassLoader());

  @VisibleForTesting
  final ProxyDetector proxyDetector;

  /** Access through {@link #getLocalHostname}. */
  private static String localHostname;

  private final Random random = new Random();

  protected volatile AddressResolver addressResolver = JdkAddressResolver.INSTANCE;
  private final AtomicReference<ResourceResolver> resourceResolver = new AtomicReference<>();

  private final String authority;
  private final String host;
  private final int port;

  private final ObjectPool<Executor> executorPool;
  private final long cacheTtlNanos;
  private final SynchronizationContext syncContext;
  private final ServiceConfigParser serviceConfigParser;

  // Following fields must be accessed from syncContext
  private final Stopwatch stopwatch;
  protected boolean resolved;
  private boolean shutdown;
  private Executor executor;

  private boolean resolving;

  // The field must be accessed from syncContext, although the methods on an Listener2 can be called
  // from any thread.
  private NameResolver.Listener2 listener;

  protected DnsNameResolver(
      @Nullable String nsAuthority,
      String name,
      Args args,
      Resource<Executor> executorResource,
      Stopwatch stopwatch,
      boolean isAndroid) {
    checkNotNull(args, "args");
    // TODO: if a DNS server is provided as nsAuthority, use it.
    // https://www.captechconsulting.com/blogs/accessing-the-dusty-corners-of-dns-with-java

    // Must prepend a "//" to the name when constructing a URI, otherwise it will be treated as an
    // opaque URI, thus the authority and host of the resulted URI would be null.
    URI nameUri = URI.create("//" + checkNotNull(name, "name"));
    Preconditions.checkArgument(nameUri.getHost() != null, "Invalid DNS name: %s", name);
    authority = Preconditions.checkNotNull(nameUri.getAuthority(),
        "nameUri (%s) doesn't have an authority", nameUri);
    host = nameUri.getHost();
    if (nameUri.getPort() == -1) {
      port = args.getDefaultPort();
    } else {
      port = nameUri.getPort();
    }
    this.proxyDetector = checkNotNull(args.getProxyDetector(), "proxyDetector");
    Executor offloadExecutor = args.getOffloadExecutor();
    if (offloadExecutor != null) {
      this.executorPool = new FixedObjectPool<>(offloadExecutor);
    } else {
      this.executorPool = SharedResourcePool.forResource(executorResource);
    }
    this.cacheTtlNanos = getNetworkAddressCacheTtlNanos(isAndroid);
    this.stopwatch = checkNotNull(stopwatch, "stopwatch");
    this.syncContext = checkNotNull(args.getSynchronizationContext(), "syncContext");
    this.serviceConfigParser = checkNotNull(args.getServiceConfigParser(), "serviceConfigParser");
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @VisibleForTesting
  protected String getHost() {
    return host;
  }

  @Override
  public void start(Listener2 listener) {
    Preconditions.checkState(this.listener == null, "already started");
    executor = executorPool.getObject();
    this.listener = checkNotNull(listener, "listener");
    resolve();
  }

  @Override
  public void refresh() {
    Preconditions.checkState(listener != null, "not started");
    resolve();
  }

  private List<EquivalentAddressGroup> resolveAddresses() {
    List<? extends InetAddress> addresses;
    Exception addressesException = null;
    try {
      addresses = addressResolver.resolveAddress(host);
    } catch (Exception e) {
      addressesException = e;
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    } finally {
      if (addressesException != null) {
        logger.log(Level.FINE, "Address resolution failure", addressesException);
      }
    }
    // Each address forms an EAG
    List<EquivalentAddressGroup> servers = new ArrayList<>(addresses.size());
    for (InetAddress inetAddr : addresses) {
      servers.add(new EquivalentAddressGroup(new InetSocketAddress(inetAddr, port)));
    }
    return Collections.unmodifiableList(servers);
  }

  @Nullable
  private ConfigOrError resolveServiceConfig() {
    List<String> txtRecords = Collections.emptyList();
    ResourceResolver resourceResolver = getResourceResolver();
    if (resourceResolver != null) {
      try {
        txtRecords = resourceResolver.resolveTxt(SERVICE_CONFIG_NAME_PREFIX + host);
      } catch (Exception e) {
        logger.log(Level.FINE, "ServiceConfig resolution failure", e);
      }
    }
    if (!txtRecords.isEmpty()) {
      ConfigOrError rawServiceConfig = parseServiceConfig(txtRecords, random, getLocalHostname());
      if (rawServiceConfig != null) {
        if (rawServiceConfig.getError() != null) {
          return ConfigOrError.fromError(rawServiceConfig.getError());
        }

        @SuppressWarnings("unchecked")
        Map<String, ?> verifiedRawServiceConfig = (Map<String, ?>) rawServiceConfig.getConfig();
        return serviceConfigParser.parseServiceConfig(verifiedRawServiceConfig);
      }
    } else {
      logger.log(Level.FINE, "No TXT records found for {0}", new Object[]{host});
    }
    return null;
  }

  @Nullable
  private EquivalentAddressGroup detectProxy() throws IOException {
    InetSocketAddress destination =
        InetSocketAddress.createUnresolved(host, port);
    ProxiedSocketAddress proxiedAddr = proxyDetector.proxyFor(destination);
    if (proxiedAddr != null) {
      return new EquivalentAddressGroup(proxiedAddr);
    }
    return null;
  }

  /**
   * Main logic of name resolution.
   */
  protected InternalResolutionResult doResolve(boolean forceTxt) {
    InternalResolutionResult result = new InternalResolutionResult();
    try {
      result.addresses = resolveAddresses();
    } catch (Exception e) {
      if (!forceTxt) {
        result.error =
            Status.UNAVAILABLE.withDescription("Unable to resolve host " + host).withCause(e);
        return result;
      }
    }
    if (enableTxt) {
      result.config = resolveServiceConfig();
    }
    return result;
  }

  private final class Resolve implements Runnable {
    private final Listener2 savedListener;

    Resolve(Listener2 savedListener) {
      this.savedListener = checkNotNull(savedListener, "savedListener");
    }

    @Override
    public void run() {
      if (logger.isLoggable(Level.FINER)) {
        logger.finer("Attempting DNS resolution of " + host);
      }
      InternalResolutionResult result = null;
      try {
        EquivalentAddressGroup proxiedAddr = detectProxy();
        ResolutionResult.Builder resolutionResultBuilder = ResolutionResult.newBuilder();
        if (proxiedAddr != null) {
          if (logger.isLoggable(Level.FINER)) {
            logger.finer("Using proxy address " + proxiedAddr);
          }
          resolutionResultBuilder.setAddressesOrError(
              StatusOr.fromValue(Collections.singletonList(proxiedAddr)));
        } else {
          result = doResolve(false);
          if (result.error != null) {
            InternalResolutionResult finalResult = result;
            syncContext.execute(() ->
                savedListener.onResult2(ResolutionResult.newBuilder()
                    .setAddressesOrError(StatusOr.fromStatus(finalResult.error))
                    .build()));
            return;
          }
          if (result.addresses != null) {
            resolutionResultBuilder.setAddressesOrError(StatusOr.fromValue(result.addresses));
          }
          if (result.config != null) {
            resolutionResultBuilder.setServiceConfig(result.config);
          }
          if (result.attributes != null) {
            resolutionResultBuilder.setAttributes(result.attributes);
          }
        }
        syncContext.execute(() -> {
          savedListener.onResult2(resolutionResultBuilder.build());
        });
      } catch (IOException e) {
        syncContext.execute(() ->
            savedListener.onResult2(ResolutionResult.newBuilder()
                .setAddressesOrError(
                    StatusOr.fromStatus(
                        Status.UNAVAILABLE.withDescription(
                            "Unable to resolve host " + host).withCause(e))).build()));
      } finally {
        final boolean succeed = result != null && result.error == null;
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (succeed) {
              resolved = true;
              if (cacheTtlNanos > 0) {
                stopwatch.reset().start();
              }
            }
            resolving = false;
          }
        });
      }
    }
  }

  @Nullable
  static ConfigOrError parseServiceConfig(
      List<String> rawTxtRecords, Random random, String localHostname) {
    List<Map<String, ?>> possibleServiceConfigChoices;
    try {
      possibleServiceConfigChoices = parseTxtResults(rawTxtRecords);
    } catch (IOException | RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNKNOWN.withDescription("failed to parse TXT records").withCause(e));
    }
    Map<String, ?> possibleServiceConfig = null;
    for (Map<String, ?> possibleServiceConfigChoice : possibleServiceConfigChoices) {
      try {
        possibleServiceConfig =
            maybeChooseServiceConfig(possibleServiceConfigChoice, random, localHostname);
      } catch (RuntimeException e) {
        return ConfigOrError.fromError(
            Status.UNKNOWN.withDescription("failed to pick service config choice").withCause(e));
      }
      if (possibleServiceConfig != null) {
        break;
      }
    }
    if (possibleServiceConfig == null) {
      return null;
    }
    return ConfigOrError.fromConfig(possibleServiceConfig);
  }

  private void resolve() {
    if (resolving || shutdown || !cacheRefreshRequired()) {
      return;
    }
    resolving = true;
    executor.execute(new Resolve(listener));
  }

  private boolean cacheRefreshRequired() {
    return !resolved
        || cacheTtlNanos == 0
        || (cacheTtlNanos > 0 && stopwatch.elapsed(TimeUnit.NANOSECONDS) > cacheTtlNanos);
  }

  @Override
  public void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    if (executor != null) {
      executor = executorPool.returnObject(executor);
    }
  }

  final int getPort() {
    return port;
  }

  /**
   * Parse TXT service config records as JSON.
   *
   * @throws IOException if one of the txt records contains improperly formatted JSON.
   */
  @VisibleForTesting
  static List<Map<String, ?>> parseTxtResults(List<String> txtRecords) throws IOException {
    List<Map<String, ?>> possibleServiceConfigChoices = new ArrayList<>();
    for (String txtRecord : txtRecords) {
      if (!txtRecord.startsWith(SERVICE_CONFIG_PREFIX)) {
        logger.log(Level.FINE, "Ignoring non service config {0}", new Object[]{txtRecord});
        continue;
      }
      Object rawChoices = JsonParser.parse(txtRecord.substring(SERVICE_CONFIG_PREFIX.length()));
      if (!(rawChoices instanceof List)) {
        throw new ClassCastException("wrong type " + rawChoices);
      }
      List<?> listChoices = (List<?>) rawChoices;
      possibleServiceConfigChoices.addAll(JsonUtil.checkObjectList(listChoices));
    }
    return possibleServiceConfigChoices;
  }

  @Nullable
  private static final Double getPercentageFromChoice(Map<String, ?> serviceConfigChoice) {
    return JsonUtil.getNumberAsDouble(serviceConfigChoice, SERVICE_CONFIG_CHOICE_PERCENTAGE_KEY);
  }

  @Nullable
  private static final List<String> getClientLanguagesFromChoice(
      Map<String, ?> serviceConfigChoice) {
    return JsonUtil.getListOfStrings(
        serviceConfigChoice, SERVICE_CONFIG_CHOICE_CLIENT_LANGUAGE_KEY);
  }

  @Nullable
  private static final List<String> getHostnamesFromChoice(Map<String, ?> serviceConfigChoice) {
    return JsonUtil.getListOfStrings(
        serviceConfigChoice, SERVICE_CONFIG_CHOICE_CLIENT_HOSTNAME_KEY);
  }

  /**
   * Returns value of network address cache ttl property if not Android environment. For android,
   * DnsNameResolver does not cache the dns lookup result.
   */
  private static long getNetworkAddressCacheTtlNanos(boolean isAndroid) {
    if (isAndroid) {
      // on Android, ignore dns cache.
      return 0;
    }

    String cacheTtlPropertyValue = System.getProperty(NETWORKADDRESS_CACHE_TTL_PROPERTY);
    long cacheTtl = DEFAULT_NETWORK_CACHE_TTL_SECONDS;
    if (cacheTtlPropertyValue != null) {
      try {
        cacheTtl = Long.parseLong(cacheTtlPropertyValue);
      } catch (NumberFormatException e) {
        logger.log(
            Level.WARNING,
            "Property({0}) valid is not valid number format({1}), fall back to default({2})",
            new Object[] {NETWORKADDRESS_CACHE_TTL_PROPERTY, cacheTtlPropertyValue, cacheTtl});
      }
    }
    return cacheTtl > 0 ? TimeUnit.SECONDS.toNanos(cacheTtl) : cacheTtl;
  }

  /**
   * Determines if a given Service Config choice applies, and if so, returns it.
   *
   * @see <a href="https://github.com/grpc/proposal/blob/master/A2-service-configs-in-dns.md">
   *   Service Config in DNS</a>
   * @param choice The service config choice.
   * @return The service config object or {@code null} if this choice does not apply.
   */
  @Nullable
  @VisibleForTesting
  static Map<String, ?> maybeChooseServiceConfig(
      Map<String, ?> choice, Random random, String hostname) {
    for (Map.Entry<String, ?> entry : choice.entrySet()) {
      Verify.verify(SERVICE_CONFIG_CHOICE_KEYS.contains(entry.getKey()), "Bad key: %s", entry);
    }

    List<String> clientLanguages = getClientLanguagesFromChoice(choice);
    if (clientLanguages != null && !clientLanguages.isEmpty()) {
      boolean javaPresent = false;
      for (String lang : clientLanguages) {
        if ("java".equalsIgnoreCase(lang)) {
          javaPresent = true;
          break;
        }
      }
      if (!javaPresent) {
        return null;
      }
    }
    Double percentage = getPercentageFromChoice(choice);
    if (percentage != null) {
      int pct = percentage.intValue();
      Verify.verify(pct >= 0 && pct <= 100, "Bad percentage: %s", percentage);
      if (random.nextInt(100) >= pct) {
        return null;
      }
    }
    List<String> clientHostnames = getHostnamesFromChoice(choice);
    if (clientHostnames != null && !clientHostnames.isEmpty()) {
      boolean hostnamePresent = false;
      for (String clientHostname : clientHostnames) {
        if (clientHostname.equals(hostname)) {
          hostnamePresent = true;
          break;
        }
      }
      if (!hostnamePresent) {
        return null;
      }
    }
    Map<String, ?> sc =
        JsonUtil.getObject(choice, SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY);
    if (sc == null) {
      throw new VerifyException(String.format(
          "key '%s' missing in '%s'", choice, SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY));
    }
    return sc;
  }

  /**
   * Used as a DNS-based name resolver's internal representation of resolution result.
   */
  protected static final class InternalResolutionResult {
    private Status error;
    private List<EquivalentAddressGroup> addresses;
    private ConfigOrError config;
    public Attributes attributes;

    private InternalResolutionResult() {}
  }

  /**
   * Describes a parsed SRV record.
   */
  @VisibleForTesting
  public static final class SrvRecord {
    public final String host;
    public final int port;

    public SrvRecord(String host, int port) {
      this.host = host;
      this.port = port;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(host, port);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      SrvRecord that = (SrvRecord) obj;
      return port == that.port && host.equals(that.host);
    }

    @Override
    public String toString() {
      return
          MoreObjects.toStringHelper(this)
              .add("host", host)
              .add("port", port)
              .toString();
    }
  }

  @VisibleForTesting
  protected void setAddressResolver(AddressResolver addressResolver) {
    this.addressResolver = addressResolver;
  }

  @VisibleForTesting
  protected void setResourceResolver(ResourceResolver resourceResolver) {
    this.resourceResolver.set(resourceResolver);
  }

  /**
   * {@link ResourceResolverFactory} is a factory for making resource resolvers.  It supports
   * optionally checking if the factory is available.
   */
  interface ResourceResolverFactory {

    /**
     * Creates a new resource resolver.  The return value is {@code null} iff
     * {@link #unavailabilityCause()} is not null;
     */
    @Nullable ResourceResolver newResourceResolver();

    /**
     * Returns the reason why the resource resolver cannot be created.  The return value is
     * {@code null} if {@link #newResourceResolver()} is suitable for use.
     */
    @Nullable Throwable unavailabilityCause();
  }

  /**
   * AddressResolver resolves a hostname into a list of addresses.
   */
  @VisibleForTesting
  public interface AddressResolver {
    List<InetAddress> resolveAddress(String host) throws Exception;
  }

  private enum JdkAddressResolver implements AddressResolver {
    INSTANCE;

    @Override
    public List<InetAddress> resolveAddress(String host) throws UnknownHostException {
      return Collections.unmodifiableList(Arrays.asList(InetAddress.getAllByName(host)));
    }
  }

  /**
   * {@link ResourceResolver} is a Dns ResourceRecord resolver.
   */
  @VisibleForTesting
  public interface ResourceResolver {
    List<String> resolveTxt(String host) throws Exception;

    List<SrvRecord> resolveSrv(String host) throws Exception;
  }

  @Nullable
  protected ResourceResolver getResourceResolver() {
    if (!shouldUseJndi(enableJndi, enableJndiLocalhost, host)) {
      return null;
    }
    ResourceResolver rr;
    if ((rr = resourceResolver.get()) == null) {
      if (resourceResolverFactory != null) {
        assert resourceResolverFactory.unavailabilityCause() == null;
        rr = resourceResolverFactory.newResourceResolver();
      }
    }
    return rr;
  }

  @Nullable
  @VisibleForTesting
  static ResourceResolverFactory getResourceResolverFactory(ClassLoader loader) {
    Class<? extends ResourceResolverFactory> jndiClazz;
    try {
      jndiClazz =
          Class.forName("io.grpc.internal.JndiResourceResolverFactory", true, loader)
              .asSubclass(ResourceResolverFactory.class);
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE, "Unable to find JndiResourceResolverFactory, skipping.", e);
      return null;
    } catch (ClassCastException e) {
      // This can happen if JndiResourceResolverFactory was removed by something like Proguard
      // combined with a broken ClassLoader that prefers classes from the child over the parent
      // while also not properly filtering dependencies in the parent that should be hidden. If the
      // class loader prefers loading from the parent then ResourceresolverFactory would have also
      // been loaded from the parent. If the class loader filtered deps, then
      // JndiResourceResolverFactory wouldn't have been found.
      logger.log(Level.FINE, "Unable to cast JndiResourceResolverFactory, skipping.", e);
      return null;
    }
    Constructor<? extends ResourceResolverFactory> jndiCtor;
    try {
      jndiCtor = jndiClazz.getConstructor();
    } catch (Exception e) {
      logger.log(Level.FINE, "Can't find JndiResourceResolverFactory ctor, skipping.", e);
      return null;
    }
    ResourceResolverFactory rrf;
    try {
      rrf = jndiCtor.newInstance();
    } catch (Exception e) {
      logger.log(Level.FINE, "Can't construct JndiResourceResolverFactory, skipping.", e);
      return null;
    }
    if (rrf.unavailabilityCause() != null) {
      logger.log(
          Level.FINE,
          "JndiResourceResolverFactory not available, skipping.",
          rrf.unavailabilityCause());
      return null;
    }
    return rrf;
  }

  private static String getLocalHostname() {
    if (localHostname == null) {
      try {
        localHostname = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        throw new RuntimeException(e);
      }
    }
    return localHostname;
  }

  @VisibleForTesting
  protected static boolean shouldUseJndi(
      boolean jndiEnabled, boolean jndiLocalhostEnabled, String target) {
    if (!jndiEnabled) {
      return false;
    }
    if ("localhost".equalsIgnoreCase(target)) {
      return jndiLocalhostEnabled;
    }
    // Check if this name looks like IPv6
    if (target.contains(":")) {
      return false;
    }
    // Check if this might be IPv4.  Such addresses have no alphabetic characters.  This also
    // checks the target is empty.
    boolean alldigits = true;
    for (int i = 0; i < target.length(); i++) {
      char c = target.charAt(i);
      if (c != '.') {
        alldigits &= (c >= '0' && c <= '9');
      }
    }
    return !alldigits;
  }
}
