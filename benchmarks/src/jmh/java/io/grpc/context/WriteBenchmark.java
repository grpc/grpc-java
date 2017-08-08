package io.grpc.context;

import com.google.common.collect.Lists;
import io.grpc.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** Write benchmark. */
public class WriteBenchmark {
  private static final int NUM_KEYS = 8;

  @State(Scope.Benchmark)
  public static class ContextState {
    @Param({"true", "false"})
    public boolean batched;

    List<Context.Key<Object>> keys = new ArrayList<Context.Key<Object>>();
    List<List<Context.Key<Object>>> batchKeys = new ArrayList<List<Context.Key<Object>>>();
    Context context = Context.ROOT;
    Object val = new Object();

    @Setup
    public void setup() {
      for (int i = 0; i < 8; i++) {
        keys.add(Context.key("Key" + i));
      }
      batchKeys = Lists.partition(keys, 4);
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void testContextWrite(ContextState state, Blackhole bh) {
    // always reset Context to avoid unbounded memory usage
    state.context = Context.ROOT;
    if (state.batched) {
      for (Context.Key<Object> key : state.keys) {
        state.context = state.context.withValue(key, state.val);
      }
    } else {
      for (List<Context.Key<Object>> batch : state.batchKeys) {
        state.context = batchPut(state.context, batch, state.val);
      }
    }
    bh.consume(state.context);
  }


  /**
   * Returns a new Context based off of `c` where all keys from `batch` are inserted with `val`.
   */
  private static Context batchPut(Context c, List<Context.Key<Object>> batch, Object val) {
    switch (batch.size()) {
      case 4:
        return c.withValues(
            batch.get(0), val,
            batch.get(1), val,
            batch.get(2), val,
            batch.get(3), val);
      case 3:
        return c.withValues(
            batch.get(0), val,
            batch.get(1), val,
            batch.get(2), val);
      case 2:
        return c.withValues(
            batch.get(0), val,
            batch.get(1), val);
      case 1:
        return c.withValue(batch.get(0), val);
      default:
        throw new AssertionError("Batch size should be between 1 and 4");
    }
  }
}
