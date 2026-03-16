FROM eclipse-temurin:17-jdk

# The AppEngine Gradle plugin downloads and runs its own gcloud to get the .jar
# to link against, so we need Python even if we use gcloud deploy directly
# instead of using the plugin.
RUN export DEBIAN_FRONTEND=noninteractive && \
    apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends python3 && \
    rm -rf /var/lib/apt/lists/*
