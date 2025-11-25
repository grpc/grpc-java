#!/bin/bash
#
# Build protoc
set -evux -o pipefail

PROTOBUF_VERSION=33.1
ABSL_VERSION=20250127.1
CMAKE_VERSION=3.26.3

# ARCH is x86_64 bit unless otherwise specified.
ARCH="${ARCH:-x86_64}"
DOWNLOAD_DIR=/tmp/source
INSTALL_DIR="/tmp/protobuf-cache/$PROTOBUF_VERSION/$(uname -s)-$ARCH"
BUILDSCRIPTS_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p $DOWNLOAD_DIR
cd "$DOWNLOAD_DIR"

# Start with a sane default
NUM_CPU=4
if [[ $(uname) == 'Linux' ]]; then
    NUM_CPU=$(nproc)
fi
if [[ $(uname) == 'Darwin' ]]; then
    NUM_CPU=$(sysctl -n hw.ncpu)
fi

# Make protoc
# Can't check for presence of directory as cache auto-creates it.
if [ -f ${INSTALL_DIR}/bin/protoc ]; then
  echo "Not building protobuf. Already built"
# TODO(ejona): swap to `brew install --devel protobuf` once it is up-to-date
else
  if [[ ! -d "protobuf-${PROTOBUF_VERSION}" ]]; then
    curl -Ls "https://github.com/google/protobuf/releases/download/v${PROTOBUF_VERSION}/protobuf-${PROTOBUF_VERSION}.tar.gz" | tar xz
    curl -Ls "https://github.com/abseil/abseil-cpp/archive/refs/tags/${ABSL_VERSION}.tar.gz" | tar xz
    mv "abseil-cpp-$ABSL_VERSION" "protobuf-$PROTOBUF_VERSION/third_party/abseil-cpp"
  fi
  # the same source dir is used for 32 and 64 bit builds, so we need to clean stale data first
  rm -rf "$DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}/build"
  mkdir "$DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}/build"
  pushd "$DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}/build"
  # install here so we don't need sudo
  if [[ "$(uname -s)" == "Darwin" ]]; then
    cmake .. \
      -DCMAKE_CXX_STANDARD=17 -Dprotobuf_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
      -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64" \
      -B. || exit 1
  elif [[ "$ARCH" == x86* ]]; then
    CFLAGS=-m${ARCH#*_} CXXFLAGS=-m${ARCH#*_} cmake .. \
      -DCMAKE_CXX_STANDARD=17 -Dprotobuf_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
      -B. || exit 1
  else
    if [[ "$ARCH" == aarch_64 ]]; then
      GCC_ARCH=aarch64-linux-gnu
    elif [[ "$ARCH" == ppcle_64 ]]; then
      GCC_ARCH=powerpc64le-linux-gnu
    elif [[ "$ARCH" == s390_64 ]]; then
      GCC_ARCH=s390x-linux-gnu
    elif [[ "$ARCH" == loongarch_64 ]]; then
      GCC_ARCH=loongarch64-unknown-linux-gnu
    else
      echo "Unknown architecture: $ARCH"
      exit 1
    fi
    cmake .. \
      -DCMAKE_CXX_STANDARD=17 -Dprotobuf_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
      -Dcrosscompile_ARCH="$GCC_ARCH" \
      -DCMAKE_TOOLCHAIN_FILE=$BUILDSCRIPTS_DIR/toolchain.cmake \
      -B. || exit 1
  fi
  export CMAKE_BUILD_PARALLEL_LEVEL="$NUM_CPU"
  cmake --build . || exit 1
  cmake --install . || exit 1
  [ -d "$INSTALL_DIR/lib64" ] && mv "$INSTALL_DIR/lib64" "$INSTALL_DIR/lib"
  popd
fi

# If /tmp/protobuf exists then we just assume it's a symlink created by us.
# It may be that it points to the wrong arch, so we idempotently set it now.
if [[ -L /tmp/protobuf ]]; then
  rm /tmp/protobuf
fi
ln -s "$INSTALL_DIR" /tmp/protobuf

cat <<EOF
To compile with the build dependencies:

export LDFLAGS="$(PKG_CONFIG_PATH=/tmp/protobuf/lib/pkgconfig pkg-config --libs protobuf)"
export CXXFLAGS="$(PKG_CONFIG_PATH=/tmp/protobuf/lib/pkgconfig pkg-config --cflags protobuf)"
export LIBRARY_PATH=/tmp/protobuf/lib
export LD_LIBRARY_PATH=/tmp/protobuf/lib
EOF
