"""External dependencies for grpc-java."""

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
    actual = "@com_google_guava//jar",
  )
  native.bind(
    name = "gson",
    actual = "@com_google_code_gson//jar",
  )

def com_google_api_grpc_google_common_protos():
  native.maven_jar(
      name = "com_google_api_grpc_google_common_protos",
      artifact = "com.google.api.grpc:proto-google-common-protos:0.1.9",
      sha1 = "3760f6a6e13c8ab070aa629876cdd183614ee877",
  )

def com_google_code_findbugs_jsr305():
  native.maven_jar(
      name = "com_google_code_findbugs_jsr305",
      artifact = "com.google.code.findbugs:jsr305:3.0.0",
      sha1 = "5871fb60dc68d67da54a663c3fd636a10a532948",
  )

def com_google_code_gson():
  native.maven_jar(
      name = "com_google_code_gson",
      artifact = "com.google.code.gson:gson:jar:2.7",
      sha1 = "751f548c85fa49f330cecbb1875893f971b33c4e",
  )

def com_google_errorprone_error_prone_annotations():
  native.maven_jar(
      name = "com_google_errorprone_error_prone_annotations",
      sha1 = "c3754a0bdd545b00ddc26884f9e7624f8b6a14de",
      artifact = "com.google.errorprone:error_prone_annotations:2.0.19",
  )

def com_google_guava():
  native.maven_jar(
      name = "com_google_guava",
      artifact = "com.google.guava:guava:19.0",
      sha1 = "6ce200f6b23222af3d8abb6b6459e6c44f4bb0e9",
  )

def com_google_instrumentation_api():
  native.maven_jar(
      name = "com_google_instrumentation_api",
      sha1 = "3b548639e14ca8d8af5075acac82926266479ebf",
      artifact = "com.google.instrumentation:instrumentation-api:0.4.2",
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
  native.maven_jar(
      name = "com_google_protobuf_nano_protobuf_javanano",
      sha1 = "357e60f95cebb87c72151e49ba1f570d899734f8",
      artifact = "com.google.protobuf.nano:protobuf-javanano:3.0.0-alpha-5",
  )

def com_squareup_okhttp():
  native.maven_jar(
      name = "com_squareup_okhttp",
      sha1 = "4de2b4ed3445c37ec1720a7d214712e845a24636",
      artifact = "com.squareup.okhttp:okhttp:2.5.0",
  )

def com_squareup_okio():
  native.maven_jar(
      name = "com_squareup_okio",
      sha1 = "98476622f10715998eacf9240d6b479f12c66143",
      artifact = "com.squareup.okio:okio:1.6.0",
  )

def io_netty_codec_http2():
  native.maven_jar(
      name = "io_netty_codec_http2",
      sha1 = "105a99ee5767463370ccc3d2e16800bd99f5648e",
      artifact = "io.netty:netty-codec-http2:4.1.8.Final",
  )

def io_netty_buffer():
  native.maven_jar(
      name = "io_netty_buffer",
      sha1 = "43292c2622e340a0d07178c341ca3bdf3d662034",
      artifact = "io.netty:netty-buffer:4.1.8.Final",
  )

def io_netty_common():
  native.maven_jar(
      name = "io_netty_common",
      sha1 = "ee62c80318413d2375d145e51e48d7d35c901324",
      artifact = "io.netty:netty-common:4.1.8.Final",
  )

def io_netty_transport():
  native.maven_jar(
      name = "io_netty_transport",
      sha1 = "905b5dadce881c9824b3039c0df36dabbb7b6a07",
      artifact = "io.netty:netty-transport:4.1.8.Final",
  )

def io_netty_codec():
  native.maven_jar(
      name = "io_netty_codec",
      sha1 = "1bd0a2d032e5c7fc3f42c1b483d0f4c57eb516a3",
      artifact = "io.netty:netty-codec:4.1.8.Final",
  )

def io_netty_codec_socks():
  native.maven_jar(
      name = "io_netty_codec_socks",
      sha1 = "7f7c5f5b154646d7c571f8ca944fb813f71b1d51",
      artifact = "io.netty:netty-codec-socks:4.1.8.Final",
  )

def io_netty_codec_http():
  native.maven_jar(
      name = "io_netty_codec_http",
      sha1 = "1e88617c4a6c88da7e86fdbbd9494d22a250c879",
      artifact = "io.netty:netty-codec-http:4.1.8.Final",
  )

def io_netty_handler():
  native.maven_jar(
      name = "io_netty_handler",
      sha1 = "db01139bfb11afd009a695eef55b43bbf22c4ef5",
      artifact = "io.netty:netty-handler:4.1.8.Final",
  )

def io_netty_handler_proxy():
  native.maven_jar(
      name = "io_netty_handler_proxy",
      sha1 = "c4d22e8b9071a0ea8d75766d869496c32648a758",
      artifact = "io.netty:netty-handler-proxy:4.1.8.Final",
  )

def io_netty_resolver():
  native.maven_jar(
      name = "io_netty_resolver",
      sha1 = "2e116cdd5edc01b2305072b1dbbd17c0595dbfef",
      artifact = "io.netty:netty-resolver:4.1.8.Final",
  )

def io_netty_tcnative_boringssl_static():
  native.maven_jar(
      name = "io_netty_tcnative_boringssl_static",
      sha1 = "3b78a7e40707be313c4d5449ba514c9747e1c731",
      artifact = "io.netty:netty-tcnative-boringssl-static:1.1.33.Fork26",
  )
