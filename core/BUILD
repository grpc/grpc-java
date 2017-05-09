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
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava",
        "@com_google_instrumentation_api",
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
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava",
        "@com_google_instrumentation_api",
    ],
)

java_library(
    name = "util",
    srcs = glob([
        "src/main/java/io/grpc/util/*.java",
    ]),
    deps = [
        ":core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
    ],
)
