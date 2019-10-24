/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import java.io.Closeable;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReferenceCountingMap}. */
@RunWith(JUnit4.class)
public class ReferenceCountingMapTest {

  @Test
  public void referenceCountingMap_getAndRelease_closeCalled() throws InterruptedException {
    ReferenceCountingMap<Integer, MyValue> map =
        new ReferenceCountingMap<>(/* destroyDelaySeconds= */ 1L);

    MyResourceDef res = new MyResourceDef(3);
    MyValue val = map.get(res);
    assertThat(val.key).isEqualTo(3);
    assertThat(val.closeCalled).isFalse();
    MyValue val1 = map.get(new MyResourceDef(3));
    assertThat(val1).isSameInstanceAs(val);
    // at this point ref-count is 2
    assertThat(map.release(3, val)).isNull();
    Thread.sleep(1500L);
    assertThat(val.closeCalled).isFalse();
    assertThat(map.release(3, val)).isNull(); // after this ref-count is 0
    Thread.sleep(1500L);
    assertThat(val.closeCalled).isTrue();
  }

  @Test
  public void referenceCountingMap_excessRelease_expectException() throws InterruptedException {
    ReferenceCountingMap<Integer, MyValue> map = new ReferenceCountingMap<>();

    MyResourceDef res = new MyResourceDef(4);
    MyValue val = map.get(res);
    assertThat(val.key).isEqualTo(4);
    // at this point ref-count is 1
    map.release(4, val);
    // ref-count is now 0
    try {
      map.release(4, val);
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("Refcount has already reached zero");
    }
  }

  @Test
  public void referenceCountingMap_releaseAndGetImmediately_noClose() throws InterruptedException {
    ReferenceCountingMap<Integer, MyValue> map = new ReferenceCountingMap<>();

    MyResourceDef res = new MyResourceDef(4);
    MyValue val = map.get(res);
    assertThat(val.key).isEqualTo(4);
    // at this point ref-count is 1
    map.release(4, val); // after this ref-count is 0
    // before the object is garbage collected , do a get again:
    MyValue val1 = map.get(res);
    assertThat(val1).isSameInstanceAs(val);
    assertThat(val.closeCalled).isFalse();
  }

  @Test
  public void referenceCountingMap_releaseAndGetAfterDelay_differentInstance()
      throws InterruptedException {
    ReferenceCountingMap<Integer, MyValue> map =
        new ReferenceCountingMap<>(/* destroyDelaySeconds= */ 1L);

    MyResourceDef res = new MyResourceDef(4);
    MyValue val = map.get(res);
    assertThat(val.key).isEqualTo(4);
    // at this point ref-count is 1
    map.release(4, val); // after this ref-count is 0
    Thread.sleep(1500L);
    // object is garbage collected , do a get again:
    MyValue val1 = map.get(res);
    assertThat(val1).isNotSameInstanceAs(val);
  }

  private static final class MyValue implements Closeable {

    boolean closeCalled = false;

    Integer key;

    MyValue(Integer key) {
      this.key = key;
    }

    @Override
    public void close() throws IOException {
      closeCalled = true;
    }
  }

  private static final class MyResourceDef
      implements ReferenceCountingMap.ResourceDefinition<Integer, MyValue> {

    Integer key;

    MyResourceDef(Integer key) {
      this.key = key;
    }

    @Override
    public MyValue create() {
      return new MyValue(key);
    }

    @Override
    public Integer getKey() {
      return key;
    }
  }
}
