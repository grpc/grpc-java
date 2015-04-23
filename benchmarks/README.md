grpc Benchmarks
==============================================

## QPS Benchmark

The "Queries Per Second Benchmark" allows you to get a quick overview of the throughput and
latency characteristics of grpc.

To build the benchmark type

```
$ ./gradlew :grpc-benchmarks:installDist
```

from the grpc-java directory.

You can now find the client and the server executables in `benchmarks/build/install/grpc-benchmarks/bin`.

The `C++` counterpart can be found at https://github.com/grpc/grpc/tree/master/test/cpp/qps

## Visualizing the Latency Distribution

The QPS client comes with the option `--dump_histogram=FILE`, if set it serializes the histogram
to `FILE` which can then be used with a plotter to visualize the latency distribution. The
histogram is stored in the file format of [HdrHistogram](http://hdrhistogram.org/). That way it
can be plotted very easily using a browser based tool like
http://hdrhistogram.github.io/HdrHistogram/plotFiles.html. Simply upload the generated file and it
will generate a beautiful graph for you. It also allows you to plot two or more histograms on the
same surface in order two easily compare latency distributions.
