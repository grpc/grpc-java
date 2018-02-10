#!/bin/bash
#
# Build protoc
set -evux -o pipefail

DOWNLOAD_DIR=/tmp/source
# install here so we don't need sudo
INSTALL_DIR="/tmp/protobuf-$PROTOBUF_VERSION/$(uname -s)-$(uname -p)"
CONFIG_FLAGS="--prefix=$INSTALL_DIR ${CONFIG_FLAGS:-}"
echo $CONFIG_FLAGS

mkdir -p $DOWNLOAD_DIR

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
if [[ -f ${INSTALL_DIR}/bin/protoc ]]; then
  echo "Not building protobuf. Already built"
# TODO(ejona): swap to `brew install --devel protobuf` once it is up-to-date
else
  if [[ ! -f "$DOWNLOAD_DIR/protobof-${PROTOBUF_VERSION}" ]]; then
    wget -O - https://github.com/google/protobuf/archive/v${PROTOBUF_VERSION}.tar.gz | tar xz -C $DOWNLOAD_DIR
  fi
  pushd $DOWNLOAD_DIR/protobuf-${PROTOBUF_VERSION}
  ./autogen.sh
  ./configure $CONFIG_FLAGS
  make clean # in case we are building again with different flags
  make -j$NUM_CPU
  make install
  popd
fi

