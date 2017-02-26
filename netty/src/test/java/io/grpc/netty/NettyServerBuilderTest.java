/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.netty;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.ssl.SslContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/**
 * Unit tests for {@link NettyServerBuilder}.
 */
@RunWith(JUnit4.class)
public class NettyServerBuilderTest {

  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void sslContextCanBeNull() {
    NettyServerBuilder builder = NettyServerBuilder.forPort(8080);
    builder.sslContext(null);
  }

  @Test
  public void failIfSslContextIsNotServer() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Client SSL context can not be used for server");

    SslContext sslContext = mock(SslContext.class);
    when(sslContext.isClient()).thenReturn(true);

    NettyServerBuilder builder = NettyServerBuilder.forPort(8080);;
    builder.sslContext(sslContext);
  }
}
