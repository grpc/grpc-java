FROM protoc-artifacts:latest

# set via docker flag: --build-arg VAR=<val>
ARG USERNAME
ARG UID
ARG GID

RUN groupadd -g $GID $USERNAME
RUN useradd -m -u $UID -g $GID -s /bin/bash $USERNAME

COPY scl-enable-devtoolset.sh /

# Start in devtoolset environment that uses GCC 4.7
ENTRYPOINT ["/scl-enable-devtoolset.sh"]
