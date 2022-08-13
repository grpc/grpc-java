package io.grpc;

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

/**
 * Class to write the benchmark
 */
public class WriteBenchmark {
  @State(Scope.Thread)
  public static class ContextState {
    @Param({"0","10","25","100"})
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
        context = context.withValue(
          Context.key("preexisting_key" + i),
          val
        );
      }
    }
  }

  /**
   * Write the benchmark
   * @param state The ContextState instance
   * @param blackhole The Blackhole instance
   */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public Context doWrite(ContextState state, Blackhole blackhole) {
    return state.context.withValues(
      state.key1, state.val,
      state.key2, state.val,
      state.key3, state.val,
      state.key4, state.val
    );
  }
}