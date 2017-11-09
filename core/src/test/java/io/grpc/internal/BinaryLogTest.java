/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.internal.BinaryLog.Factory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BinaryLog}. */
@RunWith(JUnit4.class)
public final class BinaryLogTest {
  private static final BinaryLog NONE = new Builder().build();
  private static final BinaryLog HEADER_FULL = new Builder().header(Integer.MAX_VALUE).build();
  private static final BinaryLog HEADER_256 = new Builder().header(256).build();
  private static final BinaryLog MSG_FULL = new Builder().msg(Integer.MAX_VALUE).build();
  private static final BinaryLog MSG_256 = new Builder().msg(256).build();
  private static final BinaryLog BOTH_256 = new Builder().header(256).msg(256).build();
  private static final BinaryLog BOTH_FULL =
      new Builder().header(Integer.MAX_VALUE).msg(Integer.MAX_VALUE).build();
  

  @Test
  public void noConfiguration() throws Exception {
    Factory factory = new Factory(null);
    assertEquals(NONE, factory.getLog("p.s/m"));
    assertEquals(NONE, factory.getLog("/p.s/m"));
  }

  @Test
  public void configBinLog_global() throws Exception {
    assertEquals(BOTH_FULL, new Factory("*").getLog("p.s/m"));
    assertEquals(BOTH_FULL, new Factory("*{h;m}").getLog("p.s/m"));
    assertEquals(HEADER_FULL, new Factory("*{h}").getLog("p.s/m"));
    assertEquals(MSG_FULL, new Factory("*{m}").getLog("p.s/m"));
    assertEquals(HEADER_256, new Factory("*{h:256}").getLog("p.s/m"));
    assertEquals(MSG_256, new Factory("*{m:256}").getLog("p.s/m"));
    assertEquals(BOTH_256, new Factory("*{h:256;m:256}").getLog("p.s/m"));
    assertEquals(
        new Builder().header(Integer.MAX_VALUE).msg(256).build(),
        new Factory("*{h;m:256}").getLog("p.s/m"));
    assertEquals(
        new Builder().header(256).msg(Integer.MAX_VALUE).build(),
        new Factory("*{h:256;m}").getLog("p.s/m"));
  }

  @Test
  public void configBinLog_method() throws Exception {
    assertEquals(BOTH_FULL, new Factory("p.s/m").getLog("p.s/m"));
    assertEquals(BOTH_FULL, new Factory("p.s/m{h;m}").getLog("p.s/m"));
    assertEquals(HEADER_FULL, new Factory("p.s/m{h}").getLog("p.s/m"));
    assertEquals(MSG_FULL, new Factory("p.s/m{m}").getLog("p.s/m"));
    assertEquals(HEADER_256, new Factory("p.s/m{h:256}").getLog("p.s/m"));
    assertEquals(MSG_256, new Factory("p.s/m{m:256}").getLog("p.s/m"));
    assertEquals(BOTH_256, new Factory("p.s/m{h:256;m:256}").getLog("p.s/m"));
    assertEquals(
        new Builder().header(Integer.MAX_VALUE).msg(256).build(),
        new Factory("p.s/m{h;m:256}").getLog("p.s/m"));
    assertEquals(
        new Builder().header(256).msg(Integer.MAX_VALUE).build(),
        new Factory("p.s/m{h:256;m}").getLog("p.s/m"));
  }

  @Test
  public void configBinLog_method_absent() throws Exception {
    assertEquals(NONE, new Factory("p.s/m").getLog("p.s/absent"));
  }

  @Test
  public void configBinLog_service() throws Exception {
    assertEquals(BOTH_FULL, new Factory("p.s/*").getLog("p.s/m"));
    assertEquals(BOTH_FULL, new Factory("p.s/*{h;m}").getLog("p.s/m"));
    assertEquals(HEADER_FULL, new Factory("p.s/*{h}").getLog("p.s/m"));
    assertEquals(MSG_FULL, new Factory("p.s/*{m}").getLog("p.s/m"));
    assertEquals(HEADER_256, new Factory("p.s/*{h:256}").getLog("p.s/m"));
    assertEquals(MSG_256, new Factory("p.s/*{m:256}").getLog("p.s/m"));
    assertEquals(BOTH_256, new Factory("p.s/*{h:256;m:256}").getLog("p.s/m"));
    assertEquals(
        new Builder().header(Integer.MAX_VALUE).msg(256).build(),
        new Factory("p.s/*{h;m:256}").getLog("p.s/m"));
    assertEquals(
        new Builder().header(256).msg(Integer.MAX_VALUE).build(),
        new Factory("p.s/*{h:256;m}").getLog("p.s/m"));
  }

