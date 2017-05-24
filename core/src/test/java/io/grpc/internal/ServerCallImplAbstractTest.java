/*
 * Copyright 2017, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static com.google.common.base.Charsets.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * An abstract test class to help run subclass test suites against all applicable
 * {@link MethodType} classes. Subclasses must implement {{@link #shouldRunTest(MethodType)}}.
 */
public abstract class ServerCallImplAbstractTest {
  private static final List<Object[]> ALL = Lists.transform(
      Arrays.asList(MethodType.values()),
      new Function<MethodType, Object[]>() {
        @Nullable
        @Override
        public Object[] apply(@Nullable MethodType input) {
          return new Object[] {input};
        }
      });

  @Rule public final ExpectedException thrown = ExpectedException.none();
  @Mock protected ServerStream stream;
  @Mock protected ServerCall.Listener<Long> callListener;
  @Captor protected ArgumentCaptor<Status> statusCaptor;

  protected ServerCallImpl<Long, Long> call;
  protected Context.CancellableContext context;

  protected final MethodDescriptor<Long, Long> method;

  protected final Metadata requestHeaders = new Metadata();

  ServerCallImplAbstractTest(MethodType type) {
    method = MethodDescriptor.<Long, Long>newBuilder()
        .setType(type)
        .setFullMethodName("/service/method")
        .setRequestMarshaller(new LongMarshaller())
        .setResponseMarshaller(new LongMarshaller())
        .build();
    assumeTrue(shouldRunTest(type));
  }

  @Parameters
  public static Collection<Object[]> params() {
    return ALL;
  }

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    context = Context.ROOT.withCancellation();
    call = new ServerCallImpl<Long, Long>(stream, method, requestHeaders, context,
        DecompressorRegistry.getDefaultInstance(), CompressorRegistry.getDefaultInstance());
  }

  /**
   * The subclass test class should return true if its test suite applies for the method type.
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
