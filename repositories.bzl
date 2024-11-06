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
    "com.google.api.grpc:proto-google-common-protos:2.48.0",
    "com.google.auth:google-auth-library-credentials:1.24.1",
    "com.google.auth:google-auth-library-oauth2-http:1.24.1",
    "com.google.auto.value:auto-value-annotations:1.11.0",
    "com.google.auto.value:auto-value:1.11.0",
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.code.gson:gson:2.11.0",
    "com.google.errorprone:error_prone_annotations:2.30.0",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:guava:33.3.1-android",
    "com.google.re2j:re2j:1.7",
    "com.google.truth:truth:1.4.2",
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
    "io.perfmark:perfmark-api:0.27.0",
    "junit:junit:4.13.2",
    "org.apache.tomcat:annotations-api:6.0.53",
    "org.checkerframework:checker-qual:3.12.0",
    "org.codehaus.mojo:animal-sniffer-annotations:1.24",
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
    "com.google.protobuf:protobuf-javalite": "@com_google_protobuf//:protobuf_javalite",
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
    "io.grpc:grpc-s2a": "@io_grpc_grpc_java//s2a",
    "io.grpc:grpc-testing": "@io_grpc_grpc_java//testing",
    "io.grpc:grpc-xds": "@io_grpc_grpc_java//xds:xds_maven",
    "io.grpc:grpc-util": "@io_grpc_grpc_java//util",
}

def grpc_java_repositories(bzlmod = False):
    """Imports dependencies for grpc-java."""
    if not bzlmod and not native.existing_rule("dev_cel"):
        http_archive(
            name = "dev_cel",
            strip_prefix = "cel-spec-0.15.0",
            sha256 = "3ee09eb69dbe77722e9dee23dc48dc2cd9f765869fcf5ffb1226587c81791a0b",
            urls = [
                "https://github.com/google/cel-spec/archive/refs/tags/v0.15.0.tar.gz",
            ],
        )
    if not native.existing_rule("com_github_cncf_xds"):
        http_archive(
            name = "com_github_cncf_xds",
            strip_prefix = "xds-024c85f92f20cab567a83acc50934c7f9711d124",
            sha256 = "5f403aa681711500ca8e62387be3e37d971977db6e88616fc21862a406430649",
            urls = [
                "https://github.com/cncf/xds/archive/024c85f92f20cab567a83acc50934c7f9711d124.tar.gz",
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
            sha256 = "f439add0cc01f718d53d6feb4d0972ac0d48b3e145c18b53439a3b5148a0cb6e",
            strip_prefix = "data-plane-api-55f8b2351962d84c84a6534da67da1dd9f671c50",
            urls = [
                "https://github.com/envoyproxy/data-plane-api/archive/55f8b2351962d84c84a6534da67da1dd9f671c50.tar.gz",
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
