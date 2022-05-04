#!/bin/bash
set -ex

readonly grpc_java_dir="$(dirname "$(readlink -f "$0")")/.."

if [[ -t 0 ]]; then
  DOCKER_ARGS="-it"
else
  # The input device on kokoro is not a TTY, so -it does not work.
  DOCKER_ARGS=
fi


cat <<EOF >> "${grpc_java_dir}/gradle.properties"
skipAndroid=true
skipCodegen=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx1024m
EOF

export JAVA_OPTS="-Duser.home=/grpc-java/.current-user-home -Djava.util.prefs.userRoot=/grpc-java/.current-user-home/.java/.userPrefs"

# build under x64 docker image to save time over building everything under
# aarch64 emulator. We've already built and tested the protoc binaries
# so for the rest of the build we will be using "-PskipCodegen=true"
# avoid further complicating the build.
docker run $DOCKER_ARGS --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  --user "$(id -u):$(id -g)" -e JAVA_OPTS \
  openjdk:11-jdk-slim-buster \
  ./gradlew build -x test

# Build and run java tests under aarch64 image.
# To be able to run this docker container on x64 machine, one needs to have
# qemu-user-static properly registered with binfmt_misc.
# The most important flag binfmt_misc flag we need is "F" (set by "--persistent yes"),
# which allows the qemu-aarch64-static binary to be loaded eagerly at the time of registration with binfmt_misc.
# That way, we can emulate aarch64 binaries running inside docker containers transparently, without needing the emulator
# binary to be accessible from the docker image we're emulating.
# Note that on newer distributions (such as glinux), simply "apt install qemu-user-static" is sufficient
# to install qemu-user-static with the right flags.
# A note on the "docker run" args used:
# - run docker container under current user's UID to avoid polluting the workspace
# - set the user.home property to avoid creating a "?" directory under grpc-java
docker run $DOCKER_ARGS --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  --user "$(id -u):$(id -g)" -e JAVA_OPTS \
  arm64v8/openjdk:11-jdk-slim-buster \
  ./gradlew build
