"""External dependencies for grpc-java."""

load("//:java_import_external.bzl", "java_import_external")

def grpc_java_repositories(
    omit_com_google_api_grpc_google_common_protos=False,
    omit_com_google_code_findbugs_jsr305=False,
    omit_com_google_code_gson=False,
    omit_com_google_errorprone_error_prone_annotations=False,
    omit_com_google_guava=False,
    omit_com_google_instrumentation_api=False,
    omit_com_google_protobuf=False,
    omit_com_google_protobuf_java=False,
    omit_com_google_protobuf_nano_protobuf_javanano=False,
    omit_com_squareup_okhttp=False,
    omit_com_squareup_okio=False,
    omit_io_netty_buffer=False,
    omit_io_netty_common=False,
    omit_io_netty_transport=False,
    omit_io_netty_codec=False,
    omit_io_netty_codec_socks=False,
    omit_io_netty_codec_http=False,
    omit_io_netty_codec_http2=False,
    omit_io_netty_handler=False,
    omit_io_netty_handler_proxy=False,
    omit_io_netty_resolver=False,
    omit_io_netty_tcnative_boringssl_static=False):
  """Imports dependencies for grpc-java."""
  if not omit_com_google_api_grpc_google_common_protos:
    com_google_api_grpc_google_common_protos()
  if not omit_com_google_code_findbugs_jsr305:
    com_google_code_findbugs_jsr305()
  if not omit_com_google_code_gson:
    com_google_code_gson()
  if not omit_com_google_errorprone_error_prone_annotations:
    com_google_errorprone_error_prone_annotations()
  if not omit_com_google_guava:
    com_google_guava()
  if not omit_com_google_instrumentation_api:
    com_google_instrumentation_api()
  if not omit_com_google_protobuf:
    com_google_protobuf()
  if not omit_com_google_protobuf_java:
    com_google_protobuf_java()
  if not omit_com_google_protobuf_nano_protobuf_javanano:
    com_google_protobuf_nano_protobuf_javanano()
  if not omit_com_squareup_okhttp:
    com_squareup_okhttp()
  if not omit_com_squareup_okio:
    com_squareup_okio()
  if not omit_io_netty_buffer:
    io_netty_buffer()
  if not omit_io_netty_common:
    io_netty_common()
  if not omit_io_netty_transport:
    io_netty_transport()
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
  native.bind(
    name = "guava",
    actual = "@com_google_guava",
  )
  native.bind(
    name = "gson",
    actual = "@com_google_code_gson",
  )

def com_google_api_grpc_google_common_protos():
  java_import_external(
      name = "com_google_api_grpc_google_common_protos",
      licenses = ["notice"],  # Apache 2.0
      jar_urls = [
          "http://central.maven.org/maven2/com/google/api/grpc/proto-google-common-protos/0.1.9/proto-google-common-protos-0.1.9.jar",
      ],
      jar_sha256 = "bf76dcb173f6c6083ccf452f093b53500621e701645df47671c47043c7b5491f",
  )

def com_google_code_findbugs_jsr305():
  java_import_external(
      name = "com_google_code_findbugs_jsr305",
      licenses = ["notice"],  # BSD 3-clause
      jar_urls = [
          "http://central.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.0/jsr305-3.0.0.jar",
      ],
      jar_sha256 = "bec0b24dcb23f9670172724826584802b80ae6cbdaba03bdebdef9327b962f6a",
  )

def com_google_code_gson():
  java_import_external(
      name = "com_google_code_gson",
      licenses = ["notice"],  # Apache 2.0
      jar_urls = [
          "http://bazel-mirror.storage.googleapis.com/repo1.maven.org/maven2/com/google/code/gson/gson/2.7/gson-2.7.jar",
          "http://repo1.maven.org/maven2/com/google/code/gson/gson/2.7/gson-2.7.jar",
          "http://maven.ibiblio.org/maven2/com/google/code/gson/gson/2.7/gson-2.7.jar",
      ],
      jar_sha256 = "2d43eb5ea9e133d2ee2405cc14f5ee08951b8361302fdd93494a3a997b508d32",
      deps = ["@com_google_code_findbugs_jsr305"],
  )

def com_google_errorprone_error_prone_annotations():
  java_import_external(
      name = "com_google_errorprone_error_prone_annotations",
      licenses = ["notice"],  # Apache 2.0
      jar_sha256 = "cde78ace21e46398299d0d9c6be9f47b7f971c7f045d40c78f95be9a638cbf7e",
      jar_urls = [
          "http://central.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.0.19/error_prone_annotations-2.0.19.jar",
      ],
  )

def com_google_guava():
  java_import_external(
      name = "com_google_guava",
      licenses = ["notice"],  # Apache 2.0
      jar_urls = [
          "http://bazel-mirror.storage.googleapis.com/repo1.maven.org/maven2/com/google/guava/guava/19.0/guava-19.0.jar",
          "http://repo1.maven.org/maven2/com/google/guava/guava/19.0/guava-19.0.jar",
          "http://maven.ibiblio.org/maven2/com/google/guava/guava/19.0/guava-19.0.jar",
      ],
      jar_sha256 = "58d4cc2e05ebb012bbac568b032f75623be1cb6fb096f3c60c72a86f7f057de4",
      deps = [
          "@com_google_code_findbugs_jsr305",
          "@com_google_errorprone_error_prone_annotations",
      ],
  )

