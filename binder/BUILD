# Open source Stubby. World class RPC for the world.

load("//tools/build_defs/android:rules.bzl", "android_library", "android_local_test")

package(
    default_visibility = [
        "//third_party/java/grpc:__pkg__",
        "//third_party/java_src/grpc:__subpackages__",
    ],
)

licenses(["notice"])

android_library(
    name = "binder_util",
    srcs = glob(["src/main/java/io/grpc/binder/util/*.java"]),
    plugins = [
        "//java/com/google/devtools/build/buildjar/plugin/annotations:beta_checker",
    ],
    deps = [
        "//third_party/java/android_libs/guava_jdk5:annotations",
        "//third_party/java/android_libs/guava_jdk5:base",
        "//third_party/java/android_libs/guava_jdk5:primitives",
        "//third_party/java/androidx/annotation",
        "//third_party/java/androidx/lifecycle",
        "//third_party/java/jsr305_annotations",
        "//third_party/java_src/grpc/api:api_android",
        "//third_party/java_src/grpc/core:internal_android",
    ],
)

android_local_test(
    name = "ServiceBindingTest",
    srcs = ["src/test/java/io/grpc/binder/util/ServiceBindingTest.java"],
    custom_package = "io.grpc.binder.util",
    deps = [
        ":binder_util",
        "//third_party/java/androidx/core",
        "//third_party/java/junit",
        "//third_party/java/mockito:mockito2_for_third_party",
        "//third_party/java/truth:truth-android",
        "//third_party/java_src/grpc/api:api_android",
        "//third_party/java_src/grpc/okhttp:okhttp_android",
    ],
)
