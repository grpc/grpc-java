workspace(name = "io_grpc_grpc_java")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("//:repositories.bzl", "IO_GRPC_GRPC_JAVA_ARTIFACTS", "IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS", "grpc_java_repositories")

grpc_java_repositories()

http_archive(
    name = "rules_java",
    sha256 = "47632cc506c858011853073449801d648e10483d4b50e080ec2549a4b2398960",
    urls = [
        "https://github.com/bazelbuild/rules_java/releases/download/8.15.2/rules_java-8.15.2.tar.gz",
    ],
)

load("@com_google_protobuf//:protobuf_deps.bzl", "PROTOBUF_MAVEN_ARTIFACTS", "protobuf_deps")

protobuf_deps()

# Register remote JDK toolchains to safely compile Java targets under Bazel 8.
load("@rules_java//java:rules_java_deps.bzl", "rules_java_dependencies")

rules_java_dependencies()

load("@bazel_features//:deps.bzl", "bazel_features_deps")

bazel_features_deps()

# Must be loaded after bazel_features_deps() to avoid circular deps.
load("@rules_java//java:repositories.bzl", "rules_java_toolchains")

rules_java_toolchains()

load("@bazel_jar_jar//:jar_jar.bzl", "jar_jar_repositories")

jar_jar_repositories()

load("@rules_python//python:repositories.bzl", "py_repositories")

py_repositories()

load("@com_google_googleapis//:repository_rules.bzl", "switched_rules_by_language")

switched_rules_by_language(
    name = "com_google_googleapis_imports",
)

http_archive(
    name = "rules_jvm_external",
    sha256 = "d31e369b854322ca5098ea12c69d7175ded971435e55c18dd9dd5f29cc5249ac",
    strip_prefix = "rules_jvm_external-5.3",
    url = "https://github.com/bazelbuild/rules_jvm_external/releases/download/5.3/rules_jvm_external-5.3.tar.gz",
)

load("@rules_jvm_external//:defs.bzl", "maven_install")

maven_install(
    artifacts = IO_GRPC_GRPC_JAVA_ARTIFACTS + PROTOBUF_MAVEN_ARTIFACTS + [
    # Hack: Android compile-time worker ResourceProcessorBusyBox uses protos internally.
    # This ensures rules_jvm_external generates its namespace target alias,
    # enabling the IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS to override it correctly.
        "com.google.protobuf:protobuf-java-util:3.25.5",
    ],
    override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
    repositories = [
        "https://repo.maven.apache.org/maven2/",
        # For androidx deps (e.g. core) not published to Maven Central.
        "https://maven.google.com",
    ],
    strict_visibility = True,
)

