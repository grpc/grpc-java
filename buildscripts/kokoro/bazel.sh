#!/bin/bash

set -exu -o pipefail
cat /VERSION

use_bazel.sh 0.28.1
bazel version

cd github/grpc-java
bazel build ...

cd examples
bazel clean
bazel build ...
