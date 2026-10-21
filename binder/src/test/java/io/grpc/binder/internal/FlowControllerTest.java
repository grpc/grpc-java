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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FlowControllerTest {

  @Test
  public void shouldReportTransmitWindowInitiallyNonFull() {
    FlowController flowController = new FlowController(100);
    assertThat(flowController.isTransmitWindowFull()).isFalse();
  }

  @Test
  public void shouldReportTransmitWindowFull() {
    FlowController flowController = new FlowController(100);
    boolean transition = flowController.notifyBytesSent(99);
    assertThat(transition).isFalse();
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    transition = flowController.notifyBytesSent(1);
    assertThat(transition).isTrue();
    assertThat(flowController.isTransmitWindowFull()).isTrue();
  }

  @Test
  public void shouldHandleAck() {
    FlowController flowController = new FlowController(100);
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    flowController.notifyBytesSent(1);
    flowController.notifyBytesSent(100);
    assertThat(flowController.isTransmitWindowFull()).isTrue();
    boolean transition = flowController.handleAcknowledgedBytes(100);
    assertThat(transition).isTrue();
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    transition = flowController.handleAcknowledgedBytes(1);
    assertThat(transition).isFalse();
  }

  @Test
  public void shouldHandleAcksOutOfOrder() {
    FlowController flowController = new FlowController(100);
    flowController.notifyBytesSent(49);
    flowController.notifyBytesSent(51);
    assertThat(flowController.isTransmitWindowFull()).isTrue();
    flowController.handleAcknowledgedBytes(100);
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    flowController.handleAcknowledgedBytes(49);
    assertThat(flowController.isTransmitWindowFull()).isFalse();
  }

  @Test
  public void shouldHandleAckOverflow() {
    FlowController flowController = new FlowController(2);

    // Signed long overflow
    flowController.notifyBytesSent(Long.MAX_VALUE); // totalBytesSent = 0b011..1
    flowController.handleAcknowledgedBytes(Long.MAX_VALUE); // totalBytesAckedByPeer = 0b011..1
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    flowController.notifyBytesSent(1); // totalBytesSent = 0b100..00
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    flowController.notifyBytesSent(1); // totalBytesSent = 0b100..01
    assertThat(flowController.isTransmitWindowFull()).isTrue();

    // Unsigned long overflow.
    flowController.notifyBytesSent(Long.MAX_VALUE - 1); // totalBytesSent = 0b111..11
    flowController.handleAcknowledgedBytes(-1); // totalBytesAckedByPeer = 0b111..1
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    flowController.notifyBytesSent(1); // totalBytesSent = 0
    assertThat(flowController.isTransmitWindowFull()).isFalse();
    flowController.notifyBytesSent(1); // totalBytesSent = 1
    assertThat(flowController.isTransmitWindowFull()).isTrue();
  }
}
