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

import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import org.mockito.ArgumentMatcher;

/**
 * Mockito matchers for testing LoadBalancers.
 */
public final class LoadBalancerMatchers {
  private LoadBalancerMatchers() {}

  public static SubchannelPicker pickerReturns(final PickResult result) {
    return pickerReturns(new ArgumentMatcher<PickResult>() {
      @Override public boolean matches(PickResult obj) {
        return result.equals(obj);
      }

      @Override public String toString() {
        return "[equals " + result + "]";
      }
    });
  }

  public static SubchannelPicker pickerReturns(Status.Code code) {
    return pickerReturns(new ArgumentMatcher<PickResult>() {
      @Override public boolean matches(PickResult obj) {
        return obj.getStatus() != null && code.equals(obj.getStatus().getCode());
      }

      @Override public String toString() {
        return "[with code " + code + "]";
      }
    });
  }

  public static SubchannelPicker pickerReturns(final ArgumentMatcher<PickResult> matcher) {
    return argThat(new ArgumentMatcher<SubchannelPicker>() {
      @Override public boolean matches(SubchannelPicker picker) {
        return matcher.matches(picker.pickSubchannel(mock(PickSubchannelArgs.class)));
      }

      @Override public String toString() {
        return "[picker returns: result " + matcher + "]";
      }
    });
  }
}
