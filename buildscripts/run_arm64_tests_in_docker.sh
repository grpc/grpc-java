#!/bin/bash
set -ex

readonly grpc_java_dir="$(dirname "$(readlink -f "$0")")/.."

# Build magic docker image that can run on x86 host, but looks like an ARM machine
# from the inside (qemu-user-static is used for emulation)
# Run "sudo apt install qemu-user-static binfmt-support" install the emulator
# on the host machine.
# Also see https://ownyourbits.com/2018/06/27/running-and-building-arm-docker-containers-in-x86/
cp /usr/bin/qemu-arm-static "${grpc_java_dir}/buildscripts/grpc-java-linux-arm64-tests"
docker build -t grpc-java-linux-arm64-tests "${grpc_java_dir}/buildscripts/grpc-java-linux-arm64-tests"

if [[ -t 0 ]]; then
  DOCKER_ARGS="-it"
else
  # The input device on kokoro is not a TTY, so -it does not work.
  DOCKER_ARGS=
fi

# - run docker container under current user's UID to avoid polluting the workspace
# - set the user.home property to avoid creating a "?" directory under grpc-java
exec docker run $DOCKER_ARGS --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  --user "$(id -u):$(id -g)" -e "JAVA_OPTS=-Duser.home=/grpc-java/.current-user-home" \
  grpc-java-linux-arm64-tests \
  bash -c "./gradlew build -PskipAndroid=true -PskipCodegen=true"
