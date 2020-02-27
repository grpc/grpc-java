/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.VerifyException;
import io.grpc.Status;
import io.grpc.internal.RetriableStream.Throttle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Helper utility to work with service configs.
 *
 * <p>This class contains helper methods to parse service config JSON values into Java types.
 */
public final class ServiceConfigUtil {

  private ServiceConfigUtil() {}

  /**
   * Fetch the health-checked service name from service config. {@code null} if can't find one.
   */
  @Nullable
  public static String getHealthCheckedServiceName(@Nullable Map<String, ?> serviceConfig) {
    if (serviceConfig == null) {
      return null;
    }

    /* schema as follows
    {
      "healthCheckConfig": {
        // Service name to use in the health-checking request.
        "serviceName": string
      }
    }
    */
    Map<String, ?> healthCheck = JsonUtil.getObject(serviceConfig, "healthCheckConfig");
    if (healthCheck == null) {
      return null;
    }
    return JsonUtil.getString(healthCheck, "serviceName");
  }

  @Nullable
  static Throttle getThrottlePolicy(@Nullable Map<String, ?> serviceConfig) {
    if (serviceConfig == null) {
      return null;
    }

    /* schema as follows
    {
      "retryThrottling": {
        // The number of tokens starts at maxTokens. The token_count will always be
        // between 0 and maxTokens.
        //
        // This field is required and must be greater than zero.
        "maxTokens": number,

        // The amount of tokens to add on each successful RPC. Typically this will
        // be some number between 0 and 1, e.g., 0.1.
        //
        // This field is required and must be greater than zero. Up to 3 decimal
        // places are supported.
        "tokenRatio": number
      }
    }
    */

    Map<String, ?> throttling = JsonUtil.getObject(serviceConfig, "retryThrottling");
    if (throttling == null) {
      return null;
    }

    // TODO(dapengzhang0): check if this is null.
    float maxTokens = JsonUtil.getNumber(throttling, "maxTokens").floatValue();
    float tokenRatio = JsonUtil.getNumber(throttling, "tokenRatio").floatValue();
    checkState(maxTokens > 0f, "maxToken should be greater than zero");
    checkState(tokenRatio > 0f, "tokenRatio should be greater than zero");
    return new Throttle(maxTokens, tokenRatio);
  }

