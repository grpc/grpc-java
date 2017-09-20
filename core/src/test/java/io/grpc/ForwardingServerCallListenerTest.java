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

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class ForwardingServerCallListenerTest
    extends AbstractForwardingTest<ServerCall.Listener<Void>> {

  @Mock private ServerCall.Listener<Void> serverCallListener;
  private ForwardingServerCallListener<Void> forwarder;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    forwarder = new SimpleForwardingServerCallListener<Void>(serverCallListener) {};
  }

  @Override
  public ServerCall.Listener<Void> mockDelegate() {
    return serverCallListener;
  }

  @Override
  public ServerCall.Listener<Void> forwarder() {
    return forwarder;
  }

  @Override
  public Class<ServerCall.Listener<Void>> delegateClass() {
    @SuppressWarnings("unchecked")
    Class<ServerCall.Listener<Void>> ret =
        (Class<ServerCall.Listener<Void>>) ((Object) ServerCall.Listener.class);
    return ret;
  }
}

