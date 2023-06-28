/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.android.integrationtest;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocketAddress.Namespace;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.security.ProviderInstaller;
import io.grpc.ManagedChannel;
import io.grpc.android.UdsChannelBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TesterActivity extends AppCompatActivity
    implements ProviderInstaller.ProviderInstallListener {
  private static final String LOG_TAG = "GrpcTesterActivity";

  private List<Button> buttons;
  private EditText hostEdit;
  private EditText portEdit;
  private CheckBox useUdsCheckBox;
  private EditText udsEdit;
  private TextView resultText;
  private CheckBox testCertCheckBox;

  private UdsTcpEndpointConnector endpointConnector;

  private ExecutorService executor;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_tester);
    buttons = new ArrayList<>();
    buttons.add((Button) findViewById(R.id.empty_unary_button));
    buttons.add((Button) findViewById(R.id.large_unary_button));
    buttons.add((Button) findViewById(R.id.client_streaming_button));
    buttons.add((Button) findViewById(R.id.server_streaming_button));
    buttons.add((Button) findViewById(R.id.ping_pong_button));

    hostEdit = (EditText) findViewById(R.id.host_edit_text);
    portEdit = (EditText) findViewById(R.id.port_edit_text);
    useUdsCheckBox = (CheckBox) findViewById(R.id.use_uds_checkbox);
    udsEdit = (EditText) findViewById(R.id.uds_proxy_edit_text);
    resultText = (TextView) findViewById(R.id.grpc_response_text);
    testCertCheckBox = (CheckBox) findViewById(R.id.test_cert_checkbox);

    ProviderInstaller.installIfNeededAsync(this, this);
    // Disable buttons until the security provider installing finishes.
    enableButtons(false);

    executor = Executors.newSingleThreadExecutor();
  }

  /** Click handler for unix domain socket. */
  public void enableUds(View view) {
    boolean enabled = ((CheckBox) view).isChecked();
    udsEdit.setEnabled(enabled);
    testCertCheckBox.setEnabled(!enabled);
    if (enabled) {
      testCertCheckBox.setChecked(false);
    }
  }

  public void startEmptyUnary(View view) {
    startTest("empty_unary");
  }

  public void startLargeUnary(View view) {
    startTest("large_unary");
  }

  public void startClientStreaming(View view) {
    startTest("client_streaming");
  }

  public void startServerStreaming(View view) {
    startTest("server_streaming");
  }

  public void startPingPong(View view) {
    startTest("ping_pong");
  }

  private void enableButtons(boolean enable) {
    for (Button button : buttons) {
      button.setEnabled(enable);
    }
  }

  private void startTest(String testCase) {
    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
        hostEdit.getWindowToken(), 0);
    enableButtons(false);
    resultText.setText("Testing...");

    String host = hostEdit.getText().toString();
    String portStr = portEdit.getText().toString();
    int port = TextUtils.isEmpty(portStr) ? 8080 : Integer.valueOf(portStr);
    boolean udsEnabled = useUdsCheckBox.isChecked();
    String udsPath =
        TextUtils.isEmpty(udsEdit.getText()) ? "default" : udsEdit.getText().toString();

    String serverHostOverride;
    InputStream testCert;
    if (testCertCheckBox.isChecked()) {
      serverHostOverride = "foo.test.google.fr";
      testCert = getResources().openRawResource(R.raw.ca);
    } else {
      serverHostOverride = null;
      testCert = null;
    }

    // Create Channel
    ManagedChannel channel;
    if (udsEnabled) {
      channel = UdsChannelBuilder.forPath(udsPath, Namespace.ABSTRACT).build();
    } else {
      channel = TesterOkHttpChannelBuilder.build(host, port, serverHostOverride, true, testCert);
    }

    // Port-forward uds local port to server exposing tcp endpoint.
    if (udsEnabled) {
      endpointConnector = new UdsTcpEndpointConnector(udsPath, host, port);
      try {
        endpointConnector.start();
      } catch (IOException e) {
        Log.e(LOG_TAG, "Failed to start UDS-TCP Endpoint Connector.");
      }
    }

    // Start Test.
    String result = null;
    try {
      result = executor.submit(new TestCallable(channel, testCase)).get();
    } catch (ExecutionException | InterruptedException e) {
      result = e.getMessage();
    } finally {
      if (endpointConnector != null) {
        endpointConnector.shutDown();
        endpointConnector = null;
      }
      resultText.setText(result);
      enableButtons(true);
    }
  }

  @Override
  public void onProviderInstalled() {
    // Provider is up-to-date, app can make secure network calls.
    enableButtons(true);
  }

  @Override
  public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
    // The provider is helpful, but it is possible to succeed without it.
    // Hope that the system-provided libraries are new enough.
    Log.w(LOG_TAG, "Failed installing security provider, error code: " + errorCode);
    enableButtons(true);
  }
}
