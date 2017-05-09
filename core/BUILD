package(default_visibility = ["//visibility:public"])

java_library(
    name = "core",
    srcs = glob([
        "src/main/java/**/*.java",
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
