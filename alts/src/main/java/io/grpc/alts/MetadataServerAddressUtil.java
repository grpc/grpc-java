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

package io.grpc.alts;

/** Utility class for getting ALTS handshaker service address. */
final class MetadataServerAddressUtil {
  private static final String GCE_METADATA_HOST_ENV_VAR = "GCE_METADATA_HOST";
  private static final String DEFAULT_HANDSHAKER_ADDRESS = "metadata.google.internal.:8080";

  

  /**
   * Returns the ALTS handshaker service address by checking GCE_METADATA_HOST environment
   * variable. If it is not set, returns the default handshaker address.
   */
  static String getHandshakerAddress() {
    String address = System.getenv(GCE_METADATA_HOST_ENV_VAR);
    if (address == null) {
      return DEFAULT_HANDSHAKER_ADDRESS;
    }
    return address;
  }
}
