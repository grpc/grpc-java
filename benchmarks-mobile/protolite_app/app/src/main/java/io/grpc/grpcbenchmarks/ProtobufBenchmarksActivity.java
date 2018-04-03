package io.grpc.grpcbenchmarks;

import com.google.protobuf.MessageLite;

import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProtobufBenchmarksActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener {
    private static final Logger logger = Logger.getLogger(ProtobufBenchmarksActivity.class.getName());

    private List<CardView> cardViews;
    private Button mBenchmarkButton;
    private CheckBox mCheckBox;

    private MessageLite sharedMessage;
    private String sharedJson;
    private ProtoEnum selectedEnum = ProtoEnum.SMALL_REQUEST;
    private int tasksRunning = 0;
    private boolean useGzip = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_protobuf_benchmarks);

        mBenchmarkButton = (Button) findViewById(R.id.protobuf_benchmarks_button);
        mCheckBox = (CheckBox) findViewById(R.id.protobuf_benchmarks_gzipcheck);

        // set up spinner
        Spinner mSpinner = (Spinner) findViewById(R.id.protobuf_benchmarks_spinner);
        ArrayAdapter<ProtoEnum> protoAdapter = new ArrayAdapter<ProtoEnum>(this,
                android.R.layout.simple_spinner_dropdown_item, ProtoEnum.values());
        mSpinner.setAdapter(protoAdapter);
        mSpinner.setOnItemSelectedListener(this);

        // set up benchmark cards
        initializeBenchmarkCards();
    }

    private void initializeBenchmarkCards() {
        List<Benchmark> benchmarks = new ArrayList<>();
        benchmarks.add(new Benchmark("Serialize protobuf to byte array", "", 0));
        benchmarks.add(new Benchmark("Serialize protobuf to CodedOutputStream", "", 1));
        benchmarks.add(new Benchmark("Serialize protobuf to ByteArrayOutputStream", "", 2));
        benchmarks.add(new Benchmark("Deserialize protobuf from byte array", "", 3));
        benchmarks.add(new Benchmark("Deserialize protobuf from CodedInputStream", "", 4));
        benchmarks.add(new Benchmark("Deserialize protobuf from ByteArrayInputStream", "", 5));
        benchmarks.add(new Benchmark("Serialize JSON to byte array", "", 6));
        benchmarks.add(new Benchmark("Deserialize JSON from byte array", "", 7));

        LayoutInflater inflater = LayoutInflater.from(getApplicationContext());
        LinearLayout l = (LinearLayout) findViewById(R.id.protobuf_benchmark_cardlayoutlinear);
        cardViews = new ArrayList<>();

        for (final Benchmark b : benchmarks) {
            final CardView cv = (CardView) inflater.inflate(R.layout.protobuf_cv, l, false);
            cv.setCardBackgroundColor(ContextCompat.getColor(getApplicationContext(),
                    R.color.cardview_light_background));
            TextView tv = (TextView) cv.findViewById(R.id.protobuf_benchmark_title);
            TextView descrip = (TextView) cv.findViewById(R.id.protobuf_benchmark_description);
            ImageButton button = (ImageButton) cv.findViewById(R.id.protobuf_benchmark_start);
            tv.setText(b.title);
            descrip.setText(b.description);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startBenchmark(cv, b);
                }
            });

            cardViews.add(cv);
            l.addView(cv);
        }
    }

    public void beginAllBenchmarks(View v) {
        if (tasksRunning == 0) {
            sharedMessage = ProtobufRandomWriter.randomProto(selectedEnum);
            sharedJson = ProtobufRandomWriter.protoToJsonString(selectedEnum, sharedMessage);

            mBenchmarkButton.setEnabled(false);
            mBenchmarkButton.setText(R.string.allBenchmarksButtonDisabled);
            for (CardView cv : cardViews) {
                cv.findViewById(R.id.protobuf_benchmark_start).performClick();
            }
        }
    }

    public void startBenchmark(CardView cv, Benchmark b) {
        BenchmarkAsyncTask task = new BenchmarkAsyncTask(cv, b);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        } else {
            task.execute();
        }
    }

    public void onItemSelected(AdapterView<?> parent, View view,
                               int pos, long id) {
        selectedEnum = (ProtoEnum) parent.getSelectedItem();
        // prevent gzip for small request, strange bug where GZIP fails to decompress correctly
        if (selectedEnum == ProtoEnum.SMALL_REQUEST) {
            mCheckBox.setChecked(false);
        }
    }

    public void onNothingSelected(AdapterView<?> parent) {}

    private class BenchmarkAsyncTask extends AsyncTask<Integer, Integer, BenchmarkResult> {
        CardView cv;
        Benchmark b;

        BenchmarkAsyncTask(CardView cv, Benchmark b) {
            this.cv = cv;
            this.b = b;
        }

        @Override
        protected void onPreExecute() {
            // again, prevent gzip for small request
            if (selectedEnum == ProtoEnum.SMALL_REQUEST) {
                mCheckBox.setChecked(false);
            }

            useGzip = mCheckBox.isChecked();
            tasksRunning++;
            mBenchmarkButton.setEnabled(false);
            mBenchmarkButton.setText(R.string.allBenchmarksButtonDisabled);
            cv.findViewById(R.id.protobuf_benchmark_start).setEnabled(false);
            cv.findViewById(R.id.protobuf_benchmark_start).setVisibility(View.INVISIBLE);
            cv.findViewById(R.id.protobuf_benchmark_progress).setVisibility(View.VISIBLE);
        }

        @Override
        protected BenchmarkResult doInBackground(Integer... inputs) {
            try {
                MessageLite message;
                String jsonString;
                if (sharedMessage != null) {
                    message = sharedMessage;
                    jsonString = sharedJson;
                } else {
                    message = ProtobufRandomWriter.randomProto(selectedEnum);
                    jsonString = ProtobufRandomWriter.protoToJsonString(selectedEnum, message);
                }

                BenchmarkResult res = b.run(message, jsonString, useGzip);

                logger.log(Level.INFO, res.toString());
                return res;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Exception while running benchmarks: " + e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(BenchmarkResult res) {
            tasksRunning--;
            cv.findViewById(R.id.protobuf_benchmark_progress).setVisibility(View.INVISIBLE);
            cv.findViewById(R.id.protobuf_benchmark_start).setEnabled(true);
            cv.findViewById(R.id.protobuf_benchmark_start).setVisibility(View.VISIBLE);
            TextView descrip = (TextView) cv.findViewById(R.id.protobuf_benchmark_description);
            descrip.setText(res.toString());

            if (tasksRunning == 0) {
                sharedMessage = null;
                mBenchmarkButton.setEnabled(true);
                mBenchmarkButton.setText(R.string.allBenchmarksButtonEnabled);
            }
        }
    }
}
