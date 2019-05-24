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
