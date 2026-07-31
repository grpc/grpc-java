/*
 * Copyright 2026 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.MetadataApplier;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.testing.TestMethodDescriptors;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class JwtTokenFileCallCredentialsTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  @Mock private MetadataApplier applier1;
  @Mock private MetadataApplier applier2;

  @Captor private ArgumentCaptor<Metadata> headersCaptor;
  @Captor private ArgumentCaptor<Status> statusCaptor;

  private static final Metadata.Key<String> AUTHORIZATION_HEADER =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private FakeTimeProvider timeProvider;
  private FakeExecutor executor;

  @Before
  public void setUp() {
    timeProvider = new FakeTimeProvider();
    executor = new FakeExecutor();
  }

  @After
  public void tearDown() {
    assertEquals(0, executor.runnables.size());
  }

  private String createJwtToken(long expSeconds) {
    String header = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"; // {"alg":"RS256","typ":"JWT"}
    String payload = com.google.common.io.BaseEncoding.base64Url().omitPadding().encode(
        ("{\"exp\":" + expSeconds + "}").getBytes(StandardCharsets.UTF_8));
    String signature = "signature";
    return header + "." + payload + "." + signature;
  }

  private File writeTokenToFile(String content) throws IOException {
    File file = tempFolder.newFile();
    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
    try {
      fos.write(content.getBytes(StandardCharsets.UTF_8));
    } finally {
      fos.close();
    }
    return file;
  }

  private void updateTokenFile(File file, String content) throws IOException {
    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
    try {
      fos.write(content.getBytes(StandardCharsets.UTF_8));
    } finally {
      fos.close();
    }
  }

  @Test
  public void applyMetadata_insecureChannel_fails() throws Exception {
    long nowSecs = timeProvider.currentTimeMillis() / 1000;
    File tokenFile = writeTokenToFile(createJwtToken(nowSecs + 1000));
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.NONE);
    credentials.applyRequestMetadata(requestInfo, executor, applier1);

    verify(applier1).fail(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.UNAUTHENTICATED, status.getCode());
    assertTrue(status.getDescription()
        .contains("Channel security level is not PRIVACY_AND_INTEGRITY"));
  }

  @Test
  public void applyMetadata_validCachedToken_cacheHit() throws Exception {
    String token = createJwtToken(timeProvider.currentTimeMillis() / 1000 + 1000);
    File tokenFile = writeTokenToFile(token);
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    // First load to populate the cache
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    
    verify(applier1).apply(headersCaptor.capture());
    assertEquals("Bearer " + token, headersCaptor.getValue().get(AUTHORIZATION_HEADER));

    // Second load - should be synchronous cache hit
    credentials.applyRequestMetadata(requestInfo, executor, applier2);
    // Executor should NOT have any new runnables
    assertEquals(0, executor.runnables.size());
    
    verify(applier2).apply(headersCaptor.capture());
    assertEquals("Bearer " + token, headersCaptor.getValue().get(AUTHORIZATION_HEADER));
  }

  @Test
  public void applyMetadata_tokenExpiringSoon_triggersBackgroundRefresh() throws Exception {
    timeProvider.set(100_000);
    // exp = 180 seconds, meaning expirationTimeMillis = (180-30)*1000 = 150_000.
    String firstToken = createJwtToken(180);
    File tokenFile = writeTokenToFile(firstToken);
    
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    // First load to populate the cache
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    
    verify(applier1).apply(headersCaptor.capture());
    assertEquals("Bearer " + firstToken, headersCaptor.getValue().get(AUTHORIZATION_HEADER));

    // Update the token file with a new token
    // exp = 1000 seconds, meaning expirationTimeMillis = 970_000.
    String secondToken = createJwtToken(1000);
    updateTokenFile(tokenFile, secondToken);

    // Call apply again. Time hasn't changed (still 100_000), so firstToken is valid
    // (expires at 150_000) but expiring soon (150_000 - 100_000 = 50_000 <= 60_000).
    // It should synchronously apply firstToken and queue a background read.
    credentials.applyRequestMetadata(requestInfo, executor, applier2);
    
    // Check that it synchronously applied the first token
    verify(applier2).apply(headersCaptor.capture());
    assertEquals("Bearer " + firstToken, headersCaptor.getValue().get(AUTHORIZATION_HEADER));

    // And triggered a background read
    assertEquals(1, executor.runnables.size());
    executor.runNext();

    // Now, a subsequent call should return the second (newly cached) token synchronously!
    MetadataApplier applier3 = Mockito.mock(MetadataApplier.class);
    credentials.applyRequestMetadata(requestInfo, executor, applier3);
    assertEquals(0, executor.runnables.size());
    
    ArgumentCaptor<Metadata> headersCaptor3 = ArgumentCaptor.forClass(Metadata.class);
    verify(applier3).apply(headersCaptor3.capture());
    assertEquals("Bearer " + secondToken, headersCaptor3.getValue().get(AUTHORIZATION_HEADER));
  }

  @Test
  public void applyMetadata_concurrentCalls_queued() throws Exception {
    String token = createJwtToken(timeProvider.currentTimeMillis() / 1000 + 1000);
    File tokenFile = writeTokenToFile(token);
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    // First call starts loading
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    
    // Second call while loading is in progress
    credentials.applyRequestMetadata(requestInfo, executor, applier2);
    // Executor should still have only 1 runnable
    assertEquals(1, executor.runnables.size());
    
    // Neither applier should have received a token or failed yet
    verify(applier1, never()).apply(any());
    verify(applier1, never()).fail(any());
    verify(applier2, never()).apply(any());
    verify(applier2, never()).fail(any());
    
    // Run the loader runnable
    executor.runNext();
    
    // Both appliers should now be successfully invoked
    verify(applier1).apply(headersCaptor.capture());
    assertEquals("Bearer " + token, headersCaptor.getValue().get(AUTHORIZATION_HEADER));
    
    ArgumentCaptor<Metadata> headersCaptor2 = ArgumentCaptor.forClass(Metadata.class);
    verify(applier2).apply(headersCaptor2.capture());
    assertEquals("Bearer " + token, headersCaptor2.getValue().get(AUTHORIZATION_HEADER));
  }

  @Test
  public void applyMetadata_fileNotFound_failsUnavailable() throws Exception {
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(
            tempFolder.getRoot().getAbsolutePath() + "/non-existent.txt", timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();

    verify(applier1).fail(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status.getCode());
    assertTrue(status.getCause() instanceof IOException);
  }

  @Test
  public void applyMetadata_fileReadError_backoff() throws Exception {
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(
            tempFolder.getRoot().getAbsolutePath() + "/non-existent.txt", timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    // 1. First attempt fails
    timeProvider.set(10000);
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    
    verify(applier1).fail(statusCaptor.capture());
    Status firstStatus = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, firstStatus.getCode());
    
    // 2. Call again before backoff expires (at t=10500)
    timeProvider.set(10500);
    credentials.applyRequestMetadata(requestInfo, executor, applier2);
    // Should fail synchronously (fail-fast)
    verify(applier2).fail(statusCaptor.capture());
    Status secondStatus = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, secondStatus.getCode());
    assertEquals(firstStatus.getDescription(), secondStatus.getDescription());
    assertEquals(firstStatus.getCause(), secondStatus.getCause());
    // Executor should NOT have been invoked
    assertEquals(0, executor.runnables.size());

    // 3. Move time past backoff limit (t=11001)
    timeProvider.set(11001);
    MetadataApplier applier3 = Mockito.mock(MetadataApplier.class);
    credentials.applyRequestMetadata(requestInfo, executor, applier3);
    // Should NOT fail fast. Instead, it should trigger a new attempt.
    assertEquals(1, executor.runnables.size());
    // Clean up the task from executor list
    executor.runNext();
  }

  @Test
  public void applyMetadata_malformedJwt_failsUnauthenticated() throws Exception {
    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);

    // Case 1: Invalid segment count
    File tokenFile1 = writeTokenToFile("header.payload"); // Only 2 segments
    JwtTokenFileCallCredentials credentials1 =
        new JwtTokenFileCallCredentials(tokenFile1.getAbsolutePath(), timeProvider);
    credentials1.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier1).fail(statusCaptor.capture());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("Malformed token"));

    // Case 2: Invalid base64url payload segment
    File tokenFile2 = writeTokenToFile("header.invalid_base64_symbols#$%.signature");
    JwtTokenFileCallCredentials credentials2 =
        new JwtTokenFileCallCredentials(tokenFile2.getAbsolutePath(), timeProvider);
    MetadataApplier applier2 = Mockito.mock(MetadataApplier.class);
    ArgumentCaptor<Status> statusCaptor2 = ArgumentCaptor.forClass(Status.class);
    credentials2.applyRequestMetadata(requestInfo, executor, applier2);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier2).fail(statusCaptor2.capture());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor2.getValue().getCode());
    assertTrue(statusCaptor2.getValue().getDescription().contains("Malformed token"));
  }

  @Test
  public void applyMetadata_missingExpClaim_failsUnauthenticated() throws Exception {
    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);

    // Case 1: Missing exp claim
    String header = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9";
    String payloadNoExp = com.google.common.io.BaseEncoding.base64Url().omitPadding().encode(
        "{\"foo\":\"bar\"}".getBytes(StandardCharsets.UTF_8));
    File tokenFile1 = writeTokenToFile(header + "." + payloadNoExp + ".signature");
    JwtTokenFileCallCredentials credentials1 =
        new JwtTokenFileCallCredentials(tokenFile1.getAbsolutePath(), timeProvider);
    credentials1.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier1).fail(statusCaptor.capture());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription()
        .contains("Malformed token or invalid claims"));

    // Case 2: exp is not a number
    String payloadExpString = com.google.common.io.BaseEncoding.base64Url().omitPadding().encode(
        "{\"exp\":\"not-a-number\"}".getBytes(StandardCharsets.UTF_8));
    File tokenFile2 = writeTokenToFile(header + "." + payloadExpString + ".signature");
    JwtTokenFileCallCredentials credentials2 =
        new JwtTokenFileCallCredentials(tokenFile2.getAbsolutePath(), timeProvider);
    MetadataApplier applier2 = Mockito.mock(MetadataApplier.class);
    ArgumentCaptor<Status> statusCaptor2 = ArgumentCaptor.forClass(Status.class);
    credentials2.applyRequestMetadata(requestInfo, executor, applier2);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier2).fail(statusCaptor2.capture());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor2.getValue().getCode());
    assertTrue(statusCaptor2.getValue().getDescription()
        .contains("Malformed token or invalid claims"));

    // Case 3: exp is <= 0
    String payloadExpNegative = com.google.common.io.BaseEncoding.base64Url().omitPadding().encode(
        "{\"exp\":-10}".getBytes(StandardCharsets.UTF_8));
    File tokenFile3 = writeTokenToFile(header + "." + payloadExpNegative + ".signature");
    JwtTokenFileCallCredentials credentials3 =
        new JwtTokenFileCallCredentials(tokenFile3.getAbsolutePath(), timeProvider);
    MetadataApplier applier3 = Mockito.mock(MetadataApplier.class);
    ArgumentCaptor<Status> statusCaptor3 = ArgumentCaptor.forClass(Status.class);
    credentials3.applyRequestMetadata(requestInfo, executor, applier3);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier3).fail(statusCaptor3.capture());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor3.getValue().getCode());
    assertTrue(statusCaptor3.getValue().getDescription()
        .contains("Malformed token or invalid claims"));
  }

  @Test
  public void applyMetadata_fileTooLarge_failsUnavailable() throws Exception {
    byte[] largeContent = new byte[1048577];
    java.util.Arrays.fill(largeContent, (byte) 'a');
    File tokenFile = tempFolder.newFile("large-token.txt");
    java.io.FileOutputStream fos = new java.io.FileOutputStream(tokenFile);
    try {
      fos.write(largeContent);
    } finally {
      fos.close();
    }

    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();

    verify(applier1).fail(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status.getCode());
    assertTrue(status.getDescription().contains("Failed to read token file"));
  }

  @Test
  public void applyMetadata_executorRejection_failsUnavailable() throws Exception {
    long nowSecs = timeProvider.currentTimeMillis() / 1000;
    File tokenFile = writeTokenToFile(createJwtToken(nowSecs + 1000));
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    Executor rejectingExecutor = new Executor() {
      @Override
      public void execute(Runnable command) {
        throw new RejectedExecutionException("Rejected!");
      }
    };

    credentials.applyRequestMetadata(requestInfo, rejectingExecutor, applier1);
    
    assertEquals(0, executor.runnables.size());
    
    verify(applier1).fail(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status.getCode());
    assertTrue(status.getDescription().contains("Executor rejected token read task"));
    assertTrue(status.getCause() instanceof RejectedExecutionException);
  }

  @Test
  public void applyMetadata_executorRejection_duringBackgroundRefresh_doesNotFail()
      throws Exception {
    timeProvider.set(100_000);
    String firstToken = createJwtToken(180);
    File tokenFile = writeTokenToFile(firstToken);
    
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    // First load to populate the cache
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier1).apply(any());

    Executor rejectingExecutor = new Executor() {
      @Override
      public void execute(Runnable command) {
        throw new RejectedExecutionException("Rejected!");
      }
    };

    // Second call triggers background refresh.
    // It should synchronously apply the cached token, and attempt background refresh.
    // The background refresh fails to execute, but the applier should succeed!
    credentials.applyRequestMetadata(requestInfo, rejectingExecutor, applier2);
    
    verify(applier2).apply(headersCaptor.capture());
    assertEquals("Bearer " + firstToken, headersCaptor.getValue().get(AUTHORIZATION_HEADER));
    verify(applier2, never()).fail(any());
  }

  @Test
  public void applyMetadata_backgroundRefreshFails_servesCachedTokenDuringBackoff()
      throws Exception {
    timeProvider.set(100_000);
    String firstToken = createJwtToken(180); // expires at 150_000
    File tokenFile = writeTokenToFile(firstToken);
    
    JwtTokenFileCallCredentials credentials =
        new JwtTokenFileCallCredentials(tokenFile.getAbsolutePath(), timeProvider);

    RequestInfoImpl requestInfo = new RequestInfoImpl(SecurityLevel.PRIVACY_AND_INTEGRITY);
    
    // 1. Populate the cache
    credentials.applyRequestMetadata(requestInfo, executor, applier1);
    assertEquals(1, executor.runnables.size());
    executor.runNext();
    verify(applier1).apply(any());

    // Update the token file with an invalid one
    updateTokenFile(tokenFile, "invalid-token");

    // 2. Call again - it's expiring soon since 150_000 - 100_000 <= 60_000
    credentials.applyRequestMetadata(requestInfo, executor, applier2);
    // Applies synchronously
    verify(applier2).apply(headersCaptor.capture());
    assertEquals("Bearer " + firstToken, headersCaptor.getValue().get(AUTHORIZATION_HEADER));
    
    // Background task queued
    assertEquals(1, executor.runnables.size());
    executor.runNext(); // Task runs and fails, putting it in BACKOFF state
    
    // 3. Call again while in BACKOFF state. Time hasn't changed, still 100_000.
    // Token is still valid.
    MetadataApplier applier3 = Mockito.mock(MetadataApplier.class);
    credentials.applyRequestMetadata(requestInfo, executor, applier3);
    
    // No new background tasks should be scheduled because it's in BACKOFF
    assertEquals(0, executor.runnables.size());
    
    // It should STILL serve the cached token because it is valid
    ArgumentCaptor<Metadata> headersCaptor3 = ArgumentCaptor.forClass(Metadata.class);
    verify(applier3).apply(headersCaptor3.capture());
    assertEquals("Bearer " + firstToken, headersCaptor3.getValue().get(AUTHORIZATION_HEADER));
    verify(applier3, never()).fail(any());
    
    // Move time past backoff limit
    timeProvider.set(105_000); // 105_000 > 100_000 + 1000
    
    // 4. Call again. Time is past backoff and expiring soon.
    // It should serve cache AND schedule refresh.
    MetadataApplier applier4 = Mockito.mock(MetadataApplier.class);
    credentials.applyRequestMetadata(requestInfo, executor, applier4);
    
    assertEquals(1, executor.runnables.size());
    executor.runNext(); // Consume the failing background task again
    
    ArgumentCaptor<Metadata> headersCaptor4 = ArgumentCaptor.forClass(Metadata.class);
    verify(applier4).apply(headersCaptor4.capture());
    assertEquals("Bearer " + firstToken, headersCaptor4.getValue().get(AUTHORIZATION_HEADER));
  }

  private static class FakeTimeProvider implements JwtTokenFileCallCredentials.TimeProvider {
    private long currentTimeMillis = 0;

    @Override
    public long currentTimeMillis() {
      return currentTimeMillis;
    }

    void set(long timeMillis) {
      currentTimeMillis = timeMillis;
    }
  }

  private static class FakeExecutor implements Executor {
    private final List<Runnable> runnables = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      runnables.add(command);
    }

    void runNext() {
      if (runnables.isEmpty()) {
        throw new IllegalStateException("No runnables queued");
      }
      runnables.remove(0).run();
    }
  }

  private static final class RequestInfoImpl extends CallCredentials.RequestInfo {
    private final SecurityLevel securityLevel;

    RequestInfoImpl(SecurityLevel securityLevel) {
      this.securityLevel = securityLevel;
    }

    @Override
    public MethodDescriptor<?, ?> getMethodDescriptor() {
      return MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setFullMethodName("a.service/method")
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build();
    }

    @Override
    public SecurityLevel getSecurityLevel() {
      return securityLevel;
    }

    @Override
    public String getAuthority() {
      return "testauthority";
    }

    @Override
    public Attributes getTransportAttrs() {
      return Attributes.EMPTY;
    }
  }
}
