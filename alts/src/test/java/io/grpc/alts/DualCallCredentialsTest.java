/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.alts;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.RequestInfo;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.alts.internal.AltsInternalContext;
import io.grpc.alts.internal.AltsProtocolNegotiator;
import io.grpc.testing.TestMethodDescriptors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link DualCallCredentials}. */
@RunWith(JUnit4.class)
public class DualCallCredentialsTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock CallCredentials tlsCallCredentials;

  @Mock CallCredentials altsCallCredentials;

  private static final String AUTHORITY = "testauthority";
  private static final SecurityLevel SECURITY_LEVEL = SecurityLevel.PRIVACY_AND_INTEGRITY;

  @Test
  public void invokeTlsCallCredentials() {
    DualCallCredentials callCredentials =
        new DualCallCredentials(tlsCallCredentials, altsCallCredentials);
    RequestInfo requestInfo = new RequestInfoImpl(false);
    callCredentials.applyRequestMetadata(requestInfo, null, null);

    verify(altsCallCredentials, never()).applyRequestMetadata(any(), any(), any());
    verify(tlsCallCredentials, times(1)).applyRequestMetadata(requestInfo, null, null);
  }

  @Test
  public void invokeAltsCallCredentials() {
    DualCallCredentials callCredentials =
        new DualCallCredentials(tlsCallCredentials, altsCallCredentials);
    RequestInfo requestInfo = new RequestInfoImpl(true);
    callCredentials.applyRequestMetadata(requestInfo, null, null);

    verify(altsCallCredentials, times(1)).applyRequestMetadata(requestInfo, null, null);
    verify(tlsCallCredentials, never()).applyRequestMetadata(any(), any(), any());
  }

  private static final class RequestInfoImpl extends CallCredentials.RequestInfo {
    private Attributes attrs;

    RequestInfoImpl(boolean hasAltsContext) {
      attrs =
          hasAltsContext
              ? Attributes.newBuilder()
                  .set(
                      AltsProtocolNegotiator.AUTH_CONTEXT_KEY,
                      AltsInternalContext.getDefaultInstance())
                  .build()
              : Attributes.EMPTY;
    }

    @Override
    public MethodDescriptor<?, ?> getMethodDescriptor() {
      return TestMethodDescriptors.voidMethod();
    }

    @Override
    public SecurityLevel getSecurityLevel() {
      return SECURITY_LEVEL;
    }

    @Override
    public String getAuthority() {
      return AUTHORITY;
    }

    @Override
    public Attributes getTransportAttrs() {
      return attrs;
    }
  }
}
