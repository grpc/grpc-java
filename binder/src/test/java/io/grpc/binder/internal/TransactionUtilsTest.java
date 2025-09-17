/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.binder.internal.TransactionUtils.newCallerFilteringHandler;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Binder;
import android.os.Parcel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowBinder;

@RunWith(RobolectricTestRunner.class)
public final class TransactionUtilsTest {

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  @Mock LeakSafeOneWayBinder.TransactionHandler mockHandler;

  @Test
  public void shouldIgnoreTransactionFromWrongUid() {
    Parcel p = Parcel.obtain();
    int originalUid = Binder.getCallingUid();
    try {
      when(mockHandler.handleTransaction(eq(1234), same(p))).thenReturn(true);
      LeakSafeOneWayBinder.TransactionHandler uid100OnlyHandler =
          newCallerFilteringHandler(1000, mockHandler);

      ShadowBinder.setCallingUid(9999);
      boolean result = uid100OnlyHandler.handleTransaction(1234, p);
      assertThat(result).isFalse();
      verify(mockHandler, never()).handleTransaction(anyInt(), any());

      ShadowBinder.setCallingUid(1000);
      result = uid100OnlyHandler.handleTransaction(1234, p);
      assertThat(result).isTrue();
      verify(mockHandler).handleTransaction(1234, p);
    } finally {
      ShadowBinder.setCallingUid(originalUid);
      p.recycle();
    }
  }
}
