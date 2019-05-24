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

package io.grpc;

import java.util.Comparator;

/**
 * The {@link NameResolverComparator} class compares two {@link NameResolverProvider} arguments
 * for order. Returns a negative integer, zero, or a positive integer as the first argument
 * is less than, equal to, or greater than the second.
 *
 * @author Manuel Kollus
 * @version 1.21.1
 */
public final class NameResolverComparator implements Comparator<NameResolverProvider> {

  @Override
  public int compare(
      NameResolverProvider nameResolverProvider,
      NameResolverProvider otherNameResolverProvider) {
    return nameResolverProvider.priority() - otherNameResolverProvider.priority();
  }
}