  @Test
  public void configBinLog_service_absent() throws Exception {
    assertEquals(NONE, new Factory("p.s/*").getLog("p.other/m"));
  }

  @Test
  public void createLogFromOptionString() throws Exception {
    assertEquals(BOTH_FULL, Factory.createBinaryLog(/*logConfig=*/ null));
    assertEquals(HEADER_FULL, Factory.createBinaryLog("{h}"));
    assertEquals(MSG_FULL, Factory.createBinaryLog("{m}"));
    assertEquals(HEADER_256, Factory.createBinaryLog("{h:256}"));
    assertEquals(MSG_256, Factory.createBinaryLog("{m:256}"));
    assertEquals(BOTH_256, Factory.createBinaryLog("{h:256;m:256}"));
    assertEquals(
        new Builder().header(Integer.MAX_VALUE).msg(256).build(),
        Factory.createBinaryLog("{h;m:256}"));
    assertEquals(
        new Builder().header(256).msg(Integer.MAX_VALUE).build(),
        Factory.createBinaryLog("{h:256;m}"));
  }

  @Test
  public void configBinLog_multiConfig_withGlobal() throws Exception {
    Factory factory = new Factory(
        "*{h},"
        + "package.both256/*{h:256;m:256},"
        + "package.service1/both128{h:128;m:128},"
        + "package.service2/method_messageOnly{m}");
    assertEquals(HEADER_FULL, factory.getLog("otherpackage.service/method"));

    assertEquals(BOTH_256, factory.getLog("package.both256/method1"));
    assertEquals(BOTH_256, factory.getLog("package.both256/method2"));
    assertEquals(BOTH_256, factory.getLog("package.both256/method3"));

    assertEquals(
        new Builder().header(128).msg(128).build(), factory.getLog("package.service1/both128"));
    // the global config is in effect
    assertEquals(HEADER_FULL, factory.getLog("package.service1/absent"));

    assertEquals(MSG_FULL, factory.getLog("package.service2/method_messageOnly"));
    // the global config is in effect
    assertEquals(HEADER_FULL, factory.getLog("package.service2/absent"));
  }

  @Test
  public void configBinLog_multiConfig_noGlobal() throws Exception {
    Factory factory = new Factory(
        "package.both256/*{h:256;m:256},"
        + "package.service1/both128{h:128;m:128},"
        + "package.service2/method_messageOnly{m}");
    assertEquals(NONE, factory.getLog("otherpackage.service/method"));

    assertEquals(BOTH_256, factory.getLog("package.both256/method1"));
    assertEquals(BOTH_256, factory.getLog("package.both256/method2"));
    assertEquals(BOTH_256, factory.getLog("package.both256/method3"));

    assertEquals(
        new Builder().header(128).msg(128).build(), factory.getLog("package.service1/both128"));
    // no global config in effect
    assertEquals(NONE, factory.getLog("package.service1/absent"));

    assertEquals(MSG_FULL, factory.getLog("package.service2/method_messageOnly"));
    // no global config in effect
    assertEquals(NONE, factory.getLog("package.service2/absent"));
  }

  @Test
  public void configBinLog_duplicateGlobal() throws Exception {
    try {
      new Factory("*{h},p.s/m,*{h:256}");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().equals("Duplicate log config for: *"));
    }
  }

  @Test
  public void configBinLog_duplicateMethod() throws Exception {
    try {
      new Factory("p.s/m,*{h:256},p.s/m{h}");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().startsWith("Duplicate log config for method:"));
    }
  }

  @Test
  public void configBinLog_duplicateService() throws Exception {
    try {
      new Factory("p.s/*,*{h:256},p.s/*{h}");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().startsWith("Duplicate log config for service:"));
    }
  }

  /** A builder class to make unit test code more readable. */
  private static final class Builder {
    int maxHeaderBytes = 0;
    int maxMessageBytes = 0;

    Builder header(int bytes) {
      maxHeaderBytes = bytes;
      return this;
    }

    Builder msg(int bytes) {
      maxMessageBytes = bytes;
      return this;
    }

    BinaryLog build() {
      return new BinaryLog(maxHeaderBytes, maxMessageBytes);
    }
  }
}
