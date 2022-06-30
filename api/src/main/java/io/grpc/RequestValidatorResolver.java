/*
 * Copyright 2016 The gRPC Authors
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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RequestValidatorResolver {

    private Map<Class<?>, RequestValidator<?>> validatorMap;

    public RequestValidatorResolver(List<RequestValidator<?>> validators) {
        validatorMap = validators.stream()
                .collect(Collectors.toMap(RequestValidator::getTargetClass, it -> it));
    }

    public RequestValidator<?> find(Class<?> targetClass) {
        return validatorMap.get(targetClass);
    }
}

