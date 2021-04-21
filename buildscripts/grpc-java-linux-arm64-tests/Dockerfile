# This docker image uses magic to seamlessly run its contents using qemu ARM emulator on x86 host.
# See https://ownyourbits.com/2018/06/27/running-and-building-arm-docker-containers-in-x86/

# We want to test on arm64v8
FROM arm64v8/debian:buster

# Make the host's qemu-arm-static emulator available from inside the docker container.
COPY qemu-arm-static /usr/bin

RUN apt-get update && apt-get install -y --no-install-recommends openjdk-11-jdk-headless && apt-get clean
