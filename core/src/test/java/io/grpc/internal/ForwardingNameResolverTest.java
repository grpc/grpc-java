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

import static org.mockito.Mockito.mock;

import io.grpc.AbstractForwardingTest;
import io.grpc.NameResolver;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ForwardingNameResolverTest extends AbstractForwardingTest<NameResolver> {
  private final NameResolver delegate = mock(NameResolver.class);
  private final NameResolver forwarder = new ForwardingNameResolver(delegate) {
  };

  @Override
  public NameResolver mockDelegate() {
    return delegate;
  }

  @Override
  public NameResolver forwarder() {
    return forwarder;
  }

  @Override
  public Class<NameResolver> delegateClass() {
    return NameResolver.class;
  }
}
