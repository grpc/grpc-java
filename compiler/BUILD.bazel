package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "grpc_java_plugin",
    srcs = [
        "src/java_plugin/cpp/java_generator.cpp",
        "src/java_plugin/cpp/java_generator.h",
        "src/java_plugin/cpp/java_plugin.cpp",
    ],
    deps = [
        "@com_google_protobuf//:protoc_lib",
    ],
)
