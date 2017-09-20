/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * This class uses the AbstractForwardingTest to make sure all methods are forwarded, and then
 * use specific test cases to make sure the values are forwarded as is. The abstract test uses
 * nulls for args that don't have JLS default values.
 */
@RunWith(JUnit4.class)
public class ForwardingServerCallListenerTest
    extends AbstractForwardingTest<ServerCall.Listener<Integer>> {

  @Mock private ServerCall.Listener<Integer> serverCallListener;
  private ForwardingServerCallListener<Integer> forwarder;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    forwarder = new SimpleForwardingServerCallListener<Integer>(serverCallListener) {};
  }

  @Override
  public ServerCall.Listener<Integer> mockDelegate() {
    return serverCallListener;
  }

  @Override
  public ServerCall.Listener<Integer> forwarder() {
    return forwarder;
  }

  @Override
  public Class<ServerCall.Listener<Integer>> delegateClass() {
    @SuppressWarnings("unchecked")
    Class<ServerCall.Listener<Integer>> ret =
        (Class<ServerCall.Listener<Integer>>) ((Object) ServerCall.Listener.class);
    return ret;
  }

  @Test
  public void onMessage() {
    forwarder.onMessage(1234);

    verify(serverCallListener).onMessage(1234);
  }
}

