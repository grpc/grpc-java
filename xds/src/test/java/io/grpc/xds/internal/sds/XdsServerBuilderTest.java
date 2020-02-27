/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.Server;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsChannelBuilder}.
 */
@RunWith(JUnit4.class)
public class XdsServerBuilderTest {

  @Test
  public void buildsXdsServerBuilder() {
    XdsServerBuilder builder = XdsServerBuilder.forPort(8080);
    assertThat(builder).isInstanceOf(XdsServerBuilder.class);
    Server server = builder.build();
    assertThat(server).isNotNull();
  }
}
