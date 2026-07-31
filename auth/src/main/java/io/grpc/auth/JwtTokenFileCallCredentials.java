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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link CallCredentials} implementation that loads a JWT token from a file,
 * parses it to extract its expiration time, and caches/refreshes it.
 */
public final class JwtTokenFileCallCredentials extends CallCredentials {

  private static final Logger log = Logger.getLogger(JwtTokenFileCallCredentials.class.getName());

  private static final Metadata.Key<String> AUTHORIZATION_HEADER =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private static final long INITIAL_BACKOFF_MS = 1000;
  private static final long MAX_BACKOFF_MS = 60000;
  private static final double BACKOFF_MULTIPLIER = 2.0;

  private final String filePath;
  private final TimeProvider timeProvider;
  private final Object lock = new Object();

  private enum ReadState {
    IDLE,
    READING,
    BACKOFF
  }

  private String cachedToken;
  private long expirationTimeMillis;
  private ReadState readState = ReadState.IDLE;
  private Status lastReadFailureStatus;
  private long currentBackoffMs;
  private long nextAttemptTimeMillis;
  private final List<MetadataApplier> queuedAppliers = new ArrayList<>();

  interface TimeProvider {
    long currentTimeMillis();
  }

  private static final TimeProvider SYSTEM_TIME_PROVIDER = new TimeProvider() {
    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  };

  public JwtTokenFileCallCredentials(String filePath) {
    this(filePath, SYSTEM_TIME_PROVIDER);
  }

  @VisibleForTesting
  JwtTokenFileCallCredentials(String filePath, TimeProvider timeProvider) {
    this.filePath = checkNotNull(filePath, "filePath");
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
  }

  @Override
  public void applyRequestMetadata(
      RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
    checkNotNull(requestInfo, "requestInfo");
    checkNotNull(appExecutor, "appExecutor");
    checkNotNull(applier, "applier");

    if (requestInfo.getSecurityLevel() != SecurityLevel.PRIVACY_AND_INTEGRITY) {
      applier.fail(Status.UNAUTHENTICATED
          .withDescription("Channel security level is not PRIVACY_AND_INTEGRITY"));
      return;
    }

    long now = timeProvider.currentTimeMillis();
    TokenInfo tokenToApply = null;
    boolean triggerRead = false;
    Status failStatus = null;

    synchronized (lock) {
      if (readState == ReadState.BACKOFF && now >= nextAttemptTimeMillis) {
        readState = ReadState.IDLE;
      }

      boolean hasValidCache = cachedToken != null && now < expirationTimeMillis;
      boolean expiringSoon = hasValidCache && (expirationTimeMillis - now <= 60000);

      if (hasValidCache) {
        tokenToApply = new TokenInfo(cachedToken, expirationTimeMillis);
        if (expiringSoon && readState == ReadState.IDLE) {
          readState = ReadState.READING;
          triggerRead = true;
        }
      } else {
        if (readState == ReadState.BACKOFF) {
          failStatus = lastReadFailureStatus != null ? lastReadFailureStatus : Status.UNAVAILABLE;
        } else {
          if (readState == ReadState.IDLE) {
            readState = ReadState.READING;
            triggerRead = true;
          }
          queuedAppliers.add(applier);
        }
      }
    }

    if (failStatus != null) {
      applier.fail(failStatus);
      return;
    }

    if (tokenToApply != null) {
      Metadata headers = new Metadata();
      headers.put(AUTHORIZATION_HEADER, "Bearer " + tokenToApply.token);
      applier.apply(headers);
    }

    if (triggerRead) {
      try {
        appExecutor.execute(new Runnable() {
          @Override
          public void run() {
            loadToken();
          }
        });
      } catch (RejectedExecutionException e) {
        handleExecutorRejection(e, tokenToApply != null);
      }
    }
  }

  private void handleExecutorRejection(
      RejectedExecutionException e, boolean isBackgroundReload) {
    log.log(Level.WARNING, "Executor rejected token read task", e);
    List<MetadataApplier> appliersToFail = new ArrayList<>();
    synchronized (lock) {
      readState = ReadState.IDLE;
      if (!isBackgroundReload) {
        appliersToFail.addAll(queuedAppliers);
        queuedAppliers.clear();
      }
    }
    for (MetadataApplier applier : appliersToFail) {
      try {
        applier.fail(Status.UNAVAILABLE
            .withDescription("Executor rejected token read task")
            .withCause(e));
      } catch (Throwable t) {
        log.log(Level.WARNING, "Error calling fail on applier", t);
      }
    }
  }

