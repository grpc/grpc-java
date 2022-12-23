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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.internal.DnsNameResolver.SrvRecord;
import io.grpc.internal.JndiResourceResolverFactory.JndiRecordFetcher;
import io.grpc.internal.JndiResourceResolverFactory.JndiResourceResolver;
import io.grpc.internal.JndiResourceResolverFactory.RecordFetcher;
import java.util.Arrays;
import java.util.List;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link JndiResourceResolverFactory}.
 */
@RunWith(JUnit4.class)
public class JndiResourceResolverTest {

  @Test
  public void normalizeDataRemovesJndiFormattingForTxtRecords() {
    assertEquals("blah", JndiResourceResolver.unquote("blah"));
    assertEquals("", JndiResourceResolver.unquote("\"\""));
    assertEquals("blahblah", JndiResourceResolver.unquote("blah blah"));
    assertEquals("blahfoo blah", JndiResourceResolver.unquote("blah \"foo blah\""));
    assertEquals("blah blah", JndiResourceResolver.unquote("\"blah blah\""));
    assertEquals("blah\"blah", JndiResourceResolver.unquote("\"blah\\\"blah\""));
    assertEquals("blah\\blah", JndiResourceResolver.unquote("\"blah\\\\blah\""));
  }

  @IgnoreJRERequirement
  @Test
  public void jndiResolverWorks() throws Exception {
    Assume.assumeNoException(new JndiResourceResolverFactory().unavailabilityCause());

    RecordFetcher recordFetcher = new JndiRecordFetcher();
    try {
      recordFetcher.getAllRecords("SRV", "dns:///localhost");
    } catch (javax.naming.CommunicationException e) {
      Assume.assumeNoException(e);
    } catch (javax.naming.NameNotFoundException e) {
      Assume.assumeNoException(e);
    }
  }

  @Test
  public void txtRecordLookup() throws Exception {
    RecordFetcher recordFetcher = mock(RecordFetcher.class);
    when(recordFetcher.getAllRecords("TXT", "dns:///service.example.com"))
        .thenReturn(Arrays.asList("foo", "\"bar\""));

    List<String> golden = Arrays.asList("foo", "bar");
    JndiResourceResolver resolver = new JndiResourceResolver(recordFetcher);
    assertThat(resolver.resolveTxt("service.example.com")).isEqualTo(golden);
  }

  @SuppressWarnings("deprecation")
  @Test
  public void srvRecordLookup() throws Exception {
    RecordFetcher recordFetcher = mock(RecordFetcher.class);
    when(recordFetcher.getAllRecords("SRV", "dns:///service.example.com"))
        .thenReturn(Arrays.asList(
            "0 0 314 foo.example.com.", "0 0 42 bar.example.com.", "0 0 1 discard.example.com"));

    List<SrvRecord> golden = Arrays.asList(
        new SrvRecord("foo.example.com.", 314),
        new SrvRecord("bar.example.com.", 42));
    JndiResourceResolver resolver = new JndiResourceResolver(recordFetcher);
    assertThat(resolver.resolveSrv("service.example.com")).isEqualTo(golden);
  }
}
