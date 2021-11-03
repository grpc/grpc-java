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

package io.grpc.authz;

import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.Principal.Authenticated;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.type.matcher.v3.PathMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * add.
 */
public class AuthorizationPolicyTranslator {

  private static StringMatcher getStringMatcher(String value) {
    if (value.equals("*")) {
      return StringMatcher.newBuilder().setPrefix("").build();
    } else if (value.startsWith("*")) {
      return StringMatcher.newBuilder().setSuffix(value.substring(1)).build();
    } else if (value.endsWith("*")) {
      return StringMatcher.newBuilder().setPrefix(value.substring(0, value.length() - 1)).build();
    }
    return StringMatcher.newBuilder().setExact(value).build();
  }

  private static Principal parseSource(Map<String, ?> source) {
    List<String> principalsList = JsonUtil.getListOfStrings(source, "principals");
    if (principalsList == null || (principalsList != null && principalsList.isEmpty())) {
      return Principal.newBuilder().setAny(true).build();
    }
    Principal.Set.Builder principalsSet = Principal.Set.newBuilder();
    for (String principal: principalsList) {           
      principalsSet.addIds(
          Principal.newBuilder().setAuthenticated(
            Authenticated.newBuilder().setPrincipalName(
              getStringMatcher(principal)).build()).build());
    }
    return Principal.newBuilder().setOrIds(principalsSet.build()).build();
  }

  private static Permission parseHeader(Map<String, ?> header) throws IllegalArgumentException {
    String key = JsonUtil.getString(header, "key");
    if (key == null) {
      throw new IllegalArgumentException("\"key\" is absent.");
    }
    List<String> valuesList = JsonUtil.getListOfStrings(header, "values");
    if (valuesList == null || (valuesList != null && valuesList.isEmpty())) {
      throw new IllegalArgumentException("\"values\" is absent or empty.");
    }
    Permission.Set.Builder orSet = Permission.Set.newBuilder();
    for (String value: valuesList) {
      orSet.addRules(
          Permission.newBuilder().setHeader(
            HeaderMatcher.newBuilder()
            .setName(key)
            .setStringMatch(getStringMatcher(value)).build()).build());     
    }
    return Permission.newBuilder().setOrRules(orSet.build()).build();
  }

  private static Permission parseRequest(Map<String, ?> request) throws IllegalArgumentException {
    Permission.Set.Builder andSet = Permission.Set.newBuilder();
    List<String> pathsList = JsonUtil.getListOfStrings(request, "paths"); 
    if (pathsList != null) {
      Permission.Set.Builder pathsSet = Permission.Set.newBuilder();
      for (String path: pathsList) {           
        pathsSet.addRules(
            Permission.newBuilder().setUrlPath(
              PathMatcher.newBuilder().setPath(
                getStringMatcher(path)).build()).build());
      }
      if (pathsSet.getRulesCount() > 0) {
        andSet.addRules(Permission.newBuilder().setOrRules(pathsSet.build()).build());
      }
    }
    List<Map<String, ?>> headersList = JsonUtil.getListOfObjects(request, "headers"); 
    if (headersList != null) {
      Permission.Set.Builder headersSet = Permission.Set.newBuilder();
      for (Map<String, ?> header: headersList) {           
        headersSet.addRules(parseHeader(header));
      }
      if (headersSet.getRulesCount() > 0) {
        andSet.addRules(Permission.newBuilder().setAndRules(headersSet.build()).build());
      }
    }
    if (andSet.getRulesCount() == 0) {
      return Permission.newBuilder().setAny(true).build();
    }
    return Permission.newBuilder().setAndRules(andSet.build()).build();
  }

  private static Map<String, Policy> parseRules(
      List<Map<String, ?>> objects, String name) throws IllegalArgumentException {
    Map<String, Policy> policies = new LinkedHashMap<String, Policy>();
    for (Map<String, ?> object: objects) {
      String policyName = JsonUtil.getString(object, "name");
      if (policyName == null) {
        throw new IllegalArgumentException("'name' is absent.");
      }
      List<Principal> principals = new ArrayList<>();
      Map<String, ?> source = JsonUtil.getObject(object, "source");
      if (source != null) {
        principals.add(parseSource(source));
      } else {
        principals.add(Principal.newBuilder().setAny(true).build());
      }
      List<Permission> permissions = new ArrayList<>();
      Map<String, ?> request = JsonUtil.getObject(object, "request");
      if (request != null) {
        permissions.add(parseRequest(request));
      } else {
        permissions.add(Permission.newBuilder().setAny(true).build());
      }
      Policy policy = 
          Policy.newBuilder()
          .addAllPermissions(permissions)
          .addAllPrincipals(principals)
          .build();
      policies.put(name + "_" + policyName, policy);
    }
    return policies;
  }


  /** 
   * Translate a gRPC authorization policy in JSON string to two RBAC policies, a 
   * deny RBAC policy followed by an allow RBAC policy. If the policy cannot be
   * parsed or is invalid, an IllegalArgumentException will be thrown.
   */
  @SuppressWarnings("unchecked")
  public static List<RBAC> translate(String authorizationPolicy) 
  throws IllegalArgumentException, IOException {
    Map<String, ?> json = (Map<String, ?>)JsonParser.parse(authorizationPolicy);
    String name = JsonUtil.getString(json, "name");
    if (name == null) {
      throw new IllegalArgumentException("'name' is absent.");
    }
    List<RBAC> rbacs = new ArrayList<>();
    List<Map<String, ?>> objects = JsonUtil.getListOfObjects(json, "deny_rules");
    if (objects != null) {
      rbacs.add(
          RBAC.newBuilder()
          .setAction(Action.DENY)
          .putAllPolicies(parseRules(objects, name))
          .build());
    }
    objects = JsonUtil.getListOfObjects(json, "allow_rules");
    if (objects == null) {
      throw new IllegalArgumentException("'allow_rules' is absent.");
    }
    rbacs.add(
        RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putAllPolicies(parseRules(objects, name))
        .build());
    /*
    for (Map.Entry<String, ?> object: json.entrySet()) {
      if (object.getKey().equals("name")) {
        continue;
      } else if (object.getKey().equals("allow_rules")) {
        List<Object> objects = JsonUtil.getListOfObjects(object, "allow_rules");
        if (objects == null){
          throw new IllegalArgumentException("\"allow_rules\" is absent or has invalid type.");
        }
      } else if (object.getKey().equals("deny_rules")) {
        //...
      } else {
        throw new IllegalArgumentException("policy contains unknown field: " + object.getKey());
      }
    }*/
    return rbacs;
  }
}