package io.grpc.loadbalancers;

import io.grpc.Attributes;
import io.grpc.ExperimentalApi;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;

/**
 * Labels associated with a {@link ResolvedServerAddress}.  Labels are passed from the NameResolver
 * to the LoadBalancer in the attribute Labels.ATTRIBUTE.  Labels are modeled as key value
 * pairs where a label key can be associated with multiple values.
 * 
 * @see AffinityLoadBalancer
 */
@ExperimentalApi
public final class Labels {

  public static final Attributes.Key<Labels> ATTRIBUTE = new Attributes.Key<Labels>("io.grpc.NameResolver.Labels");
  
  public static class Builder {
    private Map<String, Set<String>> labels = new LinkedHashMap<>();

    public Builder add(String key, String value) {
      Preconditions.checkNotNull(labels, "build() already called");
      Set<String> values = labels.get(key);
      if (values == null) {
        values = new LinkedHashSet<>();
        labels.put(key, values);
      }
      values.add(value);
      return this;
    }

    public Labels build() {
      Preconditions.checkNotNull(labels, "build() already called");
      try {
        return new Labels(labels);
      } finally {
        labels = null;
      }
    }
  }

  private final Map<String, Set<String>> labels;

  private Labels(Map<String, Set<String>> labels) {
    this.labels = labels;
  }
  
  public static Builder newBuilder() {
    return new Builder();
  }

  public boolean matches(String key, String value) {
    Set<String> values = labels.get(key);
    return values == null ? false : values.contains(value);
  }

  @Override
  public String toString() {
    return "Labels [" + labels + "]";
  }

}
