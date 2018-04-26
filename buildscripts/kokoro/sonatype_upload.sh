#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR=$(cd $(dirname $0)/../.. && pwd)

# A place holder at the moment

echo "all the artifacts should be here..."
find $KOKORO_GFILE_DIR