  @Nullable
  static Integer getMaxAttemptsFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getNumberAsInteger(retryPolicy, "maxAttempts");
  }

  @Nullable
  static Long getInitialBackoffNanosFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getStringAsDuration(retryPolicy, "initialBackoff");
  }

  @Nullable
  static Long getMaxBackoffNanosFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getStringAsDuration(retryPolicy, "maxBackoff");
  }

  @Nullable
  static Double getBackoffMultiplierFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getNumber(retryPolicy, "backoffMultiplier");
  }

  private static Set<Status.Code> getListOfStatusCodesAsSet(Map<String, ?> obj, String key) {
    List<?> statuses = JsonUtil.getList(obj, key);
    if (statuses == null) {
      return null;
    }
    return getStatusCodesFromList(statuses);
  }

  private static Set<Status.Code> getStatusCodesFromList(List<?> statuses) {
    EnumSet<Status.Code> codes = EnumSet.noneOf(Status.Code.class);
    for (Object status : statuses) {
      Status.Code code;
      if (status instanceof Double) {
        Double statusD = (Double) status;
        int codeValue = statusD.intValue();
        verify((double) codeValue == statusD, "Status code %s is not integral", status);
        code = Status.fromCodeValue(codeValue).getCode();
        verify(code.value() == statusD.intValue(), "Status code %s is not valid", status);
      } else if (status instanceof String) {
        try {
          code = Status.Code.valueOf((String) status);
        } catch (IllegalArgumentException iae) {
          throw new VerifyException("Status code " + status + " is not valid", iae);
        }
      } else {
        throw new VerifyException(
            "Can not convert status code " + status + " to Status.Code, because its type is "
                + status.getClass());
      }
      codes.add(code);
    }
    return Collections.unmodifiableSet(codes);
  }

  static Set<Status.Code> getRetryableStatusCodesFromRetryPolicy(Map<String, ?> retryPolicy) {
    String retryableStatusCodesKey = "retryableStatusCodes";
    Set<Status.Code> codes = getListOfStatusCodesAsSet(retryPolicy, retryableStatusCodesKey);
    verify(codes != null, "%s is required in retry policy", retryableStatusCodesKey);
    verify(!codes.isEmpty(), "%s must not be empty", retryableStatusCodesKey);
    verify(!codes.contains(Status.Code.OK), "%s must not contain OK", retryableStatusCodesKey);
    return codes;
  }

  @Nullable
  static Integer getMaxAttemptsFromHedgingPolicy(Map<String, ?> hedgingPolicy) {
    return JsonUtil.getNumberAsInteger(hedgingPolicy, "maxAttempts");
  }

  @Nullable
  static Long getHedgingDelayNanosFromHedgingPolicy(Map<String, ?> hedgingPolicy) {
    return JsonUtil.getStringAsDuration(hedgingPolicy, "hedgingDelay");
  }

  static Set<Status.Code> getNonFatalStatusCodesFromHedgingPolicy(Map<String, ?> hedgingPolicy) {
    String nonFatalStatusCodesKey = "nonFatalStatusCodes";
    Set<Status.Code> codes = getListOfStatusCodesAsSet(hedgingPolicy, nonFatalStatusCodesKey);
    if (codes == null) {
      return Collections.unmodifiableSet(EnumSet.noneOf(Status.Code.class));
    }
    verify(!codes.contains(Status.Code.OK), "%s must not contain OK", nonFatalStatusCodesKey);
    return codes;
  }

  @Nullable
  static String getServiceFromName(Map<String, ?> name) {
    return JsonUtil.getString(name, "service");
  }

  @Nullable
  static String getMethodFromName(Map<String, ?> name) {
    return JsonUtil.getString(name, "method");
  }

  @Nullable
  static Map<String, ?> getRetryPolicyFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getObject(methodConfig, "retryPolicy");
  }

  @Nullable
  static Map<String, ?> getHedgingPolicyFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getObject(methodConfig, "hedgingPolicy");
  }

  @Nullable
  static List<Map<String, ?>> getNameListFromMethodConfig(
      Map<String, ?> methodConfig) {
    return JsonUtil.getListOfObjects(methodConfig, "name");
  }

  /**
   * Returns the number of nanoseconds of timeout for the given method config.
   *
   * @return duration nanoseconds, or {@code null} if it isn't present.
   */
  @Nullable
  static Long getTimeoutFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getStringAsDuration(methodConfig, "timeout");
  }

  @Nullable
  static Boolean getWaitForReadyFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getBoolean(methodConfig, "waitForReady");
  }

  @Nullable
  static Integer getMaxRequestMessageBytesFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getNumberAsInteger(methodConfig, "maxRequestMessageBytes");
  }

  @Nullable
  static Integer getMaxResponseMessageBytesFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getNumberAsInteger(methodConfig, "maxResponseMessageBytes");
  }

  @Nullable
  static List<Map<String, ?>> getMethodConfigFromServiceConfig(
      Map<String, ?> serviceConfig) {
    return JsonUtil.getListOfObjects(serviceConfig, "methodConfig");
  }

  /**
   * Extracts load balancing configs from a service config.
   */
  @VisibleForTesting
  public static List<Map<String, ?>> getLoadBalancingConfigsFromServiceConfig(
      Map<String, ?> serviceConfig) {
    /* schema as follows
    {
      "loadBalancingConfig": [
        {"xds" :
          {
            "childPolicy": [...],
            "fallbackPolicy": [...],
          }
        },
        {"round_robin": {}}
      ],
      "loadBalancingPolicy": "ROUND_ROBIN"  // The deprecated policy key
    }
    */
    List<Map<String, ?>> lbConfigs = new ArrayList<>();
    String loadBalancingConfigKey = "loadBalancingConfig";
    if (serviceConfig.containsKey(loadBalancingConfigKey)) {
      lbConfigs.addAll(JsonUtil.getListOfObjects(
          serviceConfig, loadBalancingConfigKey));
    }
    if (lbConfigs.isEmpty()) {
      // No LoadBalancingConfig found.  Fall back to the deprecated LoadBalancingPolicy
      String policy = JsonUtil.getString(serviceConfig, "loadBalancingPolicy");
      if (policy != null) {
        // Convert the policy to a config, so that the caller can handle them in the same way.
        policy = policy.toLowerCase(Locale.ROOT);
        Map<String, ?> fakeConfig = Collections.singletonMap(policy, Collections.emptyMap());
        lbConfigs.add(fakeConfig);
      }
    }
    return Collections.unmodifiableList(lbConfigs);
  }

  /**
   * Unwrap a LoadBalancingConfig JSON object into a {@link LbConfig}.  The input is a JSON object
   * (map) with exactly one entry, where the key is the policy name and the value is a config object
   * for that policy.
   */
  public static LbConfig unwrapLoadBalancingConfig(Map<String, ?> lbConfig) {
    if (lbConfig.size() != 1) {
      throw new RuntimeException(
          "There are " + lbConfig.size() + " fields in a LoadBalancingConfig object. Exactly one"
          + " is expected. Config=" + lbConfig);
    }
    String key = lbConfig.entrySet().iterator().next().getKey();
    return new LbConfig(key, JsonUtil.getObject(lbConfig, key));
  }

  /**
   * Given a JSON list of LoadBalancingConfigs, and convert it into a list of LbConfig.
   */
  public static List<LbConfig> unwrapLoadBalancingConfigList(List<Map<String, ?>> list) {
    if (list == null) {
      return null;
    }
    ArrayList<LbConfig> result = new ArrayList<>();
    for (Map<String, ?> rawChildPolicy : list) {
      result.add(unwrapLoadBalancingConfig(rawChildPolicy));
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * A LoadBalancingConfig that includes the policy name (the key) and its raw config value (parsed
   * JSON).
   */
  public static final class LbConfig {
    private final String policyName;
    private final Map<String, ?> rawConfigValue;

    public LbConfig(String policyName, Map<String, ?> rawConfigValue) {
      this.policyName = checkNotNull(policyName, "policyName");
      this.rawConfigValue = checkNotNull(rawConfigValue, "rawConfigValue");
    }

    public String getPolicyName() {
      return policyName;
    }

    public Map<String, ?> getRawConfigValue() {
      return rawConfigValue;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof LbConfig) {
        LbConfig other = (LbConfig) o;
        return policyName.equals(other.policyName)
            && rawConfigValue.equals(other.rawConfigValue);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(policyName, rawConfigValue);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("policyName", policyName)
          .add("rawConfigValue", rawConfigValue)
          .toString();
    }
  }
}
