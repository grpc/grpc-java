"""Build rule for java_grpc_library."""

load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@com_google_protobuf//bazel/common:proto_lang_toolchain_info.bzl", "ProtoLangToolchainInfo")
load("@rules_java//java:defs.bzl", "JavaInfo", "java_common")
load("@rules_proto//proto:defs.bzl", "proto_common")

def _java_rpc_library_impl(ctx):
    if len(ctx.attr.srcs) != 1:
        fail("Exactly one src value supported", "srcs")
    if ctx.attr.srcs[0].label.package != ctx.label.package:
        print(("in srcs attribute of {0}: Proto source with label {1} should be in " +
               "same package as consuming rule").format(ctx.label, ctx.attr.srcs[0].label))

    toolchain = ctx.attr._toolchain[ProtoLangToolchainInfo]
    srcs = ctx.attr.srcs[0][ProtoInfo]

    srcjar = ctx.actions.declare_file("%s-proto-gensrc.jar" % ctx.label.name)

    proto_common.compile(
        actions = ctx.actions,
        proto_info = srcs,
        proto_lang_toolchain_info = toolchain,
        generated_files = [srcjar],
        plugin_output = srcjar.path,
    )

    deps_java_info = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])

    java_info = java_common.compile(
        ctx,
        java_toolchain = ctx.toolchains["@bazel_tools//tools/jdk:toolchain_type"].java,
        source_jars = [srcjar],
        output = ctx.outputs.jar,
        output_source_jar = ctx.outputs.srcjar,
        deps = [
            java_common.make_non_strict(deps_java_info),
        ] + ([toolchain.runtime[JavaInfo]] if toolchain.runtime else []),
    )

    return [java_info]

_java_grpc_library = rule(
    attrs = {
        "srcs": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [ProtoInfo],
        ),
        "deps": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [JavaInfo],
        ),
        "_toolchain": attr.label(
            default = Label("//compiler:java_grpc_library_toolchain"),
            providers = [ProtoLangToolchainInfo],
        ),
    },
    toolchains = ["@bazel_tools//tools/jdk:toolchain_type"],
    fragments = ["java"],
    outputs = {
        "jar": "lib%{name}.jar",
        "srcjar": "lib%{name}-src.jar",
    },
    provides = [JavaInfo],
    implementation = _java_rpc_library_impl,
)

# A copy of _java_grpc_library, except with a neverlink=1 _toolchain
INTERNAL_java_grpc_library_for_xds = rule(
    attrs = {
        "srcs": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [ProtoInfo],
        ),
        "deps": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [JavaInfo],
        ),
        "_toolchain": attr.label(
            default = Label("//xds:java_grpc_library_toolchain"),
            providers = [ProtoLangToolchainInfo],
        ),
    },
    toolchains = ["@bazel_tools//tools/jdk:toolchain_type"],
    fragments = ["java"],
    outputs = {
        "jar": "lib%{name}.jar",
        "srcjar": "lib%{name}-src.jar",
    },
    provides = [JavaInfo],
    implementation = _java_rpc_library_impl,
)

_java_lite_grpc_library = rule(
    attrs = {
        "srcs": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [ProtoInfo],
        ),
        "deps": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [JavaInfo],
        ),
        "_toolchain": attr.label(
            default = Label("//compiler:java_lite_grpc_library_toolchain"),
            providers = [ProtoLangToolchainInfo],
        ),
    },
    toolchains = ["@bazel_tools//tools/jdk:toolchain_type"],
    fragments = ["java"],
    outputs = {
        "jar": "lib%{name}.jar",
        "srcjar": "lib%{name}-src.jar",
    },
    provides = [JavaInfo],
    implementation = _java_rpc_library_impl,
)

def java_grpc_library(
        name,
        srcs,
        deps,
        flavor = None,
        **kwargs):
    """Generates gRPC Java code for services in a `proto_library`.

    This rule only generates code for services; it does not generate code for
    messages. You will need a separate java_proto_library or
    java_lite_proto_library rule.

    Args:
      name: A unique name for this rule.
      srcs: (List of `labels`) a single proto_library target that contains the
        schema of the service.
      deps: (List of `labels`) a single java_proto_library or
        java_lite_proto_library target for the proto_library in srcs.
      flavor: (str) "normal" (default) for normal proto runtime. "lite"
        for the lite runtime.
      **kwargs: Other common attributes
    """

    if len(deps) > 1:
        print("Multiple values in 'deps' is deprecated in " + name)

    if flavor == None or flavor == "normal":
        _java_grpc_library(
            name = name,
            srcs = srcs,
            deps = deps,
            **kwargs
        )
    elif flavor == "lite":
        _java_lite_grpc_library(
            name = name,
            srcs = srcs,
            deps = deps,
            **kwargs
        )
    else:
        fail("Flavor must be normal or lite")
