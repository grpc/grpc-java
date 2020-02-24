"""External dependencies for grpc-java."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:jvm.bzl", "jvm_maven_import_external")

# For use with maven_install's artifacts.
# maven_install(
#     ...
#     artifacts = [
#         # Your own deps
#     ] + IO_GRPC_GRPC_JAVA_ARTIFACTS,
# )
IO_GRPC_GRPC_JAVA_ARTIFACTS = [
    "com.google.android:annotations:4.1.1.4",
    "com.google.api.grpc:proto-google-common-protos:1.17.0",
    "com.google.auth:google-auth-library-credentials:0.20.0",
    "com.google.auth:google-auth-library-oauth2-http:0.20.0",
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.code.gson:gson:jar:2.8.6",
    "com.google.errorprone:error_prone_annotations:2.3.4",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:guava:28.1-android",
    "com.google.j2objc:j2objc-annotations:1.3",
    "com.google.truth:truth:1.0",
    "com.squareup.okhttp:okhttp:2.7.4",
    "com.squareup.okio:okio:1.13.0",
    "io.netty:netty-buffer:4.1.45.Final",
    "io.netty:netty-codec-http2:4.1.45.Final",
    "io.netty:netty-codec-http:4.1.45.Final",
    "io.netty:netty-codec-socks:4.1.45.Final",
    "io.netty:netty-codec:4.1.45.Final",
    "io.netty:netty-common:4.1.45.Final",
    "io.netty:netty-handler-proxy:4.1.45.Final",
    "io.netty:netty-handler:4.1.45.Final",
    "io.netty:netty-resolver:4.1.45.Final",
    "io.netty:netty-tcnative-boringssl-static:2.0.28.Final",
    "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.45.Final",
    "io.netty:netty-transport:4.1.45.Final",
    "io.opencensus:opencensus-api:0.24.0",
    "io.opencensus:opencensus-contrib-grpc-metrics:0.24.0",
    "io.perfmark:perfmark-api:0.19.0",
    "javax.annotation:javax.annotation-api:1.2",
    "junit:junit:4.12",
    "org.apache.commons:commons-lang3:3.5",
    "org.codehaus.mojo:animal-sniffer-annotations:1.18",
]

# For use with maven_install's override_targets.
# maven_install(
#     ...
#     override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
# )
#
# If you have your own overrides as well, you can use:
#     override_targets = dict(
#         IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
#         "your.target:artifact": "@//third_party/artifact",
#     )
#
# To combine OVERRIDE_TARGETS from multiple libraries:
#     override_targets = dict(
#         IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS.items() +
#         OTHER_OVERRIDE_TARGETS.items(),
#         "your.target:artifact": "@//third_party/artifact",
#     )
IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS = {
    "com.google.protobuf:protobuf-java": "@com_google_protobuf//:protobuf_java",
    "com.google.protobuf:protobuf-java-util": "@com_google_protobuf//:protobuf_java_util",
    "com.google.protobuf:protobuf-javalite": "@com_google_protobuf_javalite//:protobuf_java_lite",
    "io.grpc:grpc-alts": "@io_grpc_grpc_java//alts",
    "io.grpc:grpc-api": "@io_grpc_grpc_java//api",
    "io.grpc:grpc-auth": "@io_grpc_grpc_java//auth",
    "io.grpc:grpc-census": "@io_grpc_grpc_java//census",
    "io.grpc:grpc-context": "@io_grpc_grpc_java//context",
    "io.grpc:grpc-core": "@io_grpc_grpc_java//core:core_maven",
    "io.grpc:grpc-grpclb": "@io_grpc_grpc_java//grpclb",
    "io.grpc:grpc-netty": "@io_grpc_grpc_java//netty",
    "io.grpc:grpc-netty-shaded": "@io_grpc_grpc_java//netty:shaded_maven",
    "io.grpc:grpc-okhttp": "@io_grpc_grpc_java//okhttp",
    "io.grpc:grpc-protobuf": "@io_grpc_grpc_java//protobuf",
    "io.grpc:grpc-protobuf-lite": "@io_grpc_grpc_java//protobuf-lite",
    "io.grpc:grpc-stub": "@io_grpc_grpc_java//stub",
    "io.grpc:grpc-testing": "@io_grpc_grpc_java//testing",
}

def grpc_java_repositories():
    """Imports dependencies for grpc-java."""
    if not native.existing_rule("com_google_protobuf"):
        com_google_protobuf()
    if not native.existing_rule("com_google_protobuf_javalite"):
        com_google_protobuf_javalite()
    if not native.existing_rule("io_grpc_grpc_proto"):
        io_grpc_grpc_proto()

    if not native.existing_rule("com_google_android_annotations"):
        com_google_android_annotations()
    if not native.existing_rule("com_google_api_grpc_proto_google_common_protos"):
        com_google_api_grpc_proto_google_common_protos()
    if not native.existing_rule("com_google_auth_google_auth_library_credentials"):
        com_google_auth_google_auth_library_credentials()
    if not native.existing_rule("com_google_auth_google_auth_library_oauth2_http"):
        com_google_auth_google_auth_library_oauth2_http()
    if not native.existing_rule("com_google_code_findbugs_jsr305"):
        com_google_code_findbugs_jsr305()
    if not native.existing_rule("com_google_code_gson_gson"):
        com_google_code_gson_gson()
    if not native.existing_rule("com_google_errorprone_error_prone_annotations"):
        com_google_errorprone_error_prone_annotations()
    if not native.existing_rule("com_google_guava_guava"):
        com_google_guava_guava()
    if not native.existing_rule("com_google_guava_failureaccess"):
        com_google_guava_failureaccess()
    if not native.existing_rule("com_google_j2objc_j2objc_annotations"):
        com_google_j2objc_j2objc_annotations()
    if not native.existing_rule("com_google_truth_truth"):
        com_google_truth_truth()
    if not native.existing_rule("com_squareup_okhttp_okhttp"):
        com_squareup_okhttp_okhttp()
    if not native.existing_rule("com_squareup_okio_okio"):
        com_squareup_okio_okio()
    if not native.existing_rule("io_netty_netty_buffer"):
        io_netty_netty_buffer()
    if not native.existing_rule("io_netty_netty_codec"):
        io_netty_netty_codec()
    if not native.existing_rule("io_netty_netty_codec_http"):
        io_netty_netty_codec_http()
    if not native.existing_rule("io_netty_netty_codec_http2"):
        io_netty_netty_codec_http2()
    if not native.existing_rule("io_netty_netty_codec_socks"):
        io_netty_netty_codec_socks()
    if not native.existing_rule("io_netty_netty_common"):
        io_netty_netty_common()
    if not native.existing_rule("io_netty_netty_handler"):
        io_netty_netty_handler()
    if not native.existing_rule("io_netty_netty_handler_proxy"):
        io_netty_netty_handler_proxy()
    if not native.existing_rule("io_netty_netty_resolver"):
        io_netty_netty_resolver()
    if not native.existing_rule("io_netty_netty_tcnative_boringssl_static"):
        io_netty_netty_tcnative_boringssl_static()
    if not native.existing_rule("io_netty_netty_transport"):
        io_netty_netty_transport()
    if not native.existing_rule("io_netty_netty_transport_native_epoll_linux_x86_64"):
        io_netty_netty_transport_native_epoll_linux_x86_64()
    if not native.existing_rule("io_opencensus_opencensus_api"):
        io_opencensus_opencensus_api()
    if not native.existing_rule("io_opencensus_opencensus_contrib_grpc_metrics"):
        io_opencensus_opencensus_contrib_grpc_metrics()
    if not native.existing_rule("io_perfmark_perfmark_api"):
        io_perfmark_perfmark_api()
    if not native.existing_rule("javax_annotation_javax_annotation_api"):
        javax_annotation_javax_annotation_api()
    if not native.existing_rule("junit_junit"):
        junit_junit()
    if not native.existing_rule("org_apache_commons_commons_lang3"):
        org_apache_commons_commons_lang3()
    if not native.existing_rule("org_codehaus_mojo_animal_sniffer_annotations"):
        org_codehaus_mojo_animal_sniffer_annotations()

    native.bind(
        name = "guava",
        actual = "@com_google_guava_guava//jar",
    )
    native.bind(
        name = "gson",
        actual = "@com_google_code_gson_gson//jar",
    )
    native.bind(
        name = "error_prone_annotations",
        actual = "@com_google_errorprone_error_prone_annotations//jar",
    )

def com_google_android_annotations():
    jvm_maven_import_external(
        name = "com_google_android_annotations",
        artifact = "com.google.android:annotations:4.1.1.4",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "ba734e1e84c09d615af6a09d33034b4f0442f8772dec120efb376d86a565ae15",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_api_grpc_proto_google_common_protos():
    jvm_maven_import_external(
        name = "com_google_api_grpc_proto_google_common_protos",
        artifact = "com.google.api.grpc:proto-google-common-protos:1.17.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "ad25472c73ee470606fb500b376ae5a97973d5406c2f5c3b7d07fb25b4648b65",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_auth_google_auth_library_credentials():
    jvm_maven_import_external(
        name = "com_google_auth_google_auth_library_credentials",
        artifact = "com.google.auth:google-auth-library-credentials:0.20.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "8a415273a5dae5c8f9080134e53b9592dc171ca5d13127488c910177c5903bd6",
        licenses = ["notice"],  # BSD 3-clause
    )

def com_google_auth_google_auth_library_oauth2_http():
    jvm_maven_import_external(
        name = "com_google_auth_google_auth_library_oauth2_http",
        artifact = "com.google.auth:google-auth-library-oauth2-http:0.20.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "43e96e8c07285c2887042eda4e35ca96522ef361f6c1843f469039d9ccdc8f8a",
        licenses = ["notice"],  # BSD 3-clause
    )

def com_google_code_findbugs_jsr305():
    jvm_maven_import_external(
        name = "com_google_code_findbugs_jsr305",
        artifact = "com.google.code.findbugs:jsr305:3.0.2",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_code_gson_gson():
    jvm_maven_import_external(
        name = "com_google_code_gson_gson",
        artifact = "com.google.code.gson:gson:jar:2.8.6",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "c8fb4839054d280b3033f800d1f5a97de2f028eb8ba2eb458ad287e536f3f25f",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_errorprone_error_prone_annotations():
    jvm_maven_import_external(
        name = "com_google_errorprone_error_prone_annotations",
        artifact = "com.google.errorprone:error_prone_annotations:2.3.4",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "baf7d6ea97ce606c53e11b6854ba5f2ce7ef5c24dddf0afa18d1260bd25b002c",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_guava_guava():
    jvm_maven_import_external(
        name = "com_google_guava_guava",
        artifact = "com.google.guava:guava:28.1-android",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "e112ce92c0f0733965eede73d94589c59a72128b06b08bba5ebe2f9ea672ef60",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_guava_failureaccess():
    # Not needed until Guava 27.0, but including now to ease upgrading of users. See #5214
    jvm_maven_import_external(
        name = "com_google_guava_failureaccess",
        artifact = "com.google.guava:failureaccess:1.0.1",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_j2objc_j2objc_annotations():
    jvm_maven_import_external(
        name = "com_google_j2objc_j2objc_annotations",
        artifact = "com.google.j2objc:j2objc-annotations:1.3",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "21af30c92267bd6122c0e0b4d20cccb6641a37eaf956c6540ec471d584e64a7b",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_protobuf():
    # proto_library rules implicitly depend on @com_google_protobuf//:protoc,
    # which is the proto-compiler.
    # This statement defines the @com_google_protobuf repo.
    http_archive(
        name = "com_google_protobuf",
        sha256 = "60d2012e3922e429294d3a4ac31f336016514a91e5a63fd33f35743ccfe1bd7d",
        strip_prefix = "protobuf-3.11.0",
        urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.11.0.zip"],
    )

def com_google_protobuf_javalite():
    # java_lite_proto_library rules implicitly depend on @com_google_protobuf_javalite
    http_archive(
        name = "com_google_protobuf_javalite",
        sha256 = "e60211a40473f6be95b53f64559f82a3b2971672b11710db2fc9081708e25699",
        strip_prefix = "protobuf-0425fa932ce95a32bb9f88b2c09b995e9ff8207b",
        urls = ["https://github.com/google/protobuf/archive/0425fa932ce95a32bb9f88b2c09b995e9ff8207b.zip"],  # Commit with fixed javalite on 3.11.x branch
    )

def com_google_truth_truth():
    jvm_maven_import_external(
        name = "com_google_truth_truth",
        artifact = "com.google.truth:truth:1.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "edaa12f3b581fcf1c07311e94af8766919c4f3d904b00d3503147b99bf5b4004",
        licenses = ["notice"],  # Apache 2.0
    )

def com_squareup_okhttp_okhttp():
    jvm_maven_import_external(
        name = "com_squareup_okhttp_okhttp",
        artifact = "com.squareup.okhttp:okhttp:2.7.4",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "c88be9af1509d5aeec9394a818c0fa08e26fad9d64ba134e6f977e0bb20cb114",
        licenses = ["notice"],  # Apache 2.0
    )

def com_squareup_okio_okio():
    jvm_maven_import_external(
        name = "com_squareup_okio_okio",
        artifact = "com.squareup.okio:okio:1.13.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "734269c3ebc5090e3b23566db558f421f0b4027277c79ad5d176b8ec168bb850",
        licenses = ["notice"],  # Apache 2.0
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "e3f2bf5caa217b8a703acb52cc5a503f06545b6be7889e44ea428f29e7320e9d",
        strip_prefix = "grpc-proto-1ff7890719cea62825beeff16d6c2c66cf51ea90",
        urls = ["https://github.com/grpc/grpc-proto/archive/1ff7890719cea62825beeff16d6c2c66cf51ea90.zip"],
    )

def io_netty_netty_buffer():
    jvm_maven_import_external(
        name = "io_netty_netty_buffer",
        artifact = "io.netty:netty-buffer:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "8437b43e03c272093066837567e1b89019ef291f06f5ace1051017981d98d59f",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_codec():
    jvm_maven_import_external(
        name = "io_netty_netty_codec",
        artifact = "io.netty:netty-codec:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "47e211ad8c4c2b809b6e04541d6c8e3893dea63918dabe93fa5cf63914ffc9cc",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_codec_http():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_http",
        artifact = "io.netty:netty-codec-http:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "db8d8bf478bd3ad723c3d23fdf1cbf62ab9d419a8636e17add3f82f51f8e0bc1",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_codec_http2():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_http2",
        artifact = "io.netty:netty-codec-http2:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "09f82454ec9d4203b569c6027e11dd2f265aeba4bec5f61cef71845547e61faa",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_codec_socks():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_socks",
        artifact = "io.netty:netty-codec-socks:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "f5aa6197d3df9009bbb889ada2b1ae09b23559ebe748030478652c05a5977a25",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_common():
    jvm_maven_import_external(
        name = "io_netty_netty_common",
        artifact = "io.netty:netty-common:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "6f3c61684cf8c0f09df7ebb5a19df29d5d9fc175ce68ae237993b91366ccc43e",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_handler():
    jvm_maven_import_external(
        name = "io_netty_netty_handler",
        artifact = "io.netty:netty-handler:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "2ad6785ba22fb522dba8128a0599b3f5ee47c210dddb8d8ec678f7765ac406f0",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_handler_proxy():
    jvm_maven_import_external(
        name = "io_netty_netty_handler_proxy",
        artifact = "io.netty:netty-handler-proxy:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "410fc065171e26bb9a24ed5f7f88b3200e641cb65605ec11bc9c7625da28429a",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_resolver():
    jvm_maven_import_external(
        name = "io_netty_netty_resolver",
        artifact = "io.netty:netty-resolver:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "1d762ecfa9da9241db339b611fad0529491bb0c3098c16e9c80d64f04d80323c",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_tcnative_boringssl_static():
    jvm_maven_import_external(
        name = "io_netty_netty_tcnative_boringssl_static",
        artifact = "io.netty:netty-tcnative-boringssl-static:2.0.28.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "2cf327d8185202804c998cc8ae9f493f483c5398c597d5385366c6fc9ad20aef",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_transport():
    jvm_maven_import_external(
        name = "io_netty_netty_transport",
        artifact = "io.netty:netty-transport:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "ca38fc85e9e18c4921d9ce92830445efad05d0fb3e8dd6ba3536e0843cdf723b",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_netty_transport_native_epoll_linux_x86_64():
    jvm_maven_import_external(
        name = "io_netty_netty_transport_native_epoll_linux_x86_64",
        artifact = "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.45.Final",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "3a9a5645177e20adef2fadb0feabba0d544e8f4403b2fc07353658717593af3e",
        licenses = ["notice"],  # Apache 2.0
    )

def io_opencensus_opencensus_api():
    jvm_maven_import_external(
        name = "io_opencensus_opencensus_api",
        artifact = "io.opencensus:opencensus-api:0.24.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "f561b1cc2673844288e596ddf5bb6596868a8472fd2cb8993953fc5c034b2352",
        licenses = ["notice"],  # Apache 2.0
    )

def io_opencensus_opencensus_contrib_grpc_metrics():
    jvm_maven_import_external(
        name = "io_opencensus_opencensus_contrib_grpc_metrics",
        artifact = "io.opencensus:opencensus-contrib-grpc-metrics:0.24.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "875582e093f11950ad3f4a50b5fee33a008023f7d1e47820a1bef05d23b9ed42",
        licenses = ["notice"],  # Apache 2.0
    )

def io_perfmark_perfmark_api():
    jvm_maven_import_external(
        name = "io_perfmark_perfmark_api",
        artifact = "io.perfmark:perfmark-api:0.19.0",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "b734ba2149712409a44eabdb799f64768578fee0defe1418bb108fe32ea43e1a",
        licenses = ["notice"],  # Apache 2.0
    )

def javax_annotation_javax_annotation_api():
    # Use //stub:javax_annotation for neverlink=1 support.
    jvm_maven_import_external(
        name = "javax_annotation_javax_annotation_api",
        artifact = "javax.annotation:javax.annotation-api:1.2",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "5909b396ca3a2be10d0eea32c74ef78d816e1b4ead21de1d78de1f890d033e04",
        licenses = ["reciprocal"],  # CDDL License
    )

def junit_junit():
    jvm_maven_import_external(
        name = "junit_junit",
        artifact = "junit:junit:4.12",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "59721f0805e223d84b90677887d9ff567dc534d7c502ca903c0c2b17f05c116a",
        licenses = ["notice"],  # EPL 1.0
    )

def org_apache_commons_commons_lang3():
    jvm_maven_import_external(
        name = "org_apache_commons_commons_lang3",
        artifact = "org.apache.commons:commons-lang3:3.5",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "8ac96fc686512d777fca85e144f196cd7cfe0c0aec23127229497d1a38ff651c",
        licenses = ["notice"],  # Apache 2.0
    )

def org_codehaus_mojo_animal_sniffer_annotations():
    jvm_maven_import_external(
        name = "org_codehaus_mojo_animal_sniffer_annotations",
        artifact = "org.codehaus.mojo:animal-sniffer-annotations:1.18",
        server_urls = ["https://repo.maven.apache.org/maven2/"],
        artifact_sha256 = "47f05852b48ee9baefef80fa3d8cea60efa4753c0013121dd7fe5eef2e5c729d",
        licenses = ["notice"],  # MIT
    )
