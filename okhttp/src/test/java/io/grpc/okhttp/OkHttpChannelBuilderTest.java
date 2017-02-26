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

package io.grpc.okhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.squareup.okhttp.ConnectionSpec;
import io.grpc.NameResolver;
import io.grpc.internal.GrpcUtil;
import java.net.InetSocketAddress;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link OkHttpChannelBuilder}.
 */
@RunWith(JUnit4.class)
public class OkHttpChannelBuilderTest {

  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void overrideAllowsInvalidAuthority() {
    OkHttpChannelBuilder builder = new OkHttpChannelBuilder("good", 1234) {
      @Override
      protected String checkAuthority(String authority) {
        return authority;
      }
    };

    builder.overrideAuthority("[invalidauthority")
        .negotiationType(NegotiationType.PLAINTEXT)
        .buildTransportFactory();
  }

  @Test
  public void failOverrideInvalidAuthority() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority:");
    OkHttpChannelBuilder builder = new OkHttpChannelBuilder("good", 1234);

    builder.overrideAuthority("[invalidauthority")
        .negotiationType(NegotiationType.PLAINTEXT)
        .buildTransportFactory();
  }

  @Test
  public void failInvalidAuthority() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid host or port");

    OkHttpChannelBuilder.forAddress("invalid_authority", 1234);
  }

  @Test
  public void failForUsingClearTextSpecDirectly() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("plaintext ConnectionSpec is not accepted");

    OkHttpChannelBuilder.forAddress("host", 1234).connectionSpec(ConnectionSpec.CLEARTEXT);
  }

  @Test
  public void allowUsingTlsConnectionSpec() {
    OkHttpChannelBuilder.forAddress("host", 1234).connectionSpec(ConnectionSpec.MODERN_TLS);
  }

  @Test
  public void usePlaintext_newClientTransportAllowed() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("host", 1234).usePlaintext(true);
    builder.buildTransportFactory().newClientTransport(new InetSocketAddress(5678),
        "dummy_authority", "dummy_userAgent");
  }

  @Test
  public void usePlaintextDefaultPort() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("host", 1234).usePlaintext(true);
    assertEquals(GrpcUtil.DEFAULT_PORT_PLAINTEXT,
        builder.getNameResolverParams().get(NameResolver.Factory.PARAMS_DEFAULT_PORT).intValue());
  }

  @Test
  public void usePlaintextCreatesNullSocketFactory() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("host", 1234);
    assertNotNull(builder.createSocketFactory());

    builder.usePlaintext(true);
    assertNull(builder.createSocketFactory());
  }
}

