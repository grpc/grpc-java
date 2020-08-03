/*
 * Copyright 2018 The gRPC Authors
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

import android.annotation.SuppressLint;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import io.grpc.internal.DnsNameResolver.ResourceResolver;
import io.grpc.internal.DnsNameResolver.SrvRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/**
 * {@link JndiResourceResolverFactory} resolves additional records for the DnsNameResolver.
 */
final class JndiResourceResolverFactory implements DnsNameResolver.ResourceResolverFactory {

  @Nullable
  private static final Throwable JNDI_UNAVAILABILITY_CAUSE = initJndi();

  // @UsedReflectively
  public JndiResourceResolverFactory() {}

  /**
   * Returns whether the JNDI DNS resolver is available.  This is accomplished by looking up a
   * particular class.  It is believed to be the default (only?) DNS resolver that will actually be
   * used.  It is provided by the OpenJDK, but unlikely Android.  Actual resolution will be done by
   * using a service provider when a hostname query is present, so the {@code DnsContextFactory}
   * may not actually be used to perform the query.  This is believed to be "okay."
   */
  @Nullable
  private static Throwable initJndi() {
    try {
      Class.forName("javax.naming.directory.InitialDirContext");
      Class.forName("com.sun.jndi.dns.DnsContextFactory");
    } catch (ClassNotFoundException e) {
      return e;
    } catch (RuntimeException e) {
      return e;
    } catch (Error e) {
      return e;
    }
    return null;
  }

  @Nullable
  @Override
  public ResourceResolver newResourceResolver() {
    if (unavailabilityCause() != null) {
      return null;
    }
    return new JndiResourceResolver(new JndiRecordFetcher());
  }

  @Nullable
  @Override
  public Throwable unavailabilityCause() {
    return JNDI_UNAVAILABILITY_CAUSE;
  }

  @VisibleForTesting
  interface RecordFetcher {
    List<String> getAllRecords(String recordType, String name) throws NamingException;
  }

  @VisibleForTesting
  static final class JndiResourceResolver implements DnsNameResolver.ResourceResolver {
    private static final Logger logger =
        Logger.getLogger(JndiResourceResolver.class.getName());

    private static final Pattern whitespace = Pattern.compile("\\s+");

    private final RecordFetcher recordFetcher;

    public JndiResourceResolver(RecordFetcher recordFetcher) {
      this.recordFetcher = recordFetcher;
    }

    @Override
    public List<String> resolveTxt(String serviceConfigHostname) throws NamingException {
      if (logger.isLoggable(Level.FINER)) {
        logger.log(
            Level.FINER, "About to query TXT records for {0}", new Object[]{serviceConfigHostname});
      }
      List<String> serviceConfigRawTxtRecords =
          recordFetcher.getAllRecords("TXT", "dns:///" + serviceConfigHostname);
      if (logger.isLoggable(Level.FINER)) {
        logger.log(
            Level.FINER, "Found {0} TXT records", new Object[]{serviceConfigRawTxtRecords.size()});
      }
      List<String> serviceConfigTxtRecords =
          new ArrayList<>(serviceConfigRawTxtRecords.size());
      for (String serviceConfigRawTxtRecord : serviceConfigRawTxtRecords) {
        serviceConfigTxtRecords.add(unquote(serviceConfigRawTxtRecord));
      }
      return Collections.unmodifiableList(serviceConfigTxtRecords);
    }

