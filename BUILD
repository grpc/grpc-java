package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "grpc_java_plugin",
    srcs = [
        "compiler/src/java_plugin/cpp/java_generator.cpp",
        "compiler/src/java_plugin/cpp/java_generator.h",
        "compiler/src/java_plugin/cpp/java_plugin.cpp",
    ],
    deps = [
        "@com_google_protobuf//:protoc_lib",
    ],
)

java_library(
    name = "core",
    srcs = glob([
        "core/src/main/java/**/*.java",
        "context/src/main/java/**/*.java",
    ]),
    resources = glob([
        "core/src/main/resources/**/*",
    ]),
    deps = [
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava",
        "@com_google_instrumentation_api",
    ],
)

java_library(
    name = "grpc-netty",
    srcs = glob([
        "netty/src/main/java/**/*.java",
        "netty/third_party/netty/java/**/*.java",
    ]),
    resources = glob([
        "netty/src/main/resources/**/*",
    ]),
    deps = [
        ":core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava",
        "@io_netty_buffer",
        "@io_netty_codec",
        "@io_netty_codec_http",
        "@io_netty_codec_http2",
        "@io_netty_codec_socks",
        "@io_netty_common",
        "@io_netty_handler",
        "@io_netty_handler_proxy",
        "@io_netty_resolver",
        "@io_netty_transport",
    ],
)

java_library(
    name = "grpc_protobuf",
    srcs = glob([
        "protobuf/src/main/java/**/*.java",
        "protobuf-lite/src/main/java/**/*.java",
    ]),
    deps = [
        ":core",
        "@com_google_api_grpc_google_common_protos",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_library(
    name = "protobuf-nano",
    srcs = glob([
        "protobuf-nano/src/main/java/**/*.java",
    ]),
    deps = [
        ":core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_google_protobuf_nano_protobuf_javanano",
    ],
)

java_library(
    name = "grpc-okhttp",
    srcs = glob([
        "okhttp/third_party/okhttp/java/**/*.java",
        "okhttp/src/main/java/**/*.java",
    ]),
    resources = glob([
        "okhttp/src/main/resources/**/*",
    ]),
    deps = [
        ":core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_squareup_okhttp",
        "@com_squareup_okio",
    ],
)

java_library(
    name = "stub",
    srcs = glob([
        "stub/src/main/java/**/*.java",
    ]),
    deps = [
        ":core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
    ],
)

java_library(
    name = "grpc-java",
    exports = [
        ":core",
        ":stub",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
    ],
)
