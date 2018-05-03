FROM protoc-artifacts:latest

# protoc-artifacts base image is ancient and curl does
# not work properly.
RUN yum -y update || true # Update to 4/24/2018 or newer, ignore rpmdb checksum errors
RUN yum clean all
COPY scl-enable-devtoolset.sh /

# Start in devtoolset environment that uses GCC 4.7
ENTRYPOINT ["/scl-enable-devtoolset.sh"]
