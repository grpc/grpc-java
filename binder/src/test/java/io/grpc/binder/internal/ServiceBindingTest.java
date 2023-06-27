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

import static android.content.Context.BIND_AUTO_CREATE;
import static android.os.Looper.getMainLooper;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.annotation.LooperMode.Mode.PAUSED;

import android.app.Application;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserHandle;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.binder.BinderChannelCredentials;
import io.grpc.binder.internal.Bindable.Observer;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowDevicePolicyManager;

@LooperMode(PAUSED)
@RunWith(RobolectricTestRunner.class)
public final class ServiceBindingTest {

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  @Mock IBinder mockBinder;

  private Application appContext;
  private ComponentName serviceComponent;
  private ShadowApplication shadowApplication;
  private TestObserver observer;
  private ServiceBinding binding;

  @Before
  public void setUp() {
    appContext = ApplicationProvider.getApplicationContext();
    serviceComponent = new ComponentName("DUMMY", "SERVICE");
    observer = new TestObserver();

    shadowApplication = shadowOf(appContext);
    shadowApplication.setComponentNameAndServiceForBindService(serviceComponent, mockBinder);

    // Don't call onServiceDisconnected() upon unbindService(), just like the real Android doesn't.
    shadowApplication.setUnbindServiceCallsOnServiceDisconnected(false);

    binding = newBuilder().build();
    shadowOf(getMainLooper()).idle();
  }

  private ServiceBindingBuilder newBuilder() {
    return new ServiceBindingBuilder()
        .setSourceContext(appContext)
        .setTargetComponent(serviceComponent)
        .setFlags(BIND_AUTO_CREATE)
        .setObserver(observer);
  }

  @Test
  public void testInitialState() throws Exception {
    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
    assertThat(observer.gotBoundEvent).isFalse();
    assertThat(observer.gotUnboundEvent).isFalse();
    assertThat(binding.isSourceContextCleared()).isFalse();
  }

  @Test
  public void testBind() throws Exception {
    binding.bind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isNotEmpty();
    assertThat(observer.gotBoundEvent).isTrue();
    assertThat(observer.binder).isSameInstanceAs(mockBinder);
    assertThat(observer.gotUnboundEvent).isFalse();
    assertThat(binding.isSourceContextCleared()).isFalse();
  }

  @Test
  public void testBindingIntent() throws Exception {
    shadowApplication.setComponentNameAndServiceForBindService(null, null);
    shadowApplication.setComponentNameAndServiceForBindServiceForIntent(
        new Intent("foo").setComponent(serviceComponent), serviceComponent, mockBinder);
    binding = newBuilder().setBindingAction("foo").build();
    binding.bind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isNotEmpty();
  }

