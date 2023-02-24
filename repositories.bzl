"""External dependencies for grpc-java."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

# For use with maven_install's artifacts.
# maven_install(
#     ...
#     artifacts = [
#         # Your own deps
#     ] + IO_GRPC_GRPC_JAVA_ARTIFACTS,
# )
IO_GRPC_GRPC_JAVA_ARTIFACTS = [
    "com.google.android:annotations:4.1.1.4",
    "com.google.api.grpc:proto-google-common-protos:2.9.0",
    "com.google.auth:google-auth-library-credentials:0.22.0",
    "com.google.auth:google-auth-library-oauth2-http:0.22.0",
    "com.google.auto.value:auto-value-annotations:1.9",
    "com.google.auto.value:auto-value:1.9",
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.code.gson:gson:2.9.0",
    "com.google.errorprone:error_prone_annotations:2.9.0",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:guava:31.0.1-android",
    "com.google.j2objc:j2objc-annotations:1.3",
    "com.google.re2j:re2j:1.6",
    "com.google.truth:truth:1.0.1",
    "com.squareup.okhttp:okhttp:2.7.5",
    "com.squareup.okio:okio:1.17.5",
    "io.netty:netty-buffer:4.1.87.Final",
    "io.netty:netty-codec-http2:4.1.87.Final",
    "io.netty:netty-codec-http:4.1.87.Final",
    "io.netty:netty-codec-socks:4.1.87.Final",
    "io.netty:netty-codec:4.1.87.Final",
    "io.netty:netty-common:4.1.87.Final",
    "io.netty:netty-handler-proxy:4.1.87.Final",
    "io.netty:netty-handler:4.1.87.Final",
    "io.netty:netty-resolver:4.1.87.Final",
    "io.netty:netty-tcnative-boringssl-static:2.0.56.Final",
    "io.netty:netty-tcnative-classes:2.0.56.Final",
    "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.87.Final",
    "io.netty:netty-transport-native-unix-common:4.1.87.Final",
    "io.netty:netty-transport:4.1.87.Final",
    "io.opencensus:opencensus-api:0.24.0",
    "io.opencensus:opencensus-contrib-grpc-metrics:0.24.0",
    "io.perfmark:perfmark-api:0.25.0",
    "junit:junit:4.12",
    "org.apache.tomcat:annotations-api:6.0.53",
    "org.codehaus.mojo:animal-sniffer-annotations:1.21",
]

# For use with maven_install's override_targets.
# maven_install(
#     ...
#     override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
# )
#
# If you have your own overrides as well, you can use:
#     override_targets = dict(
#         IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
#         "your.target:artifact": "@//third_party/artifact",
#     )
#
# To combine OVERRIDE_TARGETS from multiple libraries:
#     override_targets = dict(
#         IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS.items() +
#         OTHER_OVERRIDE_TARGETS.items(),
#         "your.target:artifact": "@//third_party/artifact",
#     )
IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS = {
    "com.google.protobuf:protobuf-java": "@com_google_protobuf//:protobuf_java",
    "com.google.protobuf:protobuf-java-util": "@com_google_protobuf//:protobuf_java_util",
    "com.google.protobuf:protobuf-javalite": "@com_google_protobuf_javalite//:protobuf_java_lite",
    "io.grpc:grpc-alts": "@io_grpc_grpc_java//alts",
    "io.grpc:grpc-api": "@io_grpc_grpc_java//api",
    "io.grpc:grpc-auth": "@io_grpc_grpc_java//auth",
    "io.grpc:grpc-census": "@io_grpc_grpc_java//census",
    "io.grpc:grpc-context": "@io_grpc_grpc_java//context",
    "io.grpc:grpc-core": "@io_grpc_grpc_java//core:core_maven",
    "io.grpc:grpc-googleapis": "@io_grpc_grpc_java//googleapis",
    "io.grpc:grpc-grpclb": "@io_grpc_grpc_java//grpclb",
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
}

def grpc_java_repositories():
    """Imports dependencies for grpc-java."""
    if not native.existing_rule("com_github_cncf_xds"):
        http_archive(
            name = "com_github_cncf_xds",
            strip_prefix = "xds-06c439db220b89134a8a49bad41994560d6537c6",
            sha256 = "41ea212940ab44bf7f8a8b4169cfbc612ed2166dafabc0a56a8820ef665fc6a4",
            urls = [
                "https://github.com/cncf/xds/archive/06c439db220b89134a8a49bad41994560d6537c6.tar.gz",
            ],
        )
    if not native.existing_rule("com_github_grpc_grpc"):
        http_archive(
            name = "com_github_grpc_grpc",
            strip_prefix = "grpc-1.46.0",
            sha256 = "67423a4cd706ce16a88d1549297023f0f9f0d695a96dd684adc21e67b021f9bc",
            urls = [
                "https://github.com/grpc/grpc/archive/v1.46.0.tar.gz",
            ],
        )
    if not native.existing_rule("com_google_protobuf"):
        com_google_protobuf()
    if not native.existing_rule("com_google_protobuf_javalite"):
        com_google_protobuf_javalite()
    if not native.existing_rule("com_google_googleapis"):
        http_archive(
            name = "com_google_googleapis",
            sha256 = "49930468563dd48283e8301e8d4e71436bf6d27ac27c235224cc1a098710835d",
            strip_prefix = "googleapis-ca1372c6d7bcb199638ebfdb40d2b2660bab7b88",
            urls = [
                "https://github.com/googleapis/googleapis/archive/ca1372c6d7bcb199638ebfdb40d2b2660bab7b88.tar.gz",
            ],
        )
    if not native.existing_rule("io_bazel_rules_go"):
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
            sha256 = "74156c0d8738d0469f23047f0fd0f8846fdd0d59d7b55c76cd8cb9ebf2fa3a01",
            strip_prefix = "data-plane-api-b1d2e441133c00bfe8412dfd6e93ea85e66da9bb",
            urls = [
                "https://github.com/envoyproxy/data-plane-api/archive/b1d2e441133c00bfe8412dfd6e93ea85e66da9bb.tar.gz",
            ],
        )

def com_google_protobuf():
    # proto_library rules implicitly depend on @com_google_protobuf//:protoc,
    # which is the proto-compiler.
    # This statement defines the @com_google_protobuf repo.
    http_archive(
        name = "com_google_protobuf",
        sha256 = "c72840a5081484c4ac20789ea5bb5d5de6bc7c477ad76e7109fda2bc4e630fe6",
        strip_prefix = "protobuf-3.21.7",
        urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.21.7.zip"],
    )

def com_google_protobuf_javalite():
    # java_lite_proto_library rules implicitly depend on @com_google_protobuf_javalite
    http_archive(
        name = "com_google_protobuf_javalite",
        sha256 = "c72840a5081484c4ac20789ea5bb5d5de6bc7c477ad76e7109fda2bc4e630fe6",
        strip_prefix = "protobuf-3.21.7",
        urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.21.7.zip"],
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "464e97a24d7d784d9c94c25fa537ba24127af5aae3edd381007b5b98705a0518",
        strip_prefix = "grpc-proto-08911e9d585cbda3a55eb1dcc4b99c89aebccff8",
        urls = ["https://github.com/grpc/grpc-proto/archive/08911e9d585cbda3a55eb1dcc4b99c89aebccff8.zip"],
    )