# Define a custom rules_android_maven repository to override its com.google.protobuf
# runtime dependency to point directly to our workspace compiled C++ target.
# This resolves severe runtime-vs-gencode validation crashes inside the precompiled
# ResourceProcessorBusyBox tool when executing under legacy workspace mode.
maven_install(
    name = "rules_android_maven",
    artifacts = [
        "androidx.privacysandbox.tools:tools:1.0.0-alpha06",
        "androidx.privacysandbox.tools:tools-apigenerator:1.0.0-alpha06",
        "androidx.privacysandbox.tools:tools-apipackager:1.0.0-alpha06",
        "androidx.test:core:1.6.0-alpha01",
        "androidx.test.ext:junit:1.2.0-alpha01",
        "com.android.tools.apkdeployer:apkdeployer:8.8.0-alpha05",
        "org.gradle:gradle-core:4.2.1",
        "com.android.tools.build:bundletool:1.15.5",
        "com.android.tools:desugar_jdk_libs_minimal:2.0.4",
        "com.android.tools:desugar_jdk_libs_configuration_minimal:2.0.4",
        "com.android.tools:desugar_jdk_libs_nio:2.0.4",
        "com.android.tools:desugar_jdk_libs_configuration_nio:2.0.4",
        "com.android.tools.build:gradle:8.7.0",
        "com.android.tools:r8:8.5.35",
        "org.bouncycastle:bcprov-jdk18on:1.77",
        "org.hamcrest:hamcrest-core:2.2",
        "org.robolectric:robolectric:4.14.1",
        "com.google.flogger:flogger:0.8",
        "com.google.guava:guava:32.1.2-jre",
        "com.google.truth:truth:1.1.5",
        "info.picocli:picocli:4.7.4",
        "jakarta.inject:jakarta.inject-api:2.0.1",
        "junit:junit:4.13.2",
        "com.beust:jcommander:1.82",
        "com.google.protobuf:protobuf-java:3.25.5",
        "com.google.protobuf:protobuf-java-util:3.25.5",
        "com.google.code.findbugs:jsr305:3.0.2",
        "androidx.databinding:databinding-compiler:8.7.0",
        "org.ow2.asm:asm:9.6",
        "org.ow2.asm:asm-commons:9.6",
        "org.ow2.asm:asm-tree:9.6",
        "org.ow2.asm:asm-util:9.6",
        "com.android.tools.layoutlib:layoutlib-api:30.1.3",
        "com.android:zipflinger:8.7.0",
        "com.android.tools.build:manifest-merger:30.1.3",
        "com.android:signflinger:8.7.0",
        "com.android.tools.build:aapt2-proto:8.6.1-11315950",
        "com.android.tools.analytics-library:protos:30.1.3",
        "com.android.tools.analytics-library:shared:30.1.3",
        "com.android.tools.analytics-library:tracker:30.1.3",
        "com.android.tools:annotations:30.1.3",
        "com.android.tools.build:apksig:8.7.0",
        "com.android.tools.build:apkzlib:8.7.0",
        "com.android.tools.build:builder:8.7.0",
        "com.android.tools.build:builder-model:8.7.0",
        "com.google.auto.value:auto-value:1.11.0",
        "com.google.auto.value:auto-value-annotations:1.11.0",
        "com.google.auto:auto-common:1.2.2",
        "com.google.auto.service:auto-service:1.1.1",
        "com.google.auto.service:auto-service-annotations:1.1.1",
        "com.google.errorprone:error_prone_annotations:2.33.0",
        "com.google.errorprone:error_prone_type_annotations:2.33.0",
        "com.google.errorprone:error_prone_check_api:2.33.0",
        "com.google.errorprone:error_prone_core:2.33.0",
    ],
    override_targets = {
        "com.google.protobuf:protobuf-java": "@com_google_protobuf//:protobuf_java",
        "com.google.protobuf:protobuf-java-util": "@com_google_protobuf//:protobuf_java_util",
    },
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
        "https://repo.gradle.org/gradle/libs-releases",
    ],
    use_starlark_android_rules = True,
    aar_import_bzl_label = "@rules_android//rules:rules.bzl",
)

# Download rules_android prerequisite repositories (such as @robolectric,
# @io_bazel_rules_go, and @bazel_gazelle). This MUST be executed before loading
# @rules_android//:defs.bzl to resolve top-level circular Starlark load statements.
load("@rules_android//:prereqs.bzl", "rules_android_prereqs")

rules_android_prereqs()

load("@rules_android//:defs.bzl", "rules_android_workspace")

rules_android_workspace()

load("@rules_android//rules/android_sdk_repository:rule.bzl", "android_sdk_repository")

android_sdk_repository(
    name = "androidsdk",
)

# Register Android and SDK toolchains.
# Note: @rules_android//toolchains/android_sdk:android_sdk_tools is required
# to bridge legacy generated `@androidsdk` toolchains to modern standalone
# rules_android toolchain type expectations under legacy workspace mode.
register_toolchains(
    "@androidsdk//:all",
    "@rules_android//toolchains/android:all",
    "@rules_android//toolchains/android_sdk:android_sdk_tools",
)
