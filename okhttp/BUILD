package(default_visibility = ["//visibility:public"])

java_library(
    name = "okhttp",
    srcs = glob([
        "third_party/okhttp/java/**/*.java",
        "src/main/java/**/*.java",
    ]),
    resources = glob([
        "src/main/resources/**/*",
    ]),
    deps = [
        "//core",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_squareup_okhttp",
        "@com_squareup_okio",
    ],
)

