#!/bin/bash
#
# Build protoc
set -evux -o pipefail

PROTOBUF_VERSION=22.2
ABSL_VERSION=20230125.2

# ARCH is x86_64 bit unless otherwise specified.
ARCH="${ARCH:-x86_64}"
DOWNLOAD_DIR=/tmp/source
INSTALL_DIR="/tmp/protobuf-cache/$PROTOBUF_VERSION/$(uname -s)-$ARCH"
mkdir -p $DOWNLOAD_DIR
cd "$DOWNLOAD_DIR"

# Make protoc
# Can't check for presence of directory as cache auto-creates it.
if [ -f ${INSTALL_DIR}/bin/protoc ]; then
  echo "Not building protobuf. Already built"
# TODO(ejona): swap to `brew install --devel protobuf` once it is up-to-date
else
  if [[ ! -d "protobuf-${PROTOBUF_VERSION}" ]]; then
    curl -Ls "https://github.com/google/protobuf/releases/download/v${PROTOBUF_VERSION}/protobuf-${PROTOBUF_VERSION}.tar.gz" | tar xz
    curl -Ls "https://github.com/abseil/abseil-cpp/archive/refs/tags/${ABSL_VERSION}.tar.gz" | tar xz
    rmdir "protobuf-$PROTOBUF_VERSION/third_party/abseil-cpp"
    mv "abseil-cpp-$ABSL_VERSION" "protobuf-$PROTOBUF_VERSION/third_party/abseil-cpp"
  fi
  # the same source dir is used for 32 and 64 bit builds, so we need to clean stale data first
  rm -rf "$DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}/build"
  mkdir "$DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}/build"
  pushd "$DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}/build"
  # install here so we don't need sudo
  if [[ "$ARCH" == x86_64 ]]; then
    cmake .. -DCMAKE_CXX_STANDARD=14 -Dprotobuf_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR"
  elif [[ "$ARCH" == x86_32* ]]; then
    ./configure CFLAGS=-m${ARCH#*_} CXXFLAGS=-m${ARCH#*_} --disable-shared \
      --prefix="$INSTALL_DIR"
  elif [[ "$ARCH" == aarch* ]]; then
    ./configure --disable-shared --host=aarch64-linux-gnu --prefix="$INSTALL_DIR"
  elif [[ "$ARCH" == ppc* ]]; then
    ./configure --disable-shared --host=powerpc64le-linux-gnu --prefix="$INSTALL_DIR"
  elif [[ "$ARCH" == s390* ]]; then
    ./configure --disable-shared --host=s390x-linux-gnu --prefix="$INSTALL_DIR"
  elif [[ "$ARCH" == loongarch* ]]; then
    ./configure --disable-shared --host=loongarch64-unknown-linux-gnu --prefix="$INSTALL_DIR"
  fi
  cmake --build . -j
  cmake --install .
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

export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS=-I/tmp/protobuf/include
export LD_LIBRARY_PATH=/tmp/protobuf/lib
EOF
