"""External dependencies for grpc-java."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:jvm.bzl", "jvm_maven_import_external")

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
    "io.grpc:grpc-alts": "@io_grpc_grpc_java//alts",
    "io.grpc:grpc-api": "@io_grpc_grpc_java//api",
    "io.grpc:grpc-auth": "@io_grpc_grpc_java//auth",
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

def grpc_java_repositories(
        omit_com_google_android_annotations = False,
        omit_com_google_api_grpc_google_common_protos = False,
        omit_com_google_auth_google_auth_library_credentials = False,
        omit_com_google_auth_google_auth_library_oauth2_http = False,
        omit_com_google_code_findbugs_jsr305 = False,
        omit_com_google_code_gson = False,
        omit_com_google_errorprone_error_prone_annotations = False,
        omit_com_google_guava = False,
        omit_com_google_guava_failureaccess = False,
        omit_com_google_j2objc_j2objc_annotations = False,
        omit_com_google_protobuf = False,
        omit_com_google_protobuf_java = False,
        omit_com_google_protobuf_javalite = False,
        omit_com_google_truth_truth = False,
        omit_com_squareup_okhttp = False,
        omit_com_squareup_okio = False,
        omit_io_grpc_grpc_proto = False,
        omit_io_netty_buffer = False,
        omit_io_netty_common = False,
        omit_io_netty_transport = False,
        omit_io_netty_transport_native_epoll = False,
        omit_io_netty_codec = False,
        omit_io_netty_codec_socks = False,
        omit_io_netty_codec_http = False,
        omit_io_netty_codec_http2 = False,
        omit_io_netty_handler = False,
        omit_io_netty_handler_proxy = False,
        omit_io_netty_resolver = False,
        omit_io_netty_tcnative_boringssl_static = False,
        omit_io_opencensus_api = False,
        omit_io_opencensus_grpc_metrics = False,
        omit_io_perfmark = False,
        omit_javax_annotation = False,
        omit_junit_junit = False,
        omit_org_apache_commons_lang3 = False,
        omit_org_codehaus_mojo_animal_sniffer_annotations = False):
    """Imports dependencies for grpc-java."""
    if not omit_com_google_android_annotations:
        com_google_android_annotations()
    if not omit_com_google_api_grpc_google_common_protos:
        com_google_api_grpc_google_common_protos()
    if not omit_com_google_auth_google_auth_library_credentials:
        com_google_auth_google_auth_library_credentials()
    if not omit_com_google_auth_google_auth_library_oauth2_http:
        com_google_auth_google_auth_library_oauth2_http()
    if not omit_com_google_code_findbugs_jsr305:
        com_google_code_findbugs_jsr305()
    if not omit_com_google_code_gson:
        com_google_code_gson()
    if not omit_com_google_errorprone_error_prone_annotations:
        com_google_errorprone_error_prone_annotations()
    if not omit_com_google_guava:
        com_google_guava()
    if not omit_com_google_guava_failureaccess:
        com_google_guava_failureaccess()
    if not omit_com_google_j2objc_j2objc_annotations:
        com_google_j2objc_j2objc_annotations()
    if not omit_com_google_protobuf:
        com_google_protobuf()
    if omit_com_google_protobuf_java:
        fail("omit_com_google_protobuf_java is no longer supported and must be not be passed to grpc_java_repositories()")
    if not omit_com_google_protobuf_javalite:
        com_google_protobuf_javalite()
    if not omit_com_google_truth_truth:
        com_google_truth_truth()
    if not omit_com_squareup_okhttp:
        com_squareup_okhttp()
    if not omit_com_squareup_okio:
        com_squareup_okio()
    if not omit_io_grpc_grpc_proto:
        io_grpc_grpc_proto()
    if not omit_io_netty_buffer:
        io_netty_buffer()
    if not omit_io_netty_common:
        io_netty_common()
    if not omit_io_netty_transport:
        io_netty_transport()
    if not omit_io_netty_transport_native_epoll:
        io_netty_transport_native_epoll()
    if not omit_io_netty_codec:
        io_netty_codec()
    if not omit_io_netty_codec_socks:
        io_netty_codec_socks()
    if not omit_io_netty_codec_http:
        io_netty_codec_http()
    if not omit_io_netty_codec_http2:
        io_netty_codec_http2()
    if not omit_io_netty_handler:
        io_netty_handler()
    if not omit_io_netty_handler_proxy:
        io_netty_handler_proxy()
    if not omit_io_netty_resolver:
        io_netty_resolver()
    if not omit_io_netty_tcnative_boringssl_static:
        io_netty_tcnative_boringssl_static()
    if not omit_io_opencensus_api:
        io_opencensus_api()
    if not omit_io_opencensus_grpc_metrics:
        io_opencensus_grpc_metrics()
    if not omit_io_perfmark:
        io_perfmark()
    if not omit_javax_annotation:
        javax_annotation()
    if not omit_junit_junit:
        junit_junit()
    if not omit_org_apache_commons_lang3:
        org_apache_commons_lang3()
    if not omit_org_codehaus_mojo_animal_sniffer_annotations:
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
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "ba734e1e84c09d615af6a09d33034b4f0442f8772dec120efb376d86a565ae15",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_api_grpc_google_common_protos():
    jvm_maven_import_external(
        name = "com_google_api_grpc_proto_google_common_protos",
        artifact = "com.google.api.grpc:proto-google-common-protos:1.12.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "bd60cd7a423b00fb824c27bdd0293aaf4781be1daba6ed256311103fb4b84108",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_auth_google_auth_library_credentials():
    jvm_maven_import_external(
        name = "com_google_auth_google_auth_library_credentials",
        artifact = "com.google.auth:google-auth-library-credentials:0.18.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "2377b149dbf63f000f96b66f5dc0f07b9da3928f5e3f31973f2d21fcb63ce6ff",
        licenses = ["notice"],  # BSD 3-clause
    )

def com_google_auth_google_auth_library_oauth2_http():
    jvm_maven_import_external(
        name = "com_google_auth_google_auth_library_oauth2_http",
        artifact = "com.google.auth:google-auth-library-oauth2-http:0.18.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "2f13eff0105debe54a91851684b78dd5a0f3839fae5acaa8ba7959c004c050d0",
        licenses = ["notice"],  # BSD 3-clause
    )

def com_google_code_findbugs_jsr305():
    jvm_maven_import_external(
        name = "com_google_code_findbugs_jsr305",
        artifact = "com.google.code.findbugs:jsr305:3.0.2",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_code_gson():
    jvm_maven_import_external(
        name = "com_google_code_gson_gson",
        artifact = "com.google.code.gson:gson:jar:2.8.6",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "c8fb4839054d280b3033f800d1f5a97de2f028eb8ba2eb458ad287e536f3f25f",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_errorprone_error_prone_annotations():
    jvm_maven_import_external(
        name = "com_google_errorprone_error_prone_annotations",
        artifact = "com.google.errorprone:error_prone_annotations:2.3.3",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "ec59f1b702d9afc09e8c3929f5c42777dec623a6ea2731ac694332c7d7680f5a",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_guava():
    jvm_maven_import_external(
        name = "com_google_guava_guava",
        artifact = "com.google.guava:guava:28.1-android",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "e112ce92c0f0733965eede73d94589c59a72128b06b08bba5ebe2f9ea672ef60",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_guava_failureaccess():
    # Not needed until Guava 27.0, but including now to ease upgrading of users. See #5214
    jvm_maven_import_external(
        name = "com_google_guava_failureaccess",
        artifact = "com.google.guava:failureaccess:1.0.1",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_j2objc_j2objc_annotations():
    jvm_maven_import_external(
        name = "com_google_j2objc_j2objc_annotations",
        artifact = "com.google.j2objc:j2objc-annotations:1.3",
        server_urls = ["http://central.maven.org/maven2"],
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
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "edaa12f3b581fcf1c07311e94af8766919c4f3d904b00d3503147b99bf5b4004",
        licenses = ["notice"],  # Apache 2.0
    )

def com_squareup_okhttp():
    jvm_maven_import_external(
        name = "com_squareup_okhttp_okhttp",
        artifact = "com.squareup.okhttp:okhttp:2.5.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "1cc716e29539adcda677949508162796daffedb4794cbf947a6f65e696f0381c",
        licenses = ["notice"],  # Apache 2.0
    )

def com_squareup_okio():
    jvm_maven_import_external(
        name = "com_squareup_okio_okio",
        artifact = "com.squareup.okio:okio:1.13.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "734269c3ebc5090e3b23566db558f421f0b4027277c79ad5d176b8ec168bb850",
        licenses = ["notice"],  # Apache 2.0
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "9d96f861f01ed9e3d805024e72a6b218b626da2114c69c1cad5d0e967c8e23be",
        strip_prefix = "grpc-proto-435d723289d348e1bc420d420b364369d565182a",
        urls = ["https://github.com/grpc/grpc-proto/archive/435d723289d348e1bc420d420b364369d565182a.zip"],
    )

def io_netty_buffer():
    jvm_maven_import_external(
        name = "io_netty_netty_buffer",
        artifact = "io.netty:netty-buffer:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "7b0171a4e8bcd573e08d9f2bba053c67b557ab5012106a5982ccbae5743814c0",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec():
    jvm_maven_import_external(
        name = "io_netty_netty_codec",
        artifact = "io.netty:netty-codec:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "e96ced697fb7df589da7c20c995e01f75a9cb246be242bbc4cd3b4af424ff189",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec_http():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_http",
        artifact = "io.netty:netty-codec-http:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "eb349c0f1b249af7c7a8fbbd1c761d65d9bc230880cd8d37feab9e8278292625",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec_http2():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_http2",
        artifact = "io.netty:netty-codec-http2:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "8bac9625eb68635396eb0c13c9cc0b22bde7c83d0cd2dae3fe9b6f9cf929e372",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec_socks():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_socks",
        artifact = "io.netty:netty-codec-socks:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "7f14b3a95ee9aa5a26f66af668690578a81a883683ac1c4ca9e9afdf4d4c7894",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_common():
    jvm_maven_import_external(
        name = "io_netty_netty_common",
        artifact = "io.netty:netty-common:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "3d0a918d78292eeca02a7bb2188daa4e5053b6e29b71e6308309033e121242b5",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_handler():
    jvm_maven_import_external(
        name = "io_netty_netty_handler",
        artifact = "io.netty:netty-handler:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "11eda86500c33b9d386719b5419f513fd9c097d13894f25dd0c75b610d636e03",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_handler_proxy():
    jvm_maven_import_external(
        name = "io_netty_netty_handler_proxy",
        artifact = "io.netty:netty-handler-proxy:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "25f22da21c29ab0d3b6b889412351bcfc5f9ccd42e07d2d5513d5c4eb571f343",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_resolver():
    jvm_maven_import_external(
        name = "io_netty_netty_resolver",
        artifact = "io.netty:netty-resolver:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "89768242b6b7cce9bd9f5945ad21d1b4bae515c6b1bf03a8af5d1899779cebc9",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_tcnative_boringssl_static():
    jvm_maven_import_external(
        name = "io_netty_netty_tcnative_boringssl_static",
        artifact = "io.netty:netty-tcnative-boringssl-static:2.0.26.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "5f074a4b112bf7d087331e33d2da720745c5bda047b34b64bd70aaaae4de24c6",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_transport():
    jvm_maven_import_external(
        name = "io_netty_netty_transport",
        artifact = "io.netty:netty-transport:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "dfa817a156ea263aa9ad8364a2e226527665c9722aca40a7945f228c2c14f1da",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_transport_native_epoll():
    jvm_maven_import_external(
        name = "io_netty_netty_transport_native_epoll",
        artifact = "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.42.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "7bdf3003d5b60b061b494e62d1bafc420caf800efb743b14ec01ceaef1d3fa3e",
        licenses = ["notice"],  # Apache 2.0
    )

def io_opencensus_api():
    jvm_maven_import_external(
        name = "io_opencensus_opencensus_api",
        artifact = "io.opencensus:opencensus-api:0.24.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "f561b1cc2673844288e596ddf5bb6596868a8472fd2cb8993953fc5c034b2352",
        licenses = ["notice"],  # Apache 2.0
    )

def io_opencensus_grpc_metrics():
    jvm_maven_import_external(
        name = "io_opencensus_opencensus_contrib_grpc_metrics",
        artifact = "io.opencensus:opencensus-contrib-grpc-metrics:0.24.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "875582e093f11950ad3f4a50b5fee33a008023f7d1e47820a1bef05d23b9ed42",
        licenses = ["notice"],  # Apache 2.0
    )

def io_perfmark():
    jvm_maven_import_external(
        name = "io_perfmark_perfmark_api",
        artifact = "io.perfmark:perfmark-api:0.19.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "b734ba2149712409a44eabdb799f64768578fee0defe1418bb108fe32ea43e1a",
        licenses = ["notice"],  # Apache 2.0
    )

def javax_annotation():
    # Use //stub:javax_annotation for neverlink=1 support.
    jvm_maven_import_external(
        name = "javax_annotation_javax_annotation_api",
        artifact = "javax.annotation:javax.annotation-api:1.2",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "5909b396ca3a2be10d0eea32c74ef78d816e1b4ead21de1d78de1f890d033e04",
        licenses = ["reciprocal"],  # CDDL License
    )

def junit_junit():
    jvm_maven_import_external(
        name = "junit_junit",
        artifact = "junit:junit:4.12",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "59721f0805e223d84b90677887d9ff567dc534d7c502ca903c0c2b17f05c116a",
        licenses = ["notice"],  # EPL 1.0
    )

def org_apache_commons_lang3():
    jvm_maven_import_external(
        name = "org_apache_commons_commons_lang3",
        artifact = "org.apache.commons:commons-lang3:3.5",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "8ac96fc686512d777fca85e144f196cd7cfe0c0aec23127229497d1a38ff651c",
        licenses = ["notice"],  # Apache 2.0
    )

def org_codehaus_mojo_animal_sniffer_annotations():
    jvm_maven_import_external(
        name = "org_codehaus_mojo_animal_sniffer_annotations",
        artifact = "org.codehaus.mojo:animal-sniffer-annotations:1.18",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "47f05852b48ee9baefef80fa3d8cea60efa4753c0013121dd7fe5eef2e5c729d",
        licenses = ["notice"],  # MIT
    )
