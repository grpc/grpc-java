/*
 * Copyright 2021 The gRPC Authors
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Admin Interface provides a class of services for exposing the overall state of all gRPC
 * activity in a given binary. It aims to be a convenient API that automatically determines
 * which specific admin services are relevant.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7929")
@ThreadSafe
public final class AdminInterface {
  private static final int DEFAULT_CHANNELZ_MAX_PAGE_SIZE = 100;
  private static final Logger logger = Logger.getLogger(AdminInterface.class.getName());

  // Do not instantiate.
  private AdminInterface() {}

  /**
   * Returns a list of gRPC's built-in admin services.
   *
   * @return list of standard admin services
   */
  public static List<ServerServiceDefinition> getStandardServices() {
    List<ServerServiceDefinition> services = new ArrayList<>();
    ServerServiceDefinition channelz = loadService(
        "io.grpc.services.ChannelzService",
        new Class<?>[]{int.class}, new Object[]{DEFAULT_CHANNELZ_MAX_PAGE_SIZE});
    if (channelz != null) {
      services.add(channelz);
    }
    ServerServiceDefinition csds = loadService(
        "io.grpc.xds.CsdsService", new Class<?>[]{}, new Object[]{});
    if (csds != null) {
      services.add(csds);
    }
    return Collections.unmodifiableList(services);
  }

  @Nullable
  private static ServerServiceDefinition loadService(
      String name, Class<?>[] parameterTypes, Object[] args) {
    BindableService service = null;
    try {
      Class<?> clazz = Class.forName(name);
      Method m = clazz.getMethod("newInstance", parameterTypes);
      service = (BindableService) m.invoke(null, args);
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE, "Unable to find " + name, e);
    } catch (NoSuchMethodException e) {
      logger.log(Level.FINE, "Unable to load " + name, e);
    } catch (IllegalAccessException e) {
      logger.log(Level.FINE, "Unable to load " + name, e);
    } catch (InvocationTargetException e) {
      logger.log(Level.FINE, "Unable to load " + name, e);
    }
    if (service != null) {
      return service.bindService();
    }
    return null;
  }
}