  private void loadToken() {
    TokenInfo tokenInfo = null;
    Status status = null;
    try {
      tokenInfo = readAndParseTokenFile();
    } catch (IOException e) {
      status = Status.UNAVAILABLE
          .withDescription("Failed to read token file")
          .withCause(e);
    } catch (IllegalArgumentException e) {
      status = Status.UNAUTHENTICATED
          .withDescription("Malformed token or invalid claims")
          .withCause(e);
    } catch (Throwable e) {
      status = Status.UNAVAILABLE
          .withDescription("Unexpected error loading token")
          .withCause(e);
    }

    List<MetadataApplier> appliersToApply = new ArrayList<>();
    List<MetadataApplier> appliersToFail = new ArrayList<>();

    synchronized (lock) {
      if (status == null) {
        cachedToken = tokenInfo.token;
        expirationTimeMillis = tokenInfo.expirationTimeMillis;
        readState = ReadState.IDLE;
        lastReadFailureStatus = null;
        currentBackoffMs = 0;
        nextAttemptTimeMillis = 0;

        appliersToApply.addAll(queuedAppliers);
        queuedAppliers.clear();
      } else {
        lastReadFailureStatus = status;
        readState = ReadState.BACKOFF;

        if (currentBackoffMs == 0) {
          currentBackoffMs = INITIAL_BACKOFF_MS;
        } else {
          currentBackoffMs = Math.min(
              (long) (currentBackoffMs * BACKOFF_MULTIPLIER), MAX_BACKOFF_MS);
        }
        nextAttemptTimeMillis = timeProvider.currentTimeMillis() + currentBackoffMs;

        appliersToFail.addAll(queuedAppliers);
        queuedAppliers.clear();
      }
    }

    if (status == null) {
      Metadata headers = new Metadata();
      headers.put(AUTHORIZATION_HEADER, "Bearer " + tokenInfo.token);
      for (MetadataApplier applier : appliersToApply) {
        try {
          applier.apply(headers);
        } catch (Throwable t) {
          log.log(Level.WARNING, "Error applying credentials", t);
        }
      }
    } else {
      log.log(Level.WARNING, "Failed to load token: " + status.getDescription(), status.getCause());
      for (MetadataApplier applier : appliersToFail) {
        try {
          applier.fail(status);
        } catch (Throwable t) {
          log.log(Level.WARNING, "Error calling fail on applier", t);
        }
      }
    }
  }

  private TokenInfo readAndParseTokenFile() throws IOException {
    File file = new File(filePath);
    long length = file.length();
    if (length > 1048576) {
      throw new IOException("File size exceeds 1 MB limit: " + length);
    }
    byte[] bytes = Files.toByteArray(file);
    if (bytes.length > 1048576) {
      throw new IOException("File size exceeds 1 MB limit: " + bytes.length);
    }
    String token = new String(bytes, StandardCharsets.UTF_8).trim();
    if (token.isEmpty()) {
      throw new IllegalArgumentException("Token file is empty");
    }
    String[] segments = token.split("\\.", -1);
    if (segments.length != 3) {
      throw new IllegalArgumentException("JWT must have 3 segments");
    }
    byte[] payloadBytes;
    try {
      payloadBytes = BaseEncoding.base64Url()
          .omitPadding().decode(segments[1]);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid Base64URL encoding in payload", e);
    }
    String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
    JsonObject jsonObject;
    try {
      JsonElement jsonElement = JsonParser.parseString(payloadJson);
      if (!jsonElement.isJsonObject()) {
        throw new IllegalArgumentException("Payload is not a JSON object");
      }
      jsonObject = jsonElement.getAsJsonObject();
    } catch (JsonSyntaxException e) {
      throw new IllegalArgumentException("Invalid JSON payload", e);
    }
    if (!jsonObject.has("exp")) {
      throw new IllegalArgumentException("Payload does not contain 'exp' claim");
    }
    JsonElement expElement = jsonObject.get("exp");
    if (!expElement.isJsonPrimitive() || !expElement.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("'exp' claim is not a number");
    }
    long expSeconds = expElement.getAsLong();
    if (expSeconds <= 0) {
      throw new IllegalArgumentException("Invalid 'exp' claim value: " + expSeconds);
    }
    long expirationTimeMillis = (expSeconds - 30) * 1000;
    return new TokenInfo(token, expirationTimeMillis);
  }

  @Override
  @SuppressWarnings("deprecation")
  public void thisUsesUnstableApi() {
    // Yes
  }

  private static class TokenInfo {
    final String token;
    final long expirationTimeMillis;

    TokenInfo(String token, long expirationTimeMillis) {
      this.token = token;
      this.expirationTimeMillis = expirationTimeMillis;
    }
  }
}
