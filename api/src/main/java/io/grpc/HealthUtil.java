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

  public static final LoadBalancer.CreateSubchannelArgs.Key<SubchannelHealthListener>
      HEALTH_LISTENER_ARG_KEY =
      LoadBalancer.CreateSubchannelArgs.Key.create("health-check-listener");

  public interface SubchannelHealthListener {

    void onHealthStatus(HealthStatus healthStatus);

    int getGeneration();
  }

  /**
   * Used by a health producer system to construct the subchannel health notification chain and
   * notify aggregated health status with cached health status.
   * 1. At subchannel creation time, a health producer system should construct a
   * ChainedHealthListener and provide to parent health producer's createSubchannelArgs. The parent
   * health producer system then will call {@link #upperStreamHealthStatus} to notify health status
   * change, and {@link #thisHealthStatus} is used for the current child health producer.
   * 2. In the health producer's runtime, this health producer system should call
   * {@link #thisHealthStatus} to notify health status change of its own health status change.
   * */
  public static final class ChainedHealthListener implements SubchannelHealthListener {

    private HealthStatus upperStreamHealthStatus = HealthStatus.create(ServingStatus.UNKNOWN,
        "upstream initial unknown Health Status");
    private HealthStatus thisHealthStatus;
    private final String healthProducerName;
    private SubchannelHealthListener delegate;
    private final int generation;

    public ChainedHealthListener(SubchannelHealthListener delegate,
                                 HealthStatus initialHealthStatus,
                                 String thisHealthProducerName) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.thisHealthStatus = checkNotNull(initialHealthStatus, "initialHealthStatus");
      this.healthProducerName = checkNotNull(thisHealthProducerName, "thisHealthProducerName");
      this.generation = delegate.getGeneration() + 1;
    }

    @Override
    public void onHealthStatus(HealthUtil.HealthStatus healthStatus) {
      upperStreamHealthStatus = healthStatus;
      notifyHealth();
    }

    @Override
    public int getGeneration() {
      return generation;
    }

    public void thisHealthStatus(HealthUtil.HealthStatus healthStatus) {
      thisHealthStatus = healthStatus;
      notifyHealth();
    }

    private void notifyHealth() {
      ServingStatus aggregatedStatus;
      if (ServingStatus.SERVING == upperStreamHealthStatus.servingStatus
          && ServingStatus.SERVING == thisHealthStatus.servingStatus) {
        aggregatedStatus = ServingStatus.SERVING;
      } else {
        aggregatedStatus = ServingStatus.NOT_SERVING;
      }
      delegate.onHealthStatus(HealthStatus.create(aggregatedStatus, String.format(
          "%sHealth producer [%s] information: %s",
          upperStreamHealthStatus.description, healthProducerName,
          thisHealthStatus.description.equals("") ? "None" : thisHealthStatus.description)));
    }
  }
}
