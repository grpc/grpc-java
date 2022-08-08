/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import java.util.HashSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LdsParserTest {
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();

  @Test
  public void parseHttpConnectionManager_xffNumTrustedHopsUnsupported()
      throws ClientXdsClient.ResourceInvalidException {
    @SuppressWarnings("deprecation")
    HttpConnectionManager hcm = HttpConnectionManager.newBuilder().setXffNumTrustedHops(2).build();
    thrown.expect(ClientXdsClient.ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager with xff_num_trusted_hops unsupported");
    XdsListenerResource.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* does not matter */,
        true /* does not matter */);
  }
}
