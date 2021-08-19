/*
 * Copyright 2021 The gRPC Authors
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

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link InUseStateAggregator}.
 */
@RunWith(JUnit4.class)
public class InUseStateAggregatorTest {

  private InUseStateAggregator<String> aggregator;

  @Before
  public void setUp() {
    aggregator = new InUseStateAggregator<String>() {
      @Override
      protected void handleInUse() {
      }

      @Override
      protected void handleNotInUse() {
      }
    };
  }

  @Test
  public void anyObjectInUse() {
    String objectOne = "1";
    String objectTwo = "2";
    String objectThree = "3";

    aggregator.updateObjectInUse(objectOne, true);
    assertTrue(aggregator.anyObjectInUse(objectOne));

    aggregator.updateObjectInUse(objectTwo, true);
    aggregator.updateObjectInUse(objectThree, true);
    assertTrue(aggregator.anyObjectInUse(objectOne, objectTwo, objectThree));

    aggregator.updateObjectInUse(objectTwo, false);
    assertTrue(aggregator.anyObjectInUse(objectOne, objectThree));
  }
}
