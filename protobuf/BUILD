package(default_visibility = ["//visibility:public"])

java_library(
    name = "protobuf",
    srcs = glob([
        "src/main/java/**/*.java",
    ]),
    deps = [
        "//core",
        "//protobuf-lite",
        "@com_google_api_grpc_google_common_protos",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)
