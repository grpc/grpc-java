/*
 * Copyright 2024 The gRPC Authors
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Disables Animal Sniffer's signature checking. This is our own package-private version to avoid
 * dependening on animalsniffer-annotations.
 *
 * <p>FIELD is purposefully not supported, as Android wouldn't be able to ignore a field. Instead,
 * the entire class would need to be avoided on Android.
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@interface IgnoreJRERequirement {}
