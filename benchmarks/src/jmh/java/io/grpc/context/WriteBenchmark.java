package io.grpc.context;

import io.grpc.Context;
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
  @State(Scope.Thread)
  public static class ContextState {
    @Param({"0", "10", "25", "100"})
    public int preexistingKeys;

    Context.Key<Object> key1 = Context.key("key1");
    Context.Key<Object> key2 = Context.key("key2");
    Context.Key<Object> key3 = Context.key("key3");
    Context.Key<Object> key4 = Context.key("key4");
    Context context;
    Object val = new Object();

    @Setup
    public void setup() {
      context = Context.ROOT;
      for (int i = 0; i < preexistingKeys; i++) {
        context = context.withValue(Context.key("preexisting_key" + i), val);
      }
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void doWrite(ContextState state, Blackhole bh) {
    Context result = state.context.withValues(
        state.key1, state.val,
        state.key2, state.val,
        state.key3, state.val,
        state.key4, state.val);
    bh.consume(result);
  }
}
