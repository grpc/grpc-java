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

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.grpc.Attributes;
import io.grpc.Status;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ManagedClientTransportTest {

  @Test
  public void testListener() {
    ManagedClientTransport.Listener listener = new ManagedClientTransport.Listener() {
      @Override
      public void transportShutdown(Status s) {}

      @Override
      public void transportTerminated() {}

      @Override
      public void transportReady() {}

      @Override
      public void transportInUse(boolean inUse) {}
    };

    // Test that the listener methods do not throw.
    listener.transportShutdown(Status.OK);
    listener.transportTerminated();
    listener.transportReady();
    listener.transportInUse(true);


    assertNull(listener.filterTransport(null));

    Attributes attributes = Attributes.newBuilder()
        .set(Attributes.Key.create("yolo"), "To be, or not to be?")
        .set(Attributes.Key.create("foo"), "bar!")
        .set(Attributes.Key.create("bar"), "foo?")
        .build();
    assertEquals(attributes, listener.filterTransport(attributes));
  }
}