package io.grpc;

/**
 * A provider class for the {@link ServiceProvidersTest}.
 */
// Nesting the class inside the test leads to a class name that has a '$' in it, which causes
// issues with our build pipeline.
public abstract class ServiceProvidersTestAbstractProvider {
  abstract boolean isAvailable();

  abstract int priority();
}
