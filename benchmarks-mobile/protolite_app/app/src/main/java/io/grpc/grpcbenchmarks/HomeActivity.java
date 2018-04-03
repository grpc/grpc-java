package io.grpc.grpcbenchmarks;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
    }

    public void showProtobufBenchmarks(View v) {
        Intent intent = new Intent(this, ProtobufBenchmarksActivity.class);
        startActivity(intent);
    }

    public void showRpcBenchmarks(View v) {
        Intent intent = new Intent(this, RpcBenchmarksActivity.class);
        startActivity(intent);
    }
}
