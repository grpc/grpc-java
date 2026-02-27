/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.matcher;

import com.github.xds.core.v3.TypedExtensionConfig;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;

/**
 * MatchInput for extracting CEL environment from HTTP attributes.
 */
final class HttpAttributesCelMatchInput implements MatchInput {
  static final HttpAttributesCelMatchInput INSTANCE = new HttpAttributesCelMatchInput();

  private HttpAttributesCelMatchInput() {}

  @Override
  public Object apply(MatchContext context) {
    return new GrpcCelEnvironment(context);
  }

  @Override
  public Class<?> outputType() {
    return GrpcCelEnvironment.class;
  }

  static final class Provider implements MatchInputProvider {
    @Override
    public MatchInput getInput(TypedExtensionConfig config) {
      return INSTANCE;
    }

    @Override
    public String typeUrl() {
      return "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";
    }
  }
}
