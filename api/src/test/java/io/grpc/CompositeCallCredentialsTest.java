/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CompositeCallCredentialsTest {
  private static final Metadata.Key<String> METADATA_KEY =
      Metadata.Key.of("key", Metadata.ASCII_STRING_MARSHALLER);

  private CompositeCallCredentials callCredentials;
  private CallCredentials.RequestInfo requestInfo;
  private Executor appExecutor;
  private FakeMetadataApplier applier = new FakeMetadataApplier();

  @Test
  public void applyRequestMetadata_firstFails() {
    Status failure = Status.UNAVAILABLE.withDescription("expected");
    callCredentials = new CompositeCallCredentials(
        new FakeCallCredentials(failure),
        new FakeCallCredentials(createMetadata(METADATA_KEY, "value2")));
    callCredentials.applyRequestMetadata(requestInfo, appExecutor, applier);
    assertThat(applier.status).isSameInstanceAs(failure);
  }

  @Test
  public void applyRequestMetadata_secondFails() {
    Status failure = Status.UNAVAILABLE.withDescription("expected");
    callCredentials = new CompositeCallCredentials(
        new FakeCallCredentials(createMetadata(METADATA_KEY, "value1")),
        new FakeCallCredentials(failure));
    callCredentials.applyRequestMetadata(requestInfo, appExecutor, applier);
    assertThat(applier.status).isSameInstanceAs(failure);
  }

  @Test
  public void applyRequestMetadata_bothSucceed() {
    callCredentials = new CompositeCallCredentials(
        new FakeCallCredentials(createMetadata(METADATA_KEY, "value1")),
        new FakeCallCredentials(createMetadata(METADATA_KEY, "value2")));
    callCredentials.applyRequestMetadata(requestInfo, appExecutor, applier);
    assertThat(applier.headers).isNotNull();
    assertThat(applier.headers.getAll(METADATA_KEY)).containsExactly("value1", "value2");
  }

  private static Metadata createMetadata(Metadata.Key<String> key, String value) {
    Metadata metadata = new Metadata();
    metadata.put(key, value);
    return metadata;
  }

  private static class FakeMetadataApplier extends CallCredentials.MetadataApplier {
    private Metadata headers;
    private Status status;

    @Override public void apply(Metadata headers) {
      assertThat(this.headers).isNull();
      assertThat(this.status).isNull();
      this.headers = headers;
    }

    @Override public void fail(Status status) {
      assertThat(this.headers).isNull();
      assertThat(this.status).isNull();
      this.status = status;
    }
  }

  private static class FakeCallCredentials extends CallCredentials {
    private final Metadata headers;
    private final Status status;

    public FakeCallCredentials(Metadata headers) {
      this.headers = headers;
      this.status = null;
    }

    public FakeCallCredentials(Status status) {
      this.headers = null;
      this.status = status;
    }

    @Override public void applyRequestMetadata(
        CallCredentials.RequestInfo requestInfo, Executor appExecutor,
        CallCredentials.MetadataApplier applier) {
      if (headers != null) {
        applier.apply(headers);
      } else {
        applier.fail(status);
      }
    }

    @Override public void thisUsesUnstableApi() {}
  }
}
