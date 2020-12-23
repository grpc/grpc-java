/*
 * Copyright 2020 The gRPC Authors
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

/**
 * Represents a security configuration to be used for servers. There is no generic mechanism for
 * processing arbitrary {@code ServerCredentials}; the consumer of the credential (the server)
 * must support each implementation explicitly and separately. Consumers are not required to support
 * all types or even all possible configurations for types that are partially supported, but they
 * <em>must</em> at least fully support {@link ChoiceServerCredentials}.
 *
 * <p>A {@code ServerCredential} provides server identity. They can also influence types of
 * encryption used and similar security configuration.
 *
 * <p>The concrete credential type should not be relevant to most users of the API and may be an
 * implementation decision. Users should generally use the {@code ServerCredentials} type for
 * variables instead of the concrete type. Freshly-constructed credentials should be returned as
 * {@code ServerCredentials} instead of a concrete type to encourage this pattern. Concrete types
 * would only be used after {@code instanceof} checks (which must consider
 * {@code ChoiceServerCredentials}!).
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
public abstract class ServerCredentials {}
