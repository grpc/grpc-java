/*
 * Copyright 2023 The gRPC Authors
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

import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link ForwardingServerCall}.
 */
@RunWith(JUnit4.class)
public class ForwardingServerCallTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ServerCall<Integer, Integer> serverCall;
  private ForwardingServerCall<Integer, Integer> forwarder;

  @Before
  public void setUp() {
    forwarder =
        new ForwardingServerCall<Integer, Integer>() {
          @Override
          protected ServerCall<Integer, Integer> delegate() {
            return serverCall;
          }
    };
  }

  @Test
  public void allMethodsForwarded() throws Exception {
    ForwardingTestUtil.testMethodsForwarded(
        ServerCall.class, serverCall, forwarder, Collections.<Method>emptyList());
  }

  @Test
  public void sendMessage() {
    forwarder.sendMessage(12345);

    verify(serverCall).sendMessage(12345);
  }
}

