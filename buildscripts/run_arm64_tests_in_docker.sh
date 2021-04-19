#!/bin/bash
set -ex

readonly grpc_java_dir="$(dirname "$(readlink -f "$0")")/.."

if [[ -t 0 ]]; then
  DOCKER_ARGS="-it"
else
  # The input device on kokoro is not a TTY, so -it does not work.
  DOCKER_ARGS=
fi

docker build -t grpc-java-artifacts-aarch64 "${grpc_java_dir}"/buildscripts/grpc-java-artifacts-aarch64

# build aarch64 protoc artifacts via crosscompilation
# the corresponding codegen tests will be run under and emulator
# (thanks to the binfmt_misc registration, the emulator will be automatically
# used for executing aarch64 binaries, so we can execute the codegen tests
# even though we are in a x86_64 docker container)
docker run $DOCKER_ARGS --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  --user "$(id -u):$(id -g)" \
  -e "JAVA_OPTS=-Duser.home=/grpc-java/.current-user-home -Djava.util.prefs.userRoot=/grpc-java/.current-user-home/.java/.userPrefs" \
  grpc-java-artifacts-aarch64 \
  bash -c "LOCAL_MVN_TEMP=$(mktemp -d) SKIP_TESTS=true ARCH=aarch_64 buildscripts/kokoro/unix.sh"

# build under x64 docker image to save time over building everything under
# aarch64 emulator. We've already built and tested the protoc binaries
# so for the rest of the build we will be using "-PskipCodegen=true"
# avoid further complicating the build.
docker run $DOCKER_ARGS --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  --user "$(id -u):$(id -g)" \
  -e "JAVA_OPTS=-Duser.home=/grpc-java/.current-user-home -Djava.util.prefs.userRoot=/grpc-java/.current-user-home/.java/.userPrefs" \
  openjdk:11-jdk-slim-buster \
  bash -c "./gradlew build -x test -PskipAndroid=true -PskipCodegen=true"

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
exec docker run $DOCKER_ARGS --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  --user "$(id -u):$(id -g)" \
  -e "JAVA_OPTS=-Duser.home=/grpc-java/.current-user-home -Djava.util.prefs.userRoot=/grpc-java/.current-user-home/.java/.userPrefs" \
  arm64v8/openjdk:11-jdk-slim-buster \
  bash -c "./gradlew build -PskipAndroid=true -PskipCodegen=true"
