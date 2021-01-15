/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.inprocess;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.GrpcUtil.TIMER_SERVICE;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.ChoiceChannelCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.SharedResourceHolder;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link InProcessChannelBuilder}.
 */
@RunWith(JUnit4.class)
public class InProcessChannelBuilderTest {
  @Test
  public void scheduledExecutorService_default() {
    InProcessChannelBuilder builder = InProcessChannelBuilder.forName("foo");
    ClientTransportFactory clientTransportFactory = builder.buildTransportFactory();
    assertSame(
        SharedResourceHolder.get(TIMER_SERVICE),
        clientTransportFactory.getScheduledExecutorService());

    SharedResourceHolder.release(
        TIMER_SERVICE, clientTransportFactory.getScheduledExecutorService());
    clientTransportFactory.close();
  }

  @Test
  public void scheduledExecutorService_custom() {
    InProcessChannelBuilder builder = InProcessChannelBuilder.forName("foo");
    ScheduledExecutorService scheduledExecutorService =
        new FakeClock().getScheduledExecutorService();

    InProcessChannelBuilder builder1 = builder.scheduledExecutorService(scheduledExecutorService);
    assertSame(builder, builder1);

    ClientTransportFactory clientTransportFactory = builder1.buildTransportFactory();

    assertSame(scheduledExecutorService, clientTransportFactory.getScheduledExecutorService());

    clientTransportFactory.close();
  }

  @Test
  public void transportFactoryOnlySupportInsecureChannelCreds() {
    InProcessChannelBuilder builder = InProcessChannelBuilder.forName("foo");
    ClientTransportFactory transportFactory = builder.buildTransportFactory();
    ClientTransportFactory factoryWithInsecure =
        transportFactory.withNewChannelCredentials(InsecureChannelCredentials.create());
    assertThat(factoryWithInsecure).isSameInstanceAs(transportFactory);

    ClientTransportFactory factoryWithChoice = transportFactory.withNewChannelCredentials(
        ChoiceChannelCredentials.create(
            mock(ChannelCredentials.class), mock(ChannelCredentials.class)));
    assertThat(factoryWithChoice).isNull();
    factoryWithChoice = transportFactory.withNewChannelCredentials(
        ChoiceChannelCredentials.create(
            mock(ChannelCredentials.class), InsecureChannelCredentials.create()));
    assertThat(factoryWithChoice).isSameInstanceAs(transportFactory);

    ClientTransportFactory factoryWithComposite = transportFactory.withNewChannelCredentials(
        CompositeChannelCredentials.create(
            mock(ChannelCredentials.class), mock(CallCredentials.class)));
    assertThat(factoryWithComposite).isNull();
    factoryWithComposite = transportFactory.withNewChannelCredentials(
        CompositeChannelCredentials.create(
            InsecureChannelCredentials.create(), mock(CallCredentials.class)));
    assertThat(factoryWithComposite).isSameInstanceAs(transportFactory);

    ClientTransportFactory factoryWithSecure = transportFactory.withNewChannelCredentials(
        mock(ChannelCredentials.class));
    assertThat(factoryWithSecure).isNull();
  }
}
