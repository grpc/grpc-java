/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.services;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.protobuf.MessageLite;
import io.grpc.CallOptions;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link BinaryLogProviderImpl}.
 */
@RunWith(JUnit4.class)
public class BinaryLogProviderImplTest {
  @Test
  public void logIsUsed() {
    BinaryLogProviderImpl provider = new BinaryLogProviderImpl(new NoopSink(), "*");
    assertNotNull(provider.getClientInterceptor("package.service/method", CallOptions.DEFAULT));
    assertNotNull(provider.getServerInterceptor("package.service/method"));
  }

  @Test
  public void logNotUsed() {
    BinaryLogProviderImpl provider
        = new BinaryLogProviderImpl(new NoopSink(), "package.service/method");
    assertNull(
        provider.getClientInterceptor("otherpackage.service/othermethod", CallOptions.DEFAULT));
    assertNull(
        provider.getServerInterceptor("otherpackage.service/othermethod"));
  }

  private static final class NoopSink extends BinaryLogSink {
    @Override
    public void write(MessageLite message) {

    }

    @Override
    protected boolean isAvailable() {
      return false;
    }

    @Override
    protected int priority() {
      return 0;
    }

    @Override
    public void close() throws IOException {

    }
  }
}