    @Override
    public List<SrvRecord> resolveSrv(String host) throws Exception {
      if (logger.isLoggable(Level.FINER)) {
        logger.log(
            Level.FINER, "About to query SRV records for {0}", new Object[]{host});
      }
      List<String> rawSrvRecords =
          recordFetcher.getAllRecords("SRV", "dns:///" + host);
      if (logger.isLoggable(Level.FINER)) {
        logger.log(
            Level.FINER, "Found {0} SRV records", new Object[]{rawSrvRecords.size()});
      }
      List<SrvRecord> srvRecords = new ArrayList<>(rawSrvRecords.size());
      Exception first = null;
      Level level = Level.WARNING;
      for (String rawSrv : rawSrvRecords) {
        try {
          String[] parts = whitespace.split(rawSrv);
          Verify.verify(parts.length == 4, "Bad SRV Record: %s", rawSrv);
          // SRV requires the host name to be absolute
          if (!parts[3].endsWith(".")) {
            throw new RuntimeException("Returned SRV host does not end in period: " + parts[3]);
          }
          srvRecords.add(new SrvRecord(parts[3], Integer.parseInt(parts[2])));
        } catch (RuntimeException e) {
          logger.log(level, "Failed to construct SRV record " + rawSrv, e);
          if (first == null) {
            first = e;
            level = Level.FINE;
          }
        }
      }
      if (srvRecords.isEmpty() && first != null) {
        throw first;
      }
      return Collections.unmodifiableList(srvRecords);
    }

    /**
     * Undo the quoting done in {@link com.sun.jndi.dns.ResourceRecord#decodeTxt}.
     */
    @VisibleForTesting
    static String unquote(String txtRecord) {
      StringBuilder sb = new StringBuilder(txtRecord.length());
      boolean inquote = false;
      for (int i = 0; i < txtRecord.length(); i++) {
        char c = txtRecord.charAt(i);
        if (!inquote) {
          if (c == ' ') {
            continue;
          } else if (c == '"') {
            inquote = true;
            continue;
          }
        } else {
          if (c == '"') {
            inquote = false;
            continue;
          } else if (c == '\\') {
            c = txtRecord.charAt(++i);
            assert c == '"' || c == '\\';
          }
        }
        sb.append(c);
      }
      return sb.toString();
    }
  }

  @VisibleForTesting
  @IgnoreJRERequirement
  // Hashtable is required. https://github.com/google/error-prone/issues/1766
  @SuppressWarnings("JdkObsolete")
  // javax.naming.* is only loaded reflectively and is never loaded for Android
  // The lint issue id is supposed to be "InvalidPackage" but it doesn't work, don't know why.
  // Use "all" as the lint issue id to suppress all types of lint error.
  @SuppressLint("all")
  static final class JndiRecordFetcher implements RecordFetcher {
    @Override
    public List<String> getAllRecords(String recordType, String name) throws NamingException {
      checkAvailable();
      String[] rrType = new String[]{recordType};
      List<String> records = new ArrayList<>();

      Hashtable<String, String> env = new Hashtable<>();
      env.put("com.sun.jndi.ldap.connect.timeout", "5000");
      env.put("com.sun.jndi.ldap.read.timeout", "5000");
      DirContext dirContext = new InitialDirContext(env);

      try {
        javax.naming.directory.Attributes attrs = dirContext.getAttributes(name, rrType);
        NamingEnumeration<? extends Attribute> rrGroups = attrs.getAll();

        try {
          while (rrGroups.hasMore()) {
            Attribute rrEntry = rrGroups.next();
            assert Arrays.asList(rrType).contains(rrEntry.getID());
            NamingEnumeration<?> rrValues = rrEntry.getAll();
            try {
              while (rrValues.hasMore()) {
                records.add(String.valueOf(rrValues.next()));
              }
            } catch (NamingException ne) {
              closeThenThrow(rrValues, ne);
            }
            rrValues.close();
          }
        } catch (NamingException ne) {
          closeThenThrow(rrGroups, ne);
        }
        rrGroups.close();
      } catch (NamingException ne) {
        closeThenThrow(dirContext, ne);
      }
      dirContext.close();

      return records;
    }

    private static void closeThenThrow(NamingEnumeration<?> namingEnumeration, NamingException e)
        throws NamingException {
      try {
        namingEnumeration.close();
      } catch (NamingException ignored) {
        // ignore
      }
      throw e;
    }

    private static void closeThenThrow(DirContext ctx, NamingException e) throws NamingException {
      try {
        ctx.close();
      } catch (NamingException ignored) {
        // ignore
      }
      throw e;
    }

    private static void checkAvailable() {
      if (JNDI_UNAVAILABILITY_CAUSE != null) {
        throw new UnsupportedOperationException(
            "JNDI is not currently available", JNDI_UNAVAILABILITY_CAUSE);
      }
    }
  }
}
