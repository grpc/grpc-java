"""Build rule for java_grpc_library."""

load("@bazel_tools//tools/jdk:toolchain_utils.bzl", "find_java_runtime_toolchain", "find_java_toolchain")

_JavaRpcToolchainInfo = provider(
    fields = [
        "host_javabase",
        "java_toolchain",
        "plugin",
        "plugin_arg",
        "protoc",
        "runtime",
    ],
)

def _java_rpc_toolchain_impl(ctx):
    return [
        _JavaRpcToolchainInfo(
            host_javabase = ctx.attr._host_javabase,
            java_toolchain = ctx.attr._java_toolchain,
            plugin = ctx.executable.plugin,
            plugin_arg = ctx.attr.plugin_arg,
            protoc = ctx.executable._protoc,
            runtime = ctx.attr.runtime,
        ),
        platform_common.ToolchainInfo(),  # Magic for b/78647825
    ]

java_rpc_toolchain = rule(
    attrs = {
        # This attribute has a "magic" name recognized by the native DexArchiveAspect (b/78647825).
        "runtime": attr.label_list(
            cfg = "target",
            providers = [JavaInfo],
        ),
        "plugin": attr.label(
            cfg = "host",
            executable = True,
        ),
        "plugin_arg": attr.string(),
        "_protoc": attr.label(
            cfg = "host",
            default = Label("@com_google_protobuf//:protoc"),
            executable = True,
        ),
        "_java_toolchain": attr.label(
            default = Label("@bazel_tools//tools/jdk:current_java_toolchain"),
        ),
        "_host_javabase": attr.label(
            cfg = "host",
            default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
        ),
    },
    provides = [
        _JavaRpcToolchainInfo,
        platform_common.ToolchainInfo,
    ],
    implementation = _java_rpc_toolchain_impl,
)

# Taken from bazelbuild/rules_go license: Apache 2
# https://github.com/bazelbuild/rules_go/blob/528f6faf83f85c23da367d61f784893d1b3bd72b/proto/compiler.bzl#L94
# replaced `prefix = paths.join(..` with `prefix = "/".join(..`
def _proto_path(src, proto):
    """proto_path returns the string used to import the proto. This is the proto
    source path within its repository, adjusted by import_prefix and
    strip_import_prefix.

    Args:
        src: the proto source File.
        proto: the ProtoInfo provider.

    Returns:
        An import path string.
    """
    if not hasattr(proto, "proto_source_root"):
        # Legacy path. Remove when Bazel minimum version >= 0.21.0.
        path = src.path
        root = src.root.path
        ws = src.owner.workspace_root
        if path.startswith(root):
            path = path[len(root):]
        if path.startswith("/"):
            path = path[1:]
        if path.startswith(ws):
            path = path[len(ws):]
        if path.startswith("/"):
            path = path[1:]
        return path

    if proto.proto_source_root == ".":
        # true if proto sources were generated
        prefix = src.root.path + "/"
    elif proto.proto_source_root.startswith(src.root.path):
        # sometimes true when import paths are adjusted with import_prefix
        prefix = proto.proto_source_root + "/"
    else:
        # usually true when paths are not adjusted
        prefix = "/".join([src.root.path, proto.proto_source_root]) + "/"
    if not src.path.startswith(prefix):
        # sometimes true when importing multiple adjusted protos
        return src.path
    return src.path[len(prefix):]

def _java_rpc_library_impl(ctx):
    if len(ctx.attr.srcs) != 1:
        fail("Exactly one src value supported", "srcs")
    if ctx.attr.srcs[0].label.package != ctx.label.package:
        print(("in srcs attribute of {0}: Proto source with label {1} should be in " +
               "same package as consuming rule").format(ctx.label, ctx.attr.srcs[0].label))

    toolchain = ctx.attr._toolchain[_JavaRpcToolchainInfo]
    proto = ctx.attr.srcs[0][ProtoInfo]
    srcs = proto.direct_sources
    descriptor_set_in = proto.transitive_descriptor_sets

    srcjar = ctx.actions.declare_file("%s-proto-gensrc.jar" % ctx.label.name)

    args = ctx.actions.args()
    args.add(toolchain.plugin, format = "--plugin=protoc-gen-rpc-plugin=%s")
    args.add("--rpc-plugin_out={0}:{1}".format(toolchain.plugin_arg, srcjar.path))
    args.add_joined("--descriptor_set_in", descriptor_set_in, join_with = ":")
    for src in srcs:
        args.add(_proto_path(src, proto))

    ctx.actions.run(
        inputs = descriptor_set_in,
        tools = [toolchain.plugin],
        outputs = [srcjar],
        executable = toolchain.protoc,
        arguments = [args],
    )

    deps_java_info = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])

    java_info = java_common.compile(
        ctx,
        java_toolchain = find_java_toolchain(ctx, toolchain.java_toolchain),
        host_javabase = find_java_runtime_toolchain(ctx, toolchain.host_javabase),
        source_jars = [srcjar],
        output = ctx.outputs.jar,
        output_source_jar = ctx.outputs.srcjar,
        deps = [
            java_common.make_non_strict(deps_java_info),
        ] + [dep[JavaInfo] for dep in toolchain.runtime],
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
        ),
    },
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
            providers = ["proto"],
        ),
        "deps": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [JavaInfo],
        ),
        # This attribute has a "magic" name recognized by the native DexArchiveAspect (b/78647825).
        "_toolchain": attr.label(
            default = Label("//compiler:java_lite_grpc_library_toolchain"),
        ),
    },
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