  @Test
  public void testUnbind() throws Exception {
    binding.unbind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
    assertThat(observer.gotBoundEvent).isFalse();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.CANCELLED);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  public void testBindUnbind() throws Exception {
    binding.bind();
    shadowOf(getMainLooper()).idle();
    binding.unbind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
    assertThat(observer.gotBoundEvent).isTrue();
    assertThat(observer.binder).isSameInstanceAs(mockBinder);
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.CANCELLED);
    assertThat(binding.isSourceContextCleared()).isTrue();
    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
  }

  @Test
  public void testBindUnbindQuickly() throws Exception {
    binding.bind();
    binding.unbind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
    // Because unbinding happened so quickly, we won't have gotten the bind event.
    assertThat(observer.gotBoundEvent).isFalse();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.CANCELLED);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  public void testUnbindBind() throws Exception {
    binding.unbind();
    binding.bind();
    shadowOf(getMainLooper()).idle();
    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
    assertThat(observer.gotBoundEvent).isFalse();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.CANCELLED);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  public void testBindFailure() throws Exception {
    shadowApplication.declareComponentUnbindable(serviceComponent);
    binding.bind();
    shadowOf(getMainLooper()).idle();
    assertThat(observer.gotBoundEvent).isFalse();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.UNIMPLEMENTED);
    assertThat(binding.isSourceContextCleared()).isTrue();
    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
  }

  @Test
  public void testBindSecurityException() throws Exception {
    SecurityException securityException = new SecurityException();
    shadowApplication.setThrowInBindService(securityException);
    binding.bind();
    shadowOf(getMainLooper()).idle();
    assertThat(observer.gotBoundEvent).isFalse();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.PERMISSION_DENIED);
    assertThat(observer.unboundReason.getCause()).isEqualTo(securityException);
    assertThat(binding.isSourceContextCleared()).isTrue();
    assertThat(shadowApplication.getBoundServiceConnections()).isEmpty();
  }

  @Test
  public void testBindDisconnect() throws Exception {
    binding.bind();
    shadowOf(getMainLooper()).idle();
    shadowApplication.getBoundServiceConnections().get(0).onServiceDisconnected(serviceComponent);
    shadowOf(getMainLooper()).idle();
    assertThat(observer.gotBoundEvent).isTrue();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  public void testBindDisconnectQuickly() throws Exception {
    binding.bind();
    shadowApplication.getBoundServiceConnections().get(0).onServiceDisconnected(serviceComponent);
    shadowOf(getMainLooper()).idle();
    assertThat(observer.gotBoundEvent).isFalse(); // We won't have had time to get the binder.
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  @Config(sdk = {28}) // For onNullBinding.
  public void testBindReturnsNull() throws Exception {
    binding.bind();
    shadowOf(getMainLooper()).idle();
    shadowApplication.getBoundServiceConnections().get(0).onNullBinding(serviceComponent);
    shadowOf(getMainLooper()).idle();
    assertThat(observer.gotBoundEvent).isTrue();
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.UNIMPLEMENTED);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  @Config(sdk = {28}) // For onNullBinding.
  public void testBindReturnsNullQuickly() throws Exception {
    binding.bind();
    shadowApplication.getBoundServiceConnections().get(0).onNullBinding(serviceComponent);
    shadowOf(getMainLooper()).idle();
    assertThat(observer.gotBoundEvent).isFalse(); // We won't have had a chance to get the binder.
    assertThat(observer.gotUnboundEvent).isTrue();
    assertThat(observer.unboundReason.getCode()).isEqualTo(Code.UNIMPLEMENTED);
    assertThat(binding.isSourceContextCleared()).isTrue();
  }

  @Test
  public void testCallsAfterUnbindDontCrash() throws Exception {
    binding.unbind();
    shadowOf(getMainLooper()).idle();

    assertThat(binding.isSourceContextCleared()).isTrue();

    // The internal context is cleared. Try using the object to make sure it doesn't NPE.
    binding.bind();
    binding.unbind();
    shadowOf(getMainLooper()).idle();
  }

  @Test
  @Config(sdk = 30)
  public void testBindWithTargetUserHandle() throws Exception {
    binding =
        newBuilder().setTargetUserHandle(generateUserHandle(/* userId= */ 0)).build();
    shadowOf(getMainLooper()).idle();

    binding.bind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isNotEmpty();
    assertThat(observer.gotBoundEvent).isTrue();
    assertThat(observer.binder).isSameInstanceAs(mockBinder);
    assertThat(observer.gotUnboundEvent).isFalse();
    assertThat(binding.isSourceContextCleared()).isFalse();
  }

  @Test
  @Config(sdk = 30)
  public void testBindWithDeviceAdmin() throws Exception {
    String deviceAdminClassName = "DevicePolicyAdmin";
    ComponentName adminComponent = new ComponentName(appContext, deviceAdminClassName);
    allowBindDeviceAdminForUser(appContext, adminComponent, /* userId= */ 0);
    binding =
        newBuilder()
            .setTargetUserHandle(UserHandle.getUserHandleForUid(/* userId= */ 0))
            .setTargetUserHandle(generateUserHandle(/* userId= */ 0))
            .setChannelCredentials(
                BinderChannelCredentials.forDevicePolicyAdmin(adminComponent))
            .build();
    shadowOf(getMainLooper()).idle();

    binding.bind();
    shadowOf(getMainLooper()).idle();

    assertThat(shadowApplication.getBoundServiceConnections()).isNotEmpty();
    assertThat(observer.gotBoundEvent).isTrue();
    assertThat(observer.binder).isSameInstanceAs(mockBinder);
    assertThat(observer.gotUnboundEvent).isFalse();
    assertThat(binding.isSourceContextCleared()).isFalse();
  }

  private void assertNoLockHeld() {
    try {
      binding.wait(1);
      fail("Lock held on binding");
    } catch (IllegalMonitorStateException ime) {
      // Expected.
    } catch (InterruptedException inte) {
      throw new AssertionError(
          "Interrupted exception when we shouldn't have been able to wait.", inte);
    }
  }

  private static void allowBindDeviceAdminForUser(Context context, ComponentName admin, int userId) {
    ShadowDevicePolicyManager devicePolicyManager =
        shadowOf(context.getSystemService(DevicePolicyManager.class));
    devicePolicyManager.setDeviceOwner(admin);
    devicePolicyManager.setBindDeviceAdminTargetUsers(
        Arrays.asList(UserHandle.getUserHandleForUid(userId)));
        shadowOf((DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE));
    devicePolicyManager.setDeviceOwner(admin);
    devicePolicyManager.setBindDeviceAdminTargetUsers(
        Arrays.asList(generateUserHandle(userId)));
  }

  /** Generate UserHandles the hard way. */
  private static UserHandle generateUserHandle(int userId) {
    Parcel userParcel = Parcel.obtain();
    userParcel.writeInt(userId);
    userParcel.setDataPosition(0);
    UserHandle userHandle = new UserHandle(userParcel);
    userParcel.recycle();
    return userHandle;
  }

  private class TestObserver implements Bindable.Observer {

    public boolean gotBoundEvent;
    public IBinder binder;

    public boolean gotUnboundEvent;
    public Status unboundReason;

    @Override
    public void onBound(IBinder binder) {
      assertThat(gotBoundEvent).isFalse();
      assertNoLockHeld();
      gotBoundEvent = true;
      this.binder = binder;
    }

    @Override
    public void onUnbound(Status reason) {
      assertThat(gotUnboundEvent).isFalse();
      assertNoLockHeld();
      gotUnboundEvent = true;
      unboundReason = reason;
    }
  }

  private static class ServiceBindingBuilder {
    private Context sourceContext;
    private Observer observer;
    private Intent bindIntent = new Intent();
    private int bindServiceFlags;
    @Nullable private UserHandle targetUserHandle = null;
    private BinderChannelCredentials channelCredentials = BinderChannelCredentials.forDefault();

    public ServiceBindingBuilder setSourceContext(Context sourceContext) {
      this.sourceContext = sourceContext;      
      return this;
    }

    public ServiceBindingBuilder setBindingAction(String bindAction) {
      this.bindIntent.setAction(bindAction);
      return this;
    }

    public ServiceBindingBuilder setFlags(int bindServiceFlags) {
      this.bindServiceFlags = bindServiceFlags;
      return this;
    }

    public ServiceBindingBuilder setTargetComponent(ComponentName targetComponent) {
      this.bindIntent.setComponent(targetComponent);
      return this;
    }

    public ServiceBindingBuilder setObserver(Observer observer) {
      this.observer = observer;
      return this;
    }

    public ServiceBindingBuilder setTargetUserHandle(UserHandle targetUserHandle) {
      this.targetUserHandle = targetUserHandle;
      return this;
    }

    public ServiceBindingBuilder setChannelCredentials(
        BinderChannelCredentials channelCredentials) {
      this.channelCredentials = channelCredentials;
      return this;
    }

    public ServiceBinding build() {
      return new ServiceBinding(
          ContextCompat.getMainExecutor(sourceContext),
          sourceContext,
          channelCredentials,
          bindIntent,
          targetUserHandle,
          bindServiceFlags,
          observer);
    }
  }
}
