#!/bin/bash
set -veux -o pipefail
CMAKE_VERSION=3.31.10

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

DOWNLOAD_DIR=/tmp/source
mkdir -p ${DOWNLOAD_DIR}
curl -Ls https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-macos-universal.tar.gz | tar xz -C ${DOWNLOAD_DIR}

# We had problems with random tests timing out because it took seconds to do
# trivial (ns) operations. The Kokoro Mac machines have 2 cores with 4 logical
# threads, so Gradle should be using 4 workers by default.
export GRADLE_FLAGS="${GRADLE_FLAGS:-} --max-workers=2"

. "$GRPC_JAVA_DIR"/buildscripts/kokoro/kokoro.sh
trap spongify_logs EXIT

export -n JAVA_HOME
brew install --cask temurin@8
export PATH="$(/usr/libexec/java_home -v"1.8.0")/bin:${DOWNLOAD_DIR}/cmake-${CMAKE_VERSION}-macos-universal/CMake.app/Contents/bin:${PATH}"
brew install maven
export ARCH=aarch_64

"$GRPC_JAVA_DIR"/buildscripts/kokoro/unix.sh