def com_google_instrumentation_api():
  java_import_external(
      name = "com_google_instrumentation_api",
      jar_sha256 = "2d0c126bca740699e7b56a53480760099aab0f558c5a0a409d55f75415491334",
      jar_urls = [
          "http://central.maven.org/maven2/com/google/instrumentation/instrumentation-api/0.4.2/instrumentation-api-0.4.2.jar",
      ],
      licenses = ["notice"],  # New BSD and Apache 2.0
  )

def com_google_protobuf():
  native.http_archive(
      name = "com_google_protobuf",
      urls = ["https://github.com/google/protobuf/archive/v3.3.0rc1.zip"],
      strip_prefix = "protobuf-3.3.0rc1",
      sha256 = "2d42566b2ce17d40e7acb6956c79052aeb6b3429e05afc495963f3cdfb9162a7",
  )

def com_google_protobuf_java():
  native.http_archive(
      name = "com_google_protobuf_java",
      urls = ["https://github.com/google/protobuf/archive/v3.3.0rc1.zip"],
      strip_prefix = "protobuf-3.3.0rc1",
      sha256 = "2d42566b2ce17d40e7acb6956c79052aeb6b3429e05afc495963f3cdfb9162a7",
  )

def com_google_protobuf_nano_protobuf_javanano():
  java_import_external(
      name = "com_google_protobuf_nano_protobuf_javanano",
      jar_sha256 = "6d30f1e667a8952e1c90a0a125f0ce0edf84d6b1d51c91d8555c4fb549e3d7a1",
      jar_urls = [
          "http://central.maven.org/maven2/com/google/protobuf/nano/protobuf-javanano/3.0.0-alpha-5/protobuf-javanano-3.0.0-alpha-5.jar",
      ],
      licenses = ["notice"],  # New BSD and Apache 2.0
  )

def com_squareup_okhttp():
  java_import_external(
      name = "com_squareup_okhttp",
      jar_sha256 = "1cc716e29539adcda677949508162796daffedb4794cbf947a6f65e696f0381c",
      jar_urls = [
          "http://central.maven.org/maven2/com/squareup/okhttp/okhttp/2.5.0/okhttp-2.5.0.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def com_squareup_okio():
  java_import_external(
      name = "com_squareup_okio",
      jar_sha256 = "114bdc1f47338a68bcbc95abf2f5cdc72beeec91812f2fcd7b521c1937876266",
      jar_urls = [
          "http://central.maven.org/maven2/com/squareup/okio/okio/1.6.0/okio-1.6.0.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_codec_http2():
  java_import_external(
      name = "io_netty_codec_http2",
      jar_sha256 = "3626f30589629d7f915fb5652456c1b1e07aeb81947d507e6321951ab1178fb1",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-codec-http2/4.1.8.Final/netty-codec-http2-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_buffer():
  java_import_external(
      name = "io_netty_buffer",
      jar_sha256 = "2e71cab827b71f726f6c4c02178fccef0e9e3d8968ec40841ed94d1d8b802b6a",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-buffer/4.1.8.Final/netty-buffer-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_common():
  java_import_external(
      name = "io_netty_common",
      jar_sha256 = "1c063fb2acaeeea08ca7affd4400f5b28dec0fcf42a7d3f44155877303e64964",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-common/4.1.8.Final/netty-common-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_transport():
  java_import_external(
      name = "io_netty_transport",
      jar_sha256 = "6581c964501166daeb62792edf2a1f1ad63e348dd02b9ab228efd8ed3cce2d4a",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-transport/4.1.8.Final/netty-transport-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_codec():
  java_import_external(
      name = "io_netty_codec",
      jar_sha256 = "16090c8da8fd2f59e4cae78bb66d2ef691329407edb877bff3a4d2a628e5b139",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-codec/4.1.8.Final/netty-codec-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_codec_socks():
  java_import_external(
      name = "io_netty_codec_socks",
      jar_sha256 = "a4e3a7207ad43ce89cb5e0cf8eb332c882728ddcd1ca73bdf266057e58d0ee25",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-codec-socks/4.1.8.Final/netty-codec-socks-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_codec_http():
  java_import_external(
      name = "io_netty_codec_http",
      jar_sha256 = "af0750d4a65fea7462e5f745a5c0d44f58f3374a4273e8548fb7634233245589",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-codec-http/4.1.8.Final/netty-codec-http-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_handler():
  java_import_external(
      name = "io_netty_handler",
      jar_sha256 = "580312c6cbc8697aa29a8b3e7bf53b39556f7f32e1990edd60fe807cd8330983",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-handler/4.1.8.Final/netty-handler-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_handler_proxy():
  java_import_external(
      name = "io_netty_handler_proxy",
      jar_sha256 = "17e72fbf6396a1bf7af163e72d8c5e584f9ef5e624391ab5fb76da57e3f99f24",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-handler-proxy/4.1.8.Final/netty-handler-proxy-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_resolver():
  java_import_external(
      name = "io_netty_resolver",
      jar_sha256 = "5081e0487d601cdedb4ac79ceafb3b35f82b543cf0db752934b0b59bd33c01b9",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-resolver/4.1.8.Final/netty-resolver-4.1.8.Final.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )

def io_netty_tcnative_boringssl_static():
  java_import_external(
      name = "io_netty_tcnative_boringssl_static",
      jar_sha256 = "8d7ec605ca105747653e002bfe67bddba90ab964da697aaa5daa1060923585db",
      jar_urls = [
          "http://central.maven.org/maven2/io/netty/netty-tcnative/1.1.33.Fork26/netty-tcnative-1.1.33.Fork26.jar",
      ],
      licenses = ["notice"],  # Apache 2.0
  )
