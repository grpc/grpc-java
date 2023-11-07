/*
 * Copyright 2023 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;

public class HealthUtil {
  public enum ServingStatus {
    UNKNOWN,
    SERVING,
    NOT_SERVING,
    SERVICE_UNKNOWN;
  }

  public static class HealthStatus {
    private final ServingStatus servingStatus;
    private String description;

    private HealthStatus(ServingStatus servingStatus, String description) {
      this.servingStatus = checkNotNull(servingStatus, "servingStatus");
      this.description = checkNotNull(description, "description");
    }

    public static HealthStatus create(ServingStatus status, String description) {
      return new HealthStatus(status, description);
    }

    public static HealthStatus create(ServingStatus status) {
      return create(status, "");
    }

    public HealthStatus(ServingStatus servingStatus) {
      this.servingStatus = servingStatus;
    }

    public ServingStatus servingStatus() {
      return servingStatus;
    }

    public String description() {
      return description;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("servingStatus", servingStatus.name())
          .add("description", description)
          .toString();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof HealthStatus)) {
        return false;
      }
      HealthStatus o = (HealthStatus) other;
      return servingStatus.equals(o.servingStatus) && description.equals(o.description);
    }

    @Override
    public int hashCode() {
      return servingStatus.hashCode() ^ description.hashCode();
    }
  }

  public static final LoadBalancer.CreateSubchannelArgs.Key<LoadBalancer.SubchannelStateListener>
      HEALTH_CONSUMER_LISTENER_ARG_KEY =
      LoadBalancer.CreateSubchannelArgs.Key.create("health-check-consumer-listener");

}
