package io.grpc;

@Internal
public interface InternalMayRequireSpecificExecutor {
    boolean isSpecificExecutorRequired();
}
