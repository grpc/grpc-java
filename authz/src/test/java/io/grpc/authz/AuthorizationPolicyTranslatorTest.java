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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.Principal.Authenticated;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.type.matcher.v3.PathMatcher;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import java.io.IOException;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AuthorizationPolicyTranslatorTest {
  @Test
  public void invalidPolicy() throws Exception {
    String policy = "{ \"name\": \"abc\",, }";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IOException ioe) {
      assertThat(ioe).hasMessageThat().isEqualTo(
          "Use JsonReader.setLenient(true) to accept malformed JSON"
          + " at line 1 column 18 path $.name");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void missingAuthorizationPolicyName() throws Exception {
    String policy = "{}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("\"name\" is absent or empty");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void incorrectAuthorizationPolicyName() throws Exception {
    String policy = "{ \"name\": [\"abc\"] }";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (ClassCastException cce) {
      assertThat(cce).hasMessageThat().isEqualTo(
          "value '[abc]' for key 'name' in '{name=[abc]}' is not String");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void missingAllowRules() throws Exception {
    String policy = "{ \"name\": \"authz\" }";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("\"allow_rules\" is absent");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void missingRuleName() throws Exception {
    String policy = "{"
        + " \"name\" : \"abc\" ,"
        + " \"allow_rules\" : ["
        + "   {}"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("rule \"name\" is absent or empty");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void missingSourceAndRequest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\" : ["
        + "   {"
        + "     \"name\": \"allow_all\""
        + "   }"
        + " ]"
        + "}";
    List<RBAC> rbacs = AuthorizationPolicyTranslator.translate(policy);
    assertEquals(1, rbacs.size());
    RBAC expected_rbac = 
        RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putPolicies("authz_allow_all", 
            Policy.newBuilder()
            .addPrincipals(Principal.newBuilder().setAny(true))
            .addPermissions(Permission.newBuilder().setAny(true))
            .build())
        .build();
    assertEquals(expected_rbac, rbacs.get(0));
  }

  @Test
  public void emptySourceAndRequest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\" : ["
        + "   {"
        + "     \"name\": \"allow_all\","
        + "     \"source\": {},"
        + "     \"request\": {}"
        + "   }"
        + " ]"
        + "}";
    List<RBAC> rbacs = AuthorizationPolicyTranslator.translate(policy);
    assertEquals(1, rbacs.size());
    RBAC expected_rbac = 
        RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putPolicies("authz_allow_all", 
            Policy.newBuilder()
            .addPrincipals(Principal.newBuilder().setAny(true))
            .addPermissions(Permission.newBuilder().setAny(true))
            .build())
        .build();
    assertEquals(expected_rbac, rbacs.get(0));
  }

  @Test
  public void incorrectRulesType() throws Exception {
    String policy = "{"
        + " \"name\" : \"abc\" ,"
        + " \"allow_rules\" : {}"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (ClassCastException cce) {
      assertThat(cce).hasMessageThat().isEqualTo(
          "value '{}' for key 'allow_rules' in '{name=abc, allow_rules={}}' is not List");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void parseSourceSuccess() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_users\","
        + "     \"source\": {"
        + "       \"principals\": ["
        + "         \"spiffe://foo.com\","
        + "         \"spiffe://bar*\","
        + "         \"*baz\","
        + "         \"spiffe://*.com\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_any\","
        + "     \"source\": {"
        + "       \"principals\": ["
        + "         \"*\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    List<RBAC> rbacs = AuthorizationPolicyTranslator.translate(policy);
    assertEquals(2, rbacs.size());
    RBAC expected_deny_rbac = 
        RBAC.newBuilder()
        .setAction(Action.DENY)
        .putPolicies("authz_deny_users", 
            Policy.newBuilder()
            .addPrincipals(Principal.newBuilder()
                .setOrIds(
                    Principal.Set.newBuilder()
                    .addIds(Principal.newBuilder()
                        .setAuthenticated(Authenticated.newBuilder()
                            .setPrincipalName(StringMatcher.newBuilder()
                                .setExact("spiffe://foo.com").build()).build()).build())
                    .addIds(Principal.newBuilder()
                        .setAuthenticated(Authenticated.newBuilder()
                            .setPrincipalName(StringMatcher.newBuilder()
                                .setPrefix("spiffe://bar").build()).build()).build())
                    .addIds(Principal.newBuilder()
                        .setAuthenticated(Authenticated.newBuilder()
                            .setPrincipalName(StringMatcher.newBuilder()
                                .setSuffix("baz").build()).build()).build())
                    .addIds(Principal.newBuilder()
                        .setAuthenticated(Authenticated.newBuilder()
                            .setPrincipalName(StringMatcher.newBuilder()
                                .setExact("spiffe://*.com").build()).build()).build())
                    .build()).build())
            .addPermissions(Permission.newBuilder().setAny(true))
            .build()).build();
    RBAC expected_allow_rbac = 
        RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putPolicies("authz_allow_any", 
            Policy.newBuilder()
            .addPrincipals(Principal.newBuilder()
                .setOrIds(
                    Principal.Set.newBuilder()
                    .addIds(Principal.newBuilder()
                        .setAuthenticated(Authenticated.newBuilder()
                            .setPrincipalName(StringMatcher.newBuilder()
                                .setSafeRegex(RegexMatcher.newBuilder()
                                    .setRegex(".+").build()).build()).build())
                        .build())
                    .build()).build())
            .addPermissions(Permission.newBuilder().setAny(true))
            .build()).build();
    assertEquals(expected_deny_rbac, rbacs.get(0));
    assertEquals(expected_allow_rbac, rbacs.get(1));
  }

  @Test
  public void unsupportedPseudoHeaders() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_access\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \":method\","
        + "           \"values\": ["
        + "             \"foo\""
        + "           ]"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("Unsupported \"key\" :method");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void unsupportedGrpcPrefixHeaders() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_access\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"grpc-xxx\","
        + "           \"values\": ["
        + "             \"foo\""
        + "           ]"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("Unsupported \"key\" grpc-xxx");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void unsupportedHostHeaders() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_access\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"Host\","
        + "           \"values\": ["
        + "             \"foo\""
        + "           ]"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("Unsupported \"key\" Host");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void missingHeaderKey() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_dev\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {}"
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("\"key\" is absent or empty");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void missingHeaderValues() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_dev\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"dev-path\""
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("\"values\" is absent or empty");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void emptyHeaderValues() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_dev\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"dev-path\","
        + "           \"values\": []"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    try {
      AuthorizationPolicyTranslator.translate(policy);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("\"values\" is absent or empty");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void parseRequestSuccess() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_access\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"/pkg.service/foo\","
        + "         \"/pkg.service/bar*\""
        + "       ],"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"dev-path\","
        + "           \"values\": [\"/dev/path/*\"]"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_access1\","
        + "     \"request\": {"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"key-1\","
        + "           \"values\": ["
        + "             \"foo\","
        + "             \"*bar\""
        + "           ]"
        + "         },"
        + "         {"
        + "           \"key\": \"key-2\","
        + "           \"values\": ["
        + "             \"*\""
        + "           ]"
        + "         }"
        + "       ]"
        + "     }"
        + "   },"
        + "   {"
        + "     \"name\": \"allow_access2\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*baz\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    List<RBAC> rbacs = AuthorizationPolicyTranslator.translate(policy);
    assertEquals(2, rbacs.size());
    RBAC expected_deny_rbac = 
        RBAC.newBuilder()
        .setAction(Action.DENY)
        .putPolicies("authz_deny_access", 
            Policy.newBuilder()
            .addPermissions(Permission.newBuilder()
                .setAndRules(Permission.Set.newBuilder()
                    .addRules(Permission.newBuilder()
                        .setOrRules(Permission.Set.newBuilder()
                            .addRules(Permission.newBuilder()
                                .setUrlPath(PathMatcher.newBuilder()
                                    .setPath(StringMatcher.newBuilder()
                                        .setExact("/pkg.service/foo").build()).build()).build())
                            .addRules(Permission.newBuilder()
                                .setUrlPath(PathMatcher.newBuilder()
                                    .setPath(StringMatcher.newBuilder()
                                        .setPrefix("/pkg.service/bar").build()).build()).build())
                            .build()).build())
                    .addRules(Permission.newBuilder()
                        .setAndRules(Permission.Set.newBuilder()
                            .addRules(Permission.newBuilder()
                                .setOrRules(Permission.Set.newBuilder()
                                    .addRules(Permission.newBuilder()
                                        .setHeader(HeaderMatcher.newBuilder()
                                            .setName("dev-path")
                                            .setStringMatch(StringMatcher.newBuilder()
                                                .setPrefix("/dev/path/").build())
                                            .build())
                                        .build())
                                    .build()).build())
                            .build()).build())
                    .build()))
            .addPrincipals(Principal.newBuilder().setAny(true))
            .build()).build();
    RBAC expected_allow_rbac = 
        RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putPolicies("authz_allow_access1",
            Policy.newBuilder()
            .addPermissions(Permission.newBuilder()
                .setAndRules(Permission.Set.newBuilder()
                    .addRules(Permission.newBuilder()
                        .setAndRules(Permission.Set.newBuilder()
                            .addRules(Permission.newBuilder()
                                .setOrRules(Permission.Set.newBuilder()
                                    .addRules(Permission.newBuilder()
                                        .setHeader(HeaderMatcher.newBuilder()
                                            .setName("key-1")
                                            .setStringMatch(StringMatcher.newBuilder()
                                                .setExact("foo").build())
                                            .build())
                                        .build())
                                    .addRules(Permission.newBuilder()
                                        .setHeader(HeaderMatcher.newBuilder()
                                            .setName("key-1")
                                            .setStringMatch(StringMatcher.newBuilder()
                                                .setSuffix("bar").build())
                                            .build())
                                        .build())
                                    .build()).build())
                            .addRules(Permission.newBuilder()
                                .setOrRules(Permission.Set.newBuilder()
                                    .addRules(Permission.newBuilder()
                                        .setHeader(HeaderMatcher.newBuilder()
                                            .setName("key-2")
                                            .setStringMatch(StringMatcher.newBuilder()
                                                .setSafeRegex(RegexMatcher.newBuilder()
                                                    .setRegex(".+").build()).build())
                                            .build())
                                        .build())
                                    .build()).build()).build()).build()).build()))
            .addPrincipals(Principal.newBuilder().setAny(true))
            .build())
        .putPolicies("authz_allow_access2",
            Policy.newBuilder()
            .addPermissions(Permission.newBuilder()
                .setAndRules(Permission.Set.newBuilder()
                    .addRules(Permission.newBuilder()
                        .setOrRules(Permission.Set.newBuilder()
                            .addRules(Permission.newBuilder()
                                .setUrlPath(PathMatcher.newBuilder()
                                    .setPath(StringMatcher.newBuilder()
                                        .setSuffix("baz").build()).build()).build())
                            .build()).build())
                    .build()))
            .addPrincipals(Principal.newBuilder().setAny(true))
            .build())
        .build();
    assertEquals(expected_deny_rbac, rbacs.get(0));
    assertEquals(expected_allow_rbac, rbacs.get(1));
  }
}
