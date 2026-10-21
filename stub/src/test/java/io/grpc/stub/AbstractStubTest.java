/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.stub;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.testing.DeadlineSubject.deadline;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Deadline;
import io.grpc.stub.AbstractStub.StubFactory;
import io.grpc.stub.AbstractStubTest.NoopStub;
import java.time.Duration;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AbstractStubTest extends BaseAbstractStubTest<NoopStub> {

  @Override
  NoopStub create(Channel channel, CallOptions callOptions) {
    return new NoopStub(channel, callOptions);
  }

  @Test
  public void defaultCallOptions() {
    NoopStub stub = NoopStub.newStub(new StubFactory<NoopStub>() {
      @Override
      public NoopStub newStub(Channel channel, CallOptions callOptions) {
        return create(channel, callOptions);
      }
    }, channel, CallOptions.DEFAULT);

    assertThat(stub.getCallOptions().getOption(ClientCalls.STUB_TYPE_OPTION))
        .isNull();
  }

  @Test
  @IgnoreJRERequirement
  public void testDuration() {
    NoopStub stub = NoopStub.newStub(new StubFactory<NoopStub>() {
      @Override
      public NoopStub newStub(Channel channel, CallOptions callOptions) {
        return create(channel, callOptions);
      }
    }, channel, CallOptions.DEFAULT);
    NoopStub stubInstance = stub.withDeadlineAfter(Duration.ofMinutes(1L));
    Deadline actual = stubInstance.getCallOptions().getDeadline();
    Deadline expected = Deadline.after(1, MINUTES);

    assertAbout(deadline()).that(actual).isWithin(10, MILLISECONDS).of(expected);
  }

  class NoopStub extends AbstractStub<NoopStub> {
    NoopStub(Channel channel, CallOptions options) {
      super(channel, options);
    }

    @Override
    protected NoopStub build(Channel channel, CallOptions callOptions) {
      return new NoopStub(channel, callOptions);
    }
  }
}

