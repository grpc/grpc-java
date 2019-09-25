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

package io.grpc.xds.sds;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Internal;

/**
 * A SecretProvider is a "container" or provider of a secret.
 * This is used by gRPC-xds to access secrets and not part of the
 * public API of gRPC
 * See {@link SecretManager} for a note on lifecycle management
 */
@Internal
public interface SecretProvider<T> extends ListenableFuture<T> {
}
