# Copyright 2024 gRPC authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Stage 1: Build CSM client
#

FROM eclipse-temurin:11-jdk AS build

WORKDIR /grpc-java/examples
COPY . .

RUN cd example-gcp-csm-observability && ../gradlew installDist -PskipCodegen=true -PskipAndroid=true

#
# Stage 2:
#
# - Copy only the necessary files to reduce Docker image size.
# - Have an ENTRYPOINT script which will launch the CSM client
#   with the given parameters.
#

FROM eclipse-temurin:11-jre

WORKDIR /grpc-java/
COPY --from=build /grpc-java/examples/example-gcp-csm-observability/build/install/example-gcp-csm-observability/. .

# Intentionally after the COPY to force the update on each build.
# Update Ubuntu system packages:
RUN apt-get update \
    && apt-get -y upgrade \
    && apt-get -y autoremove \
    && rm -rf /var/lib/apt/lists/*

# Client
ENTRYPOINT ["bin/csm-observability-client"]
