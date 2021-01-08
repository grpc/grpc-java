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
    "com.google.api.grpc:proto-google-common-protos:2.0.1",
    "com.google.auth:google-auth-library-credentials:0.22.0",
    "com.google.auth:google-auth-library-oauth2-http:0.22.0",
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.code.gson:gson:jar:2.8.6",
    "com.google.errorprone:error_prone_annotations:2.4.0",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:guava:29.0-android",
    "com.google.j2objc:j2objc-annotations:1.3",
    "com.google.truth:truth:1.0.1",
    "com.squareup.okhttp:okhttp:2.7.4",
    "com.squareup.okio:okio:1.17.5",
    "io.netty:netty-buffer:4.1.52.Final",
    "io.netty:netty-codec-http2:4.1.52.Final",
    "io.netty:netty-codec-http:4.1.52.Final",
    "io.netty:netty-codec-socks:4.1.52.Final",
    "io.netty:netty-codec:4.1.52.Final",
    "io.netty:netty-common:4.1.52.Final",
    "io.netty:netty-handler-proxy:4.1.52.Final",
    "io.netty:netty-handler:4.1.52.Final",
    "io.netty:netty-resolver:4.1.52.Final",
    "io.netty:netty-tcnative-boringssl-static:2.0.34.Final",
    "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.52.Final",
    "io.netty:netty-transport:4.1.52.Final",
    "io.opencensus:opencensus-api:0.24.0",
    "io.opencensus:opencensus-contrib-grpc-metrics:0.24.0",
    "io.perfmark:perfmark-api:0.23.0",
    "junit:junit:4.12",
    "org.apache.tomcat:annotations-api:6.0.53",
    "org.codehaus.mojo:animal-sniffer-annotations:1.19",
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
    "io.grpc:grpc-grpclb": "@io_grpc_grpc_java//grpclb",
    "io.grpc:grpc-netty": "@io_grpc_grpc_java//netty",
    "io.grpc:grpc-netty-shaded": "@io_grpc_grpc_java//netty:shaded_maven",
    "io.grpc:grpc-okhttp": "@io_grpc_grpc_java//okhttp",
    "io.grpc:grpc-protobuf": "@io_grpc_grpc_java//protobuf",
    "io.grpc:grpc-protobuf-lite": "@io_grpc_grpc_java//protobuf-lite",
    "io.grpc:grpc-stub": "@io_grpc_grpc_java//stub",
    "io.grpc:grpc-testing": "@io_grpc_grpc_java//testing",
}

def grpc_java_repositories():
    """Imports dependencies for grpc-java."""
    if not native.existing_rule("com_google_protobuf"):
        com_google_protobuf()
    if not native.existing_rule("com_google_protobuf_javalite"):
        com_google_protobuf_javalite()
    if not native.existing_rule("io_grpc_grpc_proto"):
        io_grpc_grpc_proto()

    native.bind(
        name = "guava",
        actual = "@com_google_guava_guava//jar",
    )
    native.bind(
        name = "gson",
        actual = "@com_google_code_gson_gson//jar",
    )
    native.bind(
        name = "error_prone_annotations",
        actual = "@com_google_errorprone_error_prone_annotations//jar",
    )

def com_google_protobuf():
    # proto_library rules implicitly depend on @com_google_protobuf//:protoc,
    # which is the proto-compiler.
    # This statement defines the @com_google_protobuf repo.
    http_archive(
        name = "com_google_protobuf",
        sha256 = "b37e96e81842af659605908a421960a5dc809acbc888f6b947bc320f8628e5b1",
        strip_prefix = "protobuf-3.12.0",
        urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.12.0.zip"],
    )

def com_google_protobuf_javalite():
    # java_lite_proto_library rules implicitly depend on @com_google_protobuf_javalite
    http_archive(
        name = "com_google_protobuf_javalite",
        sha256 = "b37e96e81842af659605908a421960a5dc809acbc888f6b947bc320f8628e5b1",
        strip_prefix = "protobuf-3.12.0",
        urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.12.0.zip"],
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "5848a4e034126bece0c37c16554fb80625615aedf1acad4e2a3cdbaaa76944eb",
        strip_prefix = "grpc-proto-cf828d0e1155e5ea58b46d7184ee5596e03ddcb8",
        urls = ["https://github.com/grpc/grpc-proto/archive/cf828d0e1155e5ea58b46d7184ee5596e03ddcb8.zip"],
    )
