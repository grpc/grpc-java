package io.grpc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Read Benchmark
 */
public class ReadBenchmark {

  @State(Scope.Benchmark)
  public static class ContextState {
    List<Context.Key<Object>> keys = new ArrayList<>();
    List<Context> contexts = new ArrayList<>();

    /**
     * Initialise the keys and contexts lists
     */
    @Setup
    public void setup() {
      for (int = 0; i < 8; i++) {
        keys.add(Context.key("Key" + i));
      }
      contexts.add(
        Context.ROOT.withValue(keys.get(0), 
        new Object())
      );
      contexts.add(
        Context.ROOT.withValues(
          keys.get(0), 
          new Object(), 
          keys.get(1),
          new Object()
        )
      );
      contexts.add(
        Context.ROOT.withValues(
          keys.get(0),
          new Object(),
          keys.get(1),
          new Object(),
          keys.get(2),
          new Object()
        )
      );
      contexts.add(
        Context.ROOT.withValues(
          keys.get(0),
          new Object(),
          keys.get(1),
          new Object(),
          keys.get(2),
          new Object(),
          keys.get(3),
          new Object()
        )
      );
      contexts.add(
        contexts.get(0).withValue(
          keys.get(1),
          new Object()
        )
      );
      contexts.add(
        contexts.get(1).withValues(
          keys.get(2),
          new Object(),
          keys.get(3),
          new Object()
        )
      );
      contexts.add(
        contexts.get(1).withValues(
          keys.get(2),
          new Object(),
          keys.get(3),
          new Object()
        )
      );
      contexts.add(
        contexts.get(0).withValue(
          keys.get(1),
          new Object()
        )
      );
      contexts.add(
        contexts.get(1).withValues(
          keys.get(2),
          new Object(),
          keys.get(3),
          new Object()
        )
      );
      contexts.add(
        contexts.get(2).withValues(
          keys.get(3),
          new Object(),
          keys.get(4),
          new Object(),
          keys.get(5),
          new Object()
        )
      );
      contexts.add(
        contexts.get(3).withValues(
          keys.get(4),
          new Object(),
          keys.get(5),
          new Object(),
          keys.get(6),
          new Object(),
          keys.get(7),
          new Object()
        )
      );
    }

    /**
     * Perform the read operation
     * @param state A ContextState instance
     * @param blackhole A Blackhole instance (allows us to consume multiple times)
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void testContextLookup(ContextState state, Blackhole blackhole) {
      for (Context.Key<?> key : state.keys) {
        for (Context context : state.contexts) {
          blackhole.consume(key.get(context));
        }
      }
    }
  }
}
