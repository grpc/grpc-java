/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static com.google.common.base.Charsets.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.google.common.io.CharStreams;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * An abstract class to help run subclass test suites against all applicable
 */
public abstract class ServerCallImplAbstractTest {
  private static final List<Object[]> ALL_METHOD_TYPES = new ArrayList<Object[]>();

  @Rule public final ExpectedException thrown = ExpectedException.none();
  @Mock protected ServerStream stream;
  @Mock protected ServerCall.Listener<Long> callListener;
  @Captor protected ArgumentCaptor<Status> statusCaptor;

  protected ServerCallImpl<Long, Long> call;
  protected Context.CancellableContext context;

  protected final MethodDescriptor<Long, Long> method;

  protected final Metadata requestHeaders = new Metadata();

  static {
    for (MethodType type : MethodType.values()) {
      ALL_METHOD_TYPES.add(new Object[] {type});
    }
  }


  ServerCallImplAbstractTest(MethodType type) {
    assumeTrue(shouldRunTest(type));
    method = MethodDescriptor.<Long, Long>newBuilder()
        .setType(type)
        .setFullMethodName("/service/method")
        .setRequestMarshaller(new LongMarshaller())
        .setResponseMarshaller(new LongMarshaller())
        .build();
  }

  @Parameters
  public static Collection<Object[]> params() {
    return ALL_METHOD_TYPES;
  }

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    context = Context.ROOT.withCancellation();
    call = new ServerCallImpl<Long, Long>(stream, method, requestHeaders, context,
        DecompressorRegistry.getDefaultInstance(), CompressorRegistry.getDefaultInstance());
  }

  /**
   * The subclass should return true if its test suite is applicable for the method type.
   */
  protected abstract boolean shouldRunTest(MethodType type);

  private static class LongMarshaller implements Marshaller<Long> {
    @Override
    public InputStream stream(Long value) {
      return new ByteArrayInputStream(value.toString().getBytes(UTF_8));
    }

    @Override
    public Long parse(InputStream stream) {
      try {
        return Long.parseLong(CharStreams.toString(new InputStreamReader(stream, UTF_8)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
