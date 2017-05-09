package(default_visibility = ["//visibility:public"])

java_library(
    name = "core",
    srcs = glob([
        "src/main/java/io/grpc/*.java",
    ]),
    resources = glob([
        "src/main/resources/**/*",
    ]),
    deps = [
        "//context",
        "@com_google_code_findbugs_jsr305//jar",
        "@com_google_errorprone_error_prone_annotations//jar",
        "@com_google_guava//jar",
        "@com_google_instrumentation_api//jar",
    ],
)

java_library(
    name = "internal",
    srcs = glob([
        "src/main/java/io/grpc/internal/*.java",
    ]),
    deps = [
        ":core",
        "//context",
        "@com_google_code_findbugs_jsr305//jar",
        "@com_google_errorprone_error_prone_annotations//jar",
        "@com_google_guava//jar",
        "@com_google_instrumentation_api//jar",
    ],
)

java_library(
    name = "util",
    srcs = glob([
        "src/main/java/io/grpc/util/*.java",
    ]),
    deps = [
        ":core",
        "@com_google_code_findbugs_jsr305//jar",
        "@com_google_guava//jar",
    ],
)
