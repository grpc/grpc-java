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

package io.grpc.gcp.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.http.HttpTransportFactory;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class MetadataConfigTest {

  @Mock HttpTransportFactory httpTransportFactory;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void testGetAttribute() throws IOException {
    MockHttpTransport.Builder builder = new MockHttpTransport.Builder();
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    response.setContent("foo");
    builder.setLowLevelHttpResponse(response);
    MockHttpTransport httpTransport = builder.build();
    when(httpTransportFactory.create()).thenReturn(httpTransport);
    MetadataConfig metadataConfig = new MetadataConfig(httpTransportFactory);
    metadataConfig.init();
    String val = metadataConfig.getAttribute("instance/attributes/cluster-name");
    assertThat(val).isEqualTo("foo");
  }
}
