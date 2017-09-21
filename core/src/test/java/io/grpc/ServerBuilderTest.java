/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link ServerBuilder}.
 */
@RunWith(JUnit4.class)
public class ServerBuilderTest {
  @Test
  public void apiCompatible() throws Exception {
    // The purpose of this test is to ensure that gRPC developers do not accidentally add new
    // abstract methods to ServerBuilder, breaking existing ServerBuilder implementations.

    // DO NOT add methods from gRPC API > 1.0 to this class
    class Builder extends ServerBuilder<Builder> {
      @Override
      public Builder directExecutor() {
        return this;
      }

      @Override
      public Builder executor(@Nullable Executor executor) {
        return this;
      }

      @Override
      public Builder addService(ServerServiceDefinition service) {
        return this;
      }

      @Override
      public Builder addService(BindableService bindableService) {
        return this;
      }

      @Override
      public Builder fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry) {
        return this;
      }

      @Override
      public Builder useTransportSecurity(File certChain, File privateKey) {
        return this;
      }

      @Override
      public Builder decompressorRegistry(@Nullable DecompressorRegistry registry) {
        return this;
      }

      @Override
      public Builder compressorRegistry(@Nullable CompressorRegistry registry) {
        return this;
      }

      @Override
      public Server build() {
        return null;
      }
    }

    // A sanity check to make sure nobody added gRPC API > 1.0 methods to the builder
    assertEquals(9, ReflectionUtil.overrideMethodCount(Builder.class));
    assertEquals(
        Builder.class,
        Builder.class.getMethod("directExecutor").getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("executor", Executor.class).getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("addService", ServerServiceDefinition.class).getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("addService", BindableService.class).getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("fallbackHandlerRegistry", HandlerRegistry.class).getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("useTransportSecurity", File.class, File.class).getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("decompressorRegistry", DecompressorRegistry.class)
            .getReturnType());
    assertEquals(
        Builder.class,
        Builder.class.getMethod("compressorRegistry", CompressorRegistry.class).getReturnType());
    assertEquals(
        Server.class,
        Builder.class.getMethod("build").getReturnType());
  }
}
