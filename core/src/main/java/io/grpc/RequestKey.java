/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * A key generated from an RPC request, and to be used for affinity-based
 * routing.  For affinity-based routing the RequestKey contains a map of 
 * key-value pairs to be used for equality matching against labels associated
 * with servers.
 */
@ExperimentalApi
public class RequestKey {
  final public static RequestKey NONE = new RequestKey();
  
  private Map<String, String> affinities = new LinkedHashMap<String, String>();
  private boolean additive = true;

  // TODO(zhangkun83): materialize this class once we decide the form of the
  // affinity key.
  @VisibleForTesting
  RequestKey() {
  }

  public RequestKey extendWith(RequestKey key) {
    RequestKey newKey = new RequestKey();
    newKey.affinities.putAll(this.affinities);
    newKey.affinities.putAll(key.affinities);
    return newKey;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public final static class Builder {
    RequestKey requestKey = new RequestKey();

    public Builder affinityTo(String key, String value) {
      Preconditions.checkNotNull(key, "key must not be null");
      Preconditions.checkNotNull(key, "value must not be null");
      Preconditions.checkNotNull(requestKey, "build() already called");
      requestKey.affinities.put(key, value);
      return this;
    }

    public Builder additive(boolean flag) {
      requestKey.additive = flag;
      return this;
    }

    public RequestKey build() {
      Preconditions.checkNotNull(requestKey, "build() already called");
      try {
        return requestKey;
      } finally {
        requestKey = null;
      }
    }
  }

  public Map<String, String> getAffinities() {
    return Collections.unmodifiableMap(affinities);
  }

  /**
   * @return True if this RequestKey's affinity is to be applied on top of the default affinity or
   *         false to be used as the entire affinity for the request
   */
  public boolean isAdditive() {
    return this.additive;
  }

  public boolean hasAffinity() {
    return !affinities.isEmpty();
  }

  @Override
  public String toString() {
    return "RequestKey [tags=" + affinities + "]";
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + (additive ? 1231 : 1237);
    result = prime * result
        + ((affinities == null) ? 0 : affinities.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    RequestKey other = (RequestKey) obj;
    if (additive != other.additive)
      return false;
    if (affinities == null) {
      if (other.affinities != null)
        return false;
    } else if (!affinities.equals(other.affinities))
      return false;
    return true;
  }
}
