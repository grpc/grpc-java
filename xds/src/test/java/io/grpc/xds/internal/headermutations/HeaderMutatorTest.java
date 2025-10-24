/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.headermutations;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.testing.TestLogHandler;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption.HeaderAppendAction;
import io.grpc.Metadata;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderMutatorTest {

  private static final Metadata.Key<String> ASCII_KEY =
      Metadata.Key.of("some-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<byte[]> BINARY_KEY =
      Metadata.Key.of("some-key-bin", Metadata.BINARY_BYTE_MARSHALLER);
  private static final Metadata.Key<String> APPEND_KEY =
      Metadata.Key.of("append-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> ADD_KEY =
      Metadata.Key.of("add-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> OVERWRITE_KEY =
      Metadata.Key.of("overwrite-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> REMOVE_KEY =
      Metadata.Key.of("remove-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> NEW_ADD_KEY =
      Metadata.Key.of("new-add-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> NEW_OVERWRITE_KEY =
      Metadata.Key.of("new-overwrite-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> OVERWRITE_IF_EXISTS_KEY =
      Metadata.Key.of("overwrite-if-exists-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> OVERWRITE_IF_EXISTS_ABSENT_KEY =
      Metadata.Key.of("overwrite-if-exists-absent-key", Metadata.ASCII_STRING_MARSHALLER);

  private final HeaderMutator headerMutator = HeaderMutator.create();

  private static final TestLogHandler logHandler = new TestLogHandler();
  private static final Logger logger =
      Logger.getLogger(HeaderMutator.HeaderMutatorImpl.class.getName());

  @Before
  public void setUp() {
    logHandler.clear();
    logger.addHandler(logHandler);
    logger.setLevel(Level.WARNING);
  }

  @After
  public void tearDown() {
    logger.removeHandler(logHandler);
  }

  private static HeaderValueOption header(String key, String value, HeaderAppendAction action) {
    return HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value)).setAppendAction(action)
        .build();
  }

  @Test
  public void applyRequestMutations_asciiHeaders() {
    Metadata headers = new Metadata();
    headers.put(APPEND_KEY, "append-value-1");
    headers.put(ADD_KEY, "add-value-original");
    headers.put(OVERWRITE_KEY, "overwrite-value-original");
    headers.put(REMOVE_KEY, "remove-value-original");
    headers.put(OVERWRITE_IF_EXISTS_KEY, "original-value");

    RequestHeaderMutations mutations = RequestHeaderMutations.create(ImmutableList.of(
        // Append to existing header
        header(APPEND_KEY.name(), "append-value-2", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
        // Try to add to an existing header (should be no-op)
        header(ADD_KEY.name(), "add-value-new", HeaderAppendAction.ADD_IF_ABSENT),
        // Add a new header
        header(NEW_ADD_KEY.name(), "new-add-value", HeaderAppendAction.ADD_IF_ABSENT),
        // Overwrite an existing header
        header(OVERWRITE_KEY.name(), "overwrite-value-new",
            HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD),
        // Overwrite a new header
        header(NEW_OVERWRITE_KEY.name(), "new-overwrite-value",
            HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD),
        // Overwrite an existing header if it exists
        header(OVERWRITE_IF_EXISTS_KEY.name(), "new-value", HeaderAppendAction.OVERWRITE_IF_EXISTS),
        // Try to overwrite a header that does not exist
        header(OVERWRITE_IF_EXISTS_ABSENT_KEY.name(), "new-value",
            HeaderAppendAction.OVERWRITE_IF_EXISTS)),
        ImmutableList.of(REMOVE_KEY.name()));

    headerMutator.applyRequestMutations(mutations, headers);

    assertThat(headers.getAll(APPEND_KEY)).containsExactly("append-value-1", "append-value-2");
    assertThat(headers.get(ADD_KEY)).isEqualTo("add-value-original");
    assertThat(headers.get(NEW_ADD_KEY)).isEqualTo("new-add-value");
    assertThat(headers.get(OVERWRITE_KEY)).isEqualTo("overwrite-value-new");
    assertThat(headers.get(NEW_OVERWRITE_KEY)).isEqualTo("new-overwrite-value");
    assertThat(headers.containsKey(REMOVE_KEY)).isFalse();
    assertThat(headers.get(OVERWRITE_IF_EXISTS_KEY)).isEqualTo("new-value");
    assertThat(headers.containsKey(OVERWRITE_IF_EXISTS_ABSENT_KEY)).isFalse();
  }

  @Test
  public void applyRequestMutations_InvalidAppendAction_isIgnored() {
    Metadata headers = new Metadata();
    headers.put(ASCII_KEY, "value1");
    headerMutator
        .applyRequestMutations(
            RequestHeaderMutations
                .create(
                    ImmutableList.of(
                        HeaderValueOption.newBuilder()
                            .setHeader(HeaderValue.newBuilder().setKey(ASCII_KEY.name())
                                .setValue("value2"))
                            .setAppendActionValue(-1).build(),
                        HeaderValueOption.newBuilder()
                            .setHeader(HeaderValue.newBuilder().setKey(BINARY_KEY.name())
                                .setValue("value2"))
                            .setAppendActionValue(-5).build()),
                    ImmutableList.of()),
            headers);
    assertThat(headers.getAll(ASCII_KEY)).containsExactly("value1");
  }

  @Test
  public void applyRequestMutations_removalHasPriority() {
    Metadata headers = new Metadata();
    headers.put(REMOVE_KEY, "value");
    RequestHeaderMutations mutations = RequestHeaderMutations.create(
        ImmutableList.of(
            header(REMOVE_KEY.name(), "new-value", HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)),
        ImmutableList.of(REMOVE_KEY.name()));

    headerMutator.applyRequestMutations(mutations, headers);

    assertThat(headers.containsKey(REMOVE_KEY)).isFalse();
  }

  @Test
  public void applyRequestMutations_binary_withBase64RawValue() {
    Metadata headers = new Metadata();
    byte[] value = new byte[] {1, 2, 3};
    HeaderValueOption option = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(BINARY_KEY.name()).setRawValue(
            ByteString.copyFrom(BaseEncoding.base64().encode(value), StandardCharsets.US_ASCII)))
        .setAppendAction(HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD).build();
    headerMutator.applyRequestMutations(
        RequestHeaderMutations.create(ImmutableList.of(option), ImmutableList.of()), headers);
    assertThat(headers.get(BINARY_KEY)).isEqualTo(value);
  }

  @Test
  public void applyRequestMutations_binary_withBase64Value() {
    Metadata headers = new Metadata();
    byte[] value = new byte[] {1, 2, 3};
    String base64Value = BaseEncoding.base64().encode(value);
    HeaderValueOption option = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(BINARY_KEY.name()).setValue(base64Value))
        .setAppendAction(HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD).build();

    headerMutator.applyRequestMutations(
        RequestHeaderMutations.create(ImmutableList.of(option), ImmutableList.of()), headers);
    assertThat(headers.get(BINARY_KEY)).isEqualTo(value);
  }

  @Test
  public void applyRequestMutations_ascii_withRawValue() {
    Metadata headers = new Metadata();
    byte[] value = "raw-value".getBytes(StandardCharsets.US_ASCII);
    HeaderValueOption option = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(ASCII_KEY.name())
            .setRawValue(ByteString.copyFrom(value)))
        .setAppendAction(HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD).build();
    headerMutator.applyRequestMutations(
        RequestHeaderMutations.create(ImmutableList.of(option), ImmutableList.of()), headers);
    assertThat(headers.get(Metadata.Key.of(ASCII_KEY.name(), Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("raw-value");
  }

  @Test
  public void applyResponseMutations_asciiHeaders() {
    Metadata headers = new Metadata();
    headers.put(APPEND_KEY, "append-value-1");
    headers.put(ADD_KEY, "add-value-original");
    headers.put(OVERWRITE_KEY, "overwrite-value-original");

    ResponseHeaderMutations mutations = ResponseHeaderMutations.create(ImmutableList.of(
        header(APPEND_KEY.name(), "append-value-2", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
        header(ADD_KEY.name(), "add-value-new", HeaderAppendAction.ADD_IF_ABSENT),
        header(NEW_ADD_KEY.name(), "new-add-value", HeaderAppendAction.ADD_IF_ABSENT),
        header(OVERWRITE_KEY.name(), "overwrite-value-new",
            HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD),
        header(NEW_OVERWRITE_KEY.name(), "new-overwrite-value",
            HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)));

    headerMutator.applyResponseMutations(mutations, headers);

    assertThat(headers.getAll(APPEND_KEY)).containsExactly("append-value-1", "append-value-2");
    assertThat(headers.get(ADD_KEY)).isEqualTo("add-value-original");
    assertThat(headers.get(NEW_ADD_KEY)).isEqualTo("new-add-value");
    assertThat(headers.get(OVERWRITE_KEY)).isEqualTo("overwrite-value-new");
    assertThat(headers.get(NEW_OVERWRITE_KEY)).isEqualTo("new-overwrite-value");
  }


  @Test
  public void applyResponseMutations_InvalidAppendAction_isIgnored() {
    Metadata headers = new Metadata();
    headers.put(ASCII_KEY, "value1");
    headerMutator
        .applyResponseMutations(
            ResponseHeaderMutations
                .create(
                    ImmutableList.of(
                        HeaderValueOption.newBuilder()
                            .setHeader(HeaderValue.newBuilder().setKey(ASCII_KEY.name())
                                .setValue("value2"))
                            .setAppendActionValue(-1).build(),
                        HeaderValueOption
                            .newBuilder().setHeader(HeaderValue.newBuilder()
                                .setKey(BINARY_KEY.name()).setValue("value2"))
                            .setAppendActionValue(-5).build())),
            headers);
    assertThat(headers.getAll(ASCII_KEY)).containsExactly("value1");
  }

  @Test
  public void applyResponseMutations_binary_withBase64RawValue() {
    Metadata headers = new Metadata();
    byte[] value = new byte[] {1, 2, 3};
    HeaderValueOption option = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(BINARY_KEY.name()).setRawValue(
            ByteString.copyFrom(BaseEncoding.base64().encode(value), StandardCharsets.US_ASCII)))
        .setAppendAction(HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD).build();
    headerMutator.applyResponseMutations(ResponseHeaderMutations.create(ImmutableList.of(option)),
        headers);
    assertThat(headers.get(BINARY_KEY)).isEqualTo(value);
  }

  @Test
  public void applyResponseMutations_binary_withBase64Value() {
    Metadata headers = new Metadata();
    byte[] value = new byte[] {1, 2, 3};
    String base64Value = BaseEncoding.base64().encode(value);
    HeaderValueOption option = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(BINARY_KEY.name()).setValue(base64Value))
        .setAppendAction(HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD).build();

    headerMutator.applyResponseMutations(ResponseHeaderMutations.create(ImmutableList.of(option)),
        headers);
    assertThat(headers.get(BINARY_KEY)).isEqualTo(value);
  }

  @Test
  public void applyResponseMutations_ascii_withRawValue() {
    Metadata headers = new Metadata();
    byte[] value = "raw-value".getBytes(StandardCharsets.US_ASCII);
    HeaderValueOption option = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(ASCII_KEY.name())
            .setRawValue(ByteString.copyFrom(value)))
        .setAppendAction(HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD).build();

    headerMutator.applyResponseMutations(ResponseHeaderMutations.create(ImmutableList.of(option)),
        headers);
    assertThat(headers.get(Metadata.Key.of(ASCII_KEY.name(), Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("raw-value");
  }

  @Test
  public void applyRequestMutations_unrecognizedAction_logsWarning() {
    Metadata headers = new Metadata();
    RequestHeaderMutations mutations =
        RequestHeaderMutations.create(ImmutableList.of(HeaderValueOption.newBuilder()
            .setHeader(HeaderValue.newBuilder().setKey("key").setValue("value"))
            .setAppendActionValue(-1).build()), ImmutableList.of());
    headerMutator.applyRequestMutations(mutations, headers);

    List<LogRecord> records = logHandler.getStoredLogRecords();
    assertThat(records).hasSize(1);
    assertThat(records.get(0).getMessage())
        .contains("Unrecognized HeaderAppendAction: UNRECOGNIZED");
  }
}
