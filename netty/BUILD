package(default_visibility = ["//visibility:public"])

java_library(
    name = "netty",
    srcs = glob([
        "src/main/java/**/*.java",
        "third_party/netty/java/**/*.java",
    ]),
    resources = glob([
        "src/main/resources/**/*",
    ]),
    deps = [
        "//core",
        "//core:internal",
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
