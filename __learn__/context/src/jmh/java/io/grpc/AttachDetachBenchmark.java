package io.grpc;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** StatusBenchmark */
@State(Scope.Benchmark)
public class AttachDetachBenchmark {
  private final Context.Key<Integer> key = Context.keyWithDefault("key", 9999);
  private final Context cu = Context.current().withValue(key, 8888);

  /**
   * Attach and detach Context
   */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @GroupThreads(6)
  public int attachDetach() {
    Context old = cu.attach();
    try {
      return key.get();
    } finally {
      Context.current().detach(old);
    }
  }
}