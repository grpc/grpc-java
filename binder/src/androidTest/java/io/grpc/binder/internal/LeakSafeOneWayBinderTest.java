/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder.internal;

import static android.os.IBinder.FLAG_ONEWAY;
import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.grpc.binder.internal.LeakSafeOneWayBinder.TransactionHandler;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LeakSafeOneWayBinderTest {

  private LeakSafeOneWayBinder binder;
  private final FakeHandler handler = new FakeHandler();

  static class FakeHandler implements TransactionHandler {
    int transactionsHandled;
    int lastCode;
    Parcel lastParcel;

    @Override
    public boolean handleTransaction(int code, Parcel parcel) {
      transactionsHandled++;
      lastCode = code;
      lastParcel = parcel;
      return true;
    }
  }

  @Before
  public void setUp() {
    binder = new LeakSafeOneWayBinder(handler);
  }

  @Test
  public void testTransaction() {
    Parcel p = Parcel.obtain();
    assertThat(binder.onTransact(123, p, null, FLAG_ONEWAY)).isTrue();
    assertThat(handler.transactionsHandled).isEqualTo(1);
    assertThat(handler.lastCode).isEqualTo(123);
    assertThat(handler.lastParcel).isSameInstanceAs(p);
    p.recycle();
  }

  @Test
  public void testDropsTwoWayTransactions() {
    Parcel p = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    assertThat(binder.onTransact(123, p, reply, 0)).isFalse();
    assertThat(handler.transactionsHandled).isEqualTo(0);
    p.recycle();
    reply.recycle();
  }

  @Test
  public void testDetach() {
    Parcel p = Parcel.obtain();
    binder.detach();
    assertThat(binder.onTransact(456, p, null, FLAG_ONEWAY)).isFalse();

    // The transaction shouldn't have been processed.
    assertThat(handler.transactionsHandled).isEqualTo(0);

    p.recycle();
  }

  @Test
  public void testReplace() {
    binder = new LeakSafeOneWayBinder(handler);
    Parcel p = Parcel.obtain();
    FakeHandler handler2 = new FakeHandler();
    binder.setHandler(handler2);
    assertThat(binder.onTransact(456, p, null, FLAG_ONEWAY)).isTrue();

    assertThat(handler.transactionsHandled).isEqualTo(0);
    assertThat(handler2.transactionsHandled).isEqualTo(1);

    p.recycle();
  }

  @Test
  public void testMultipleTransactions() {
    Parcel p = Parcel.obtain();
    assertThat(binder.onTransact(123, p, null, FLAG_ONEWAY)).isTrue();
    assertThat(binder.onTransact(456, p, null, FLAG_ONEWAY)).isTrue();
    assertThat(handler.transactionsHandled).isEqualTo(2);
    assertThat(handler.lastCode).isEqualTo(456);
    assertThat(handler.lastParcel).isSameInstanceAs(p);
    p.recycle();
  }

  @Test
  public void testPing() {
    assertThat(binder.pingBinder()).isTrue();
  }

  @Test
  public void testPing_Detached() {
    binder.detach();
    assertThat(binder.pingBinder()).isFalse();
  }
}
