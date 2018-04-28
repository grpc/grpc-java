FROM protoc-artifacts:latest

# Work around the docker cache. Not really needed
# because Kokoro instances start off with a blank
# slate. But useful to have this here because we may
# want to run these files locally.
RUN echo "cache buster 4/24/2018"

# protoc-artifacts base image is ancient and curl does
# not work properly.
RUN yum -y update; yum clean all || true
COPY scl-enable-devtoolset.sh /

# Start in devtoolset environment that uses GCC 4.7
ENTRYPOINT ["/scl-enable-devtoolset.sh"]
