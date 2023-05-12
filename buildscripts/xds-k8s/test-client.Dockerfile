# Build runtime image.
FROM eclipse-temurin:11.0.19_7-jdk

ENV APP_DIR=/usr/src/app
WORKDIR $APP_DIR

# Install the app
COPY grpc-interop-testing/ $APP_DIR/

# Copy all logging profiles, use json logging by default
COPY logging*.properties $APP_DIR/
ENV JAVA_OPTS="-Djava.util.logging.config.file=$APP_DIR/logging-json.properties"

# Client
ENTRYPOINT ["bin/xds-test-client"]
