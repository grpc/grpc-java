/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.stub.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates that the class is gRPC-generated code to assist
 * <a href="https://docs.oracle.com/javase/6/docs/api/javax/annotation/processing/Processor.html">
 * Java Annotation Processors.</a>
 *
 * <p>This annotation is used by the gRPC stub compiler to annotate outer classes. Users should not
 * annotate their own classes with this annotation. Not all stubs may have this annotation, so
 * consumers should not assume that it is present.
 *
 * @since 1.40.0
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GrpcGenerated {}
