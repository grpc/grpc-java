package io.grpc;

import com.google.protobuf.MessageLiteOrBuilder;

interface RequestValidator<T extends MessageLiteOrBuilder> {
    ValidationResult isValid(T request);
}
