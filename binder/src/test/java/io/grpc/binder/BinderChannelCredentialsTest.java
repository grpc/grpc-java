package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BinderChannelCredentialsTest {
  private final Context appContext = ApplicationProvider.getApplicationContext();

  @Test
  public void defaultBinderChannelCredentials() {
    BinderChannelCredentials channelCredentials = BinderChannelCredentials.forDefault();
    assertThat(channelCredentials.getDevicePolicyAdminComponentName()).isNull();
  }
  
  @Test
  public void binderChannelCredentialsForDevicePolicyAdmin() {
    String deviceAdminClassName = "DevicePolicyAdmin";
    BinderChannelCredentials channelCredentials =
        BinderChannelCredentials.forDevicePolicyAdmin(
            new ComponentName(appContext, deviceAdminClassName));
    assertThat(channelCredentials.getDevicePolicyAdminComponentName()).isNotNull();
    assertThat(channelCredentials.getDevicePolicyAdminComponentName().getClassName())
        .isEqualTo(deviceAdminClassName);
  }
}
