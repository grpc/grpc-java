FROM protoc-artifacts:latest

RUN echo "cache buster 4/24/2018"
RUN yum -y update; yum clean all || true
COPY scl-enable-devtoolset.sh /

# Start in devtoolset environment that uses GCC 4.7
ENTRYPOINT ["/scl-enable-devtoolset.sh"]
