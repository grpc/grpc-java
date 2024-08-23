#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

# We had problems with random tests timing out because it took seconds to do
# trivial (ns) operations. The Kokoro Mac machines have 2 cores with 4 logical
# threads, so Gradle should be using 4 workers by default.
export GRADLE_FLAGS="${GRADLE_FLAGS:-} --max-workers=2"

. "$GRPC_JAVA_DIR"/buildscripts/kokoro/kokoro.sh
trap spongify_logs EXIT

export -n JAVA_HOME
export PATH="$(/usr/libexec/java_home -v"1.8.0")/bin:${PATH}"

"$GRPC_JAVA_DIR"/buildscripts/kokoro/unix.sh
