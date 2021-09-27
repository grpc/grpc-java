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

package io.grpc.servlet.jetty;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Response;


/**
 * Helper to optionally wrap Jetty9 HttpServletResponse instances to enable their
 * setTrailerFields implementation to delegate to setTrailers.
 *
 * <p>This class needs to be compiled with Jetty9+ on the classpath, but the static
 * {@link JettyHttpServletResponse#wrap} method is safe to use without jetty being
 * present at runtime.</p>
 */
public class JettyHttpServletResponse extends HttpServletResponseWrapper {

  /**
   * Helper to deal with Jetty 9.4.x not supporting the standard implementation
   * of trailers - this will return an HttpServletResponse instance that can safely
   * be used during the rest of this response.
   *
   * @param response the response object provided by the servlet container
   * @return a response object that will be able to correctly handle Servlet 4.0
   *     trailers.
   */
  public static HttpServletResponse wrap(HttpServletResponse response) {
    if (!response.getClass().getName().equals("org.eclipse.jetty.server.Response")) {
      // If this isn't from jetty, assume it works as expected and use it
      return response;
    } else {
      Response r = (Response) response;
      // Since this is from Jetty, check if we must use our own response wrapper
      // to let setTrailerFields work properly
      Supplier<HttpFields> existing = r.getTrailers();
      r.setTrailerFields(Collections::emptyMap);
      if (existing == r.getTrailers()) {
        // Response#_trailers wasn't changed, we need to wrap
        return new JettyHttpServletResponse(r);
      }

      // restore the old value, setTrailerFields is functional
      r.setTrailers(existing);

      // return the instance as-is
      return response;
    }
  }

  private final Response resp;

  private JettyHttpServletResponse(Response resp) {
    super(resp);
    this.resp = resp;
  }

  @Override
  public void setTrailerFields(Supplier<Map<String, String>> supplier) {
    resp.setTrailers(null);
    super.setTrailerFields(supplier);
    if (resp.getTrailers() != null) {
      return;
    }
    resp.setTrailers(() -> {
      Map<String, String> map = supplier.get();
      if (map == null) {
        return null;
      }
      HttpFields fields = new HttpFields();
      for (Map.Entry<String, String> entry : map.entrySet()) {
        fields.add(entry.getKey(), entry.getValue());
      }
      return fields;
    });
  }

  @Override
  public Supplier<Map<String, String>> getTrailerFields() {
    return () -> resp.getTrailers().get().stream()
            .collect(Collectors.toMap(HttpField::getName, HttpField::getValue));
  }
}
