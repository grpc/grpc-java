/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utilities for working with {@link ChildChannelConfigurer}.
 *
 * @since 1.79.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/12574")
public final class ChildChannelConfigurers {
  private static final Logger logger = Logger.getLogger(ChildChannelConfigurers.class.getName());

  // Singleton no-op instance to avoid object churn
  private static final ChildChannelConfigurer NO_OP = builder -> {
  };

  private ChildChannelConfigurers() { // Prevent instantiation
  }

  /**
   * Returns a configurer that does nothing.
   * Useful as a default value to avoid null checks in internal code.
   */
  public static ChildChannelConfigurer noOp() {
    return NO_OP;
  }

  /**
   * Returns a configurer that applies all the given configurers in sequence.
   *
   * <p>If any configurer in the chain throws an exception, the remaining ones are skipped
   * (unless wrapped in {@link #safe(ChildChannelConfigurer)}).
   *
   * @param configurers the configurers to apply in order. Null elements are ignored.
   */
  public static ChildChannelConfigurer compose(ChildChannelConfigurer... configurers) {
    checkNotNull(configurers, "configurers");
    return builder -> {
      for (ChildChannelConfigurer configurer : configurers) {
        if (configurer != null) {
          configurer.accept(builder);
        }
      }
    };
  }

  /**
   * Returns a configurer that applies the delegate but catches and logs any exceptions.
   *
   * <p>This prevents a buggy configurer (e.g., one that fails metric setup) from crashing
   * the critical path of channel creation.
   *
   * @param delegate the configurer to wrap.
   */
  public static ChildChannelConfigurer safe(ChildChannelConfigurer delegate) {
    checkNotNull(delegate, "delegate");
    return builder -> {
      try {
        delegate.accept(builder);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to apply child channel configuration", e);
      }
    };
  }

  /**
   * Returns a configurer that applies the delegate only if the given condition is true.
   *
   * <p>Useful for applying interceptors only in specific environments (e.g., Debug/Test).
   *
   * @param condition true to apply the delegate, false to do nothing.
   * @param delegate the configurer to apply if condition is true.
   */
  public static ChildChannelConfigurer conditional(boolean condition,
                                                   ChildChannelConfigurer delegate) {
    checkNotNull(delegate, "delegate");
    return condition ? delegate : NO_OP;
  }
}