"""External dependencies for grpc-java."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

# For use with maven_install's artifacts.
# maven_install(
#     ...
#     artifacts = [
#         # Your own deps
#     ] + IO_GRPC_GRPC_JAVA_ARTIFACTS,
# )
# GRPC_DEPS_START
IO_GRPC_GRPC_JAVA_ARTIFACTS = [
    "com.google.android:annotations:4.1.1.4",
    "com.google.api.grpc:proto-google-common-protos:2.29.0",
    "com.google.auth:google-auth-library-credentials:1.22.0",
    "com.google.auth:google-auth-library-oauth2-http:1.22.0",
    "com.google.auto.value:auto-value-annotations:1.10.4",
    "com.google.auto.value:auto-value:1.10.4",
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.code.gson:gson:2.10.1",
    "com.google.errorprone:error_prone_annotations:2.23.0",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:guava:32.1.3-android",
    "com.google.re2j:re2j:1.7",
    "com.google.truth:truth:1.1.5",
    "com.squareup.okhttp:okhttp:2.7.5",
    "com.squareup.okio:okio:2.10.0",  # 3.0+ needs swapping to -jvm; need work to avoid flag-day
    "io.netty:netty-buffer:4.1.110.Final",
    "io.netty:netty-codec-http2:4.1.110.Final",
    "io.netty:netty-codec-http:4.1.110.Final",
    "io.netty:netty-codec-socks:4.1.110.Final",
    "io.netty:netty-codec:4.1.110.Final",
    "io.netty:netty-common:4.1.110.Final",
    "io.netty:netty-handler-proxy:4.1.110.Final",
    "io.netty:netty-handler:4.1.110.Final",
    "io.netty:netty-resolver:4.1.110.Final",
    "io.netty:netty-tcnative-boringssl-static:2.0.65.Final",
    "io.netty:netty-tcnative-classes:2.0.65.Final",
    "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.110.Final",
    "io.netty:netty-transport-native-unix-common:4.1.110.Final",
    "io.netty:netty-transport:4.1.110.Final",
    "io.opencensus:opencensus-api:0.31.0",
    "io.opencensus:opencensus-contrib-grpc-metrics:0.31.0",
    "io.perfmark:perfmark-api:0.26.0",
    "junit:junit:4.13.2",
    "org.apache.tomcat:annotations-api:6.0.53",
    "org.codehaus.mojo:animal-sniffer-annotations:1.23",
]
# GRPC_DEPS_END

# For use with maven_install's override_targets.
# maven_install(
#     ...
#     override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
# )
#
# If you have your own overrides as well, you can use:
#     override_targets = {
#         "your.target:artifact": "@//third_party/artifact",
#     } | IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS = {
    "com.google.protobuf:protobuf-java": "@com_google_protobuf//:protobuf_java",
    "com.google.protobuf:protobuf-java-util": "@com_google_protobuf//:protobuf_java_util",
    "com.google.protobuf:protobuf-javalite": "@com_google_protobuf_javalite//:protobuf_javalite",
    "io.grpc:grpc-alts": "@io_grpc_grpc_java//alts",
    "io.grpc:grpc-api": "@io_grpc_grpc_java//api",
    "io.grpc:grpc-auth": "@io_grpc_grpc_java//auth",
    "io.grpc:grpc-census": "@io_grpc_grpc_java//census",
    "io.grpc:grpc-context": "@io_grpc_grpc_java//context",
    "io.grpc:grpc-core": "@io_grpc_grpc_java//core:core_maven",
    "io.grpc:grpc-googleapis": "@io_grpc_grpc_java//googleapis",
    "io.grpc:grpc-grpclb": "@io_grpc_grpc_java//grpclb",
    "io.grpc:grpc-inprocess": "@io_grpc_grpc_java//inprocess",
    "io.grpc:grpc-netty": "@io_grpc_grpc_java//netty",
    "io.grpc:grpc-netty-shaded": "@io_grpc_grpc_java//netty:shaded_maven",
    "io.grpc:grpc-okhttp": "@io_grpc_grpc_java//okhttp",
    "io.grpc:grpc-protobuf": "@io_grpc_grpc_java//protobuf",
    "io.grpc:grpc-protobuf-lite": "@io_grpc_grpc_java//protobuf-lite",
    "io.grpc:grpc-rls": "@io_grpc_grpc_java//rls",
    "io.grpc:grpc-services": "@io_grpc_grpc_java//services:services_maven",
    "io.grpc:grpc-stub": "@io_grpc_grpc_java//stub",
    "io.grpc:grpc-testing": "@io_grpc_grpc_java//testing",
    "io.grpc:grpc-xds": "@io_grpc_grpc_java//xds:xds_maven",
    "io.grpc:grpc-util": "@io_grpc_grpc_java//util",
}

def grpc_java_repositories(bzlmod = False):
    """Imports dependencies for grpc-java."""
    if not native.existing_rule("com_github_cncf_udpa"):
        http_archive(
            name = "com_github_cncf_udpa",
            sha256 = "0d33b83f8c6368954e72e7785539f0d272a8aba2f6e2e336ed15fd1514bc9899",
            strip_prefix = "xds-e9ce68804cb4e64cab5a52e3c8baf840d4ff87b7",
            urls = [
                "https://storage.googleapis.com/grpc-bazel-mirror/github.com/cncf/xds/archive/e9ce68804cb4e64cab5a52e3c8baf840d4ff87b7.tar.gz",
                "https://github.com/cncf/xds/archive/e9ce68804cb4e64cab5a52e3c8baf840d4ff87b7.tar.gz",
            ],
        )
    if not native.existing_rule("com_github_cncf_xds"):
        http_archive(
            name = "com_github_cncf_xds",
            strip_prefix = "xds-e9ce68804cb4e64cab5a52e3c8baf840d4ff87b7",
            sha256 = "0d33b83f8c6368954e72e7785539f0d272a8aba2f6e2e336ed15fd1514bc9899",
            urls = [
                "https://github.com/cncf/xds/archive/e9ce68804cb4e64cab5a52e3c8baf840d4ff87b7.tar.gz",
            ],
        )
    if not bzlmod and not native.existing_rule("com_github_grpc_grpc"):
        http_archive(
            name = "com_github_grpc_grpc",
            strip_prefix = "grpc-1.46.0",
            sha256 = "67423a4cd706ce16a88d1549297023f0f9f0d695a96dd684adc21e67b021f9bc",
            urls = [
                "https://github.com/grpc/grpc/archive/v1.46.0.tar.gz",
            ],
        )
    if not bzlmod and not native.existing_rule("com_google_protobuf"):
        com_google_protobuf()
    if not native.existing_rule("com_google_protobuf_javalite"):
        com_google_protobuf_javalite()
    if not bzlmod and not native.existing_rule("com_google_googleapis"):
        http_archive(
            name = "com_google_googleapis",
            sha256 = "49930468563dd48283e8301e8d4e71436bf6d27ac27c235224cc1a098710835d",
            strip_prefix = "googleapis-ca1372c6d7bcb199638ebfdb40d2b2660bab7b88",
            urls = [
                "https://github.com/googleapis/googleapis/archive/ca1372c6d7bcb199638ebfdb40d2b2660bab7b88.tar.gz",
            ],
        )
    if not bzlmod and not native.existing_rule("io_bazel_rules_go"):
        http_archive(
            name = "io_bazel_rules_go",
            sha256 = "ab21448cef298740765f33a7f5acee0607203e4ea321219f2a4c85a6e0fb0a27",
            urls = [
                "https://mirror.bazel.build/github.com/bazelbuild/rules_go/releases/download/v0.32.0/rules_go-v0.32.0.zip",
                "https://github.com/bazelbuild/rules_go/releases/download/v0.32.0/rules_go-v0.32.0.zip",
            ],
        )
    if not native.existing_rule("io_grpc_grpc_proto"):
        io_grpc_grpc_proto()
    if not native.existing_rule("envoy_api"):
        http_archive(
            name = "envoy_api",
            sha256 = "fff067a5d6d776fc88549b5dd4773a6f8f0187b26a859de8b29bd4226a28ee63",
            strip_prefix = "data-plane-api-9d6ffa70677c4dbf23f6ed569676206c4e2edff4",
            urls = [
                "https://storage.googleapis.com/grpc-bazel-mirror/github.com/envoyproxy/data-plane-api/archive/9d6ffa70677c4dbf23f6ed569676206c4e2edff4.tar.gz",
                "https://github.com/envoyproxy/data-plane-api/archive/9d6ffa70677c4dbf23f6ed569676206c4e2edff4.tar.gz",
            ],
        )

def com_google_protobuf():
    # proto_library rules implicitly depend on @com_google_protobuf//:protoc,
    # which is the proto-compiler.
    # This statement defines the @com_google_protobuf repo.
    http_archive(
        name = "com_google_protobuf",
        sha256 = "9bd87b8280ef720d3240514f884e56a712f2218f0d693b48050c836028940a42",
        strip_prefix = "protobuf-25.1",
        urls = ["https://github.com/protocolbuffers/protobuf/releases/download/v25.1/protobuf-25.1.tar.gz"],
    )

def com_google_protobuf_javalite():
    # java_lite_proto_library rules implicitly depend on @com_google_protobuf_javalite
    http_archive(
        name = "com_google_protobuf_javalite",
        sha256 = "9bd87b8280ef720d3240514f884e56a712f2218f0d693b48050c836028940a42",
        strip_prefix = "protobuf-25.1",
        urls = ["https://github.com/protocolbuffers/protobuf/releases/download/v25.1/protobuf-25.1.tar.gz"],
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "729ac127a003836d539ed9da72a21e094aac4c4609e0481d6fc9e28a844e11af",
        strip_prefix = "grpc-proto-4f245d272a28a680606c0739753506880cf33b5f",
        urls = ["https://github.com/grpc/grpc-proto/archive/4f245d272a28a680606c0739753506880cf33b5f.zip"],
    )

def _grpc_java_repositories_extension(_):
    grpc_java_repositories(bzlmod = True)

grpc_java_repositories_extension = module_extension(implementation = _grpc_java_repositories_extension)
