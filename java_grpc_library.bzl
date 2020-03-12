"""Build rule for java_grpc_library."""

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

# "repository" here is for Bazel builds that span multiple WORKSPACES.
def _path_ignoring_repository(f):
    # Bazel creates a _virtual_imports directory in case the .proto source files
    # need to be accessed at a path that's different from their source path:
    # https://github.com/bazelbuild/bazel/blob/0.27.1/src/main/java/com/google/devtools/build/lib/rules/proto/ProtoCommon.java#L289
    #
    # In that case, the import path of the .proto file is the path relative to
    # the virtual imports directory of the rule in question.
    virtual_imports = "/_virtual_imports/"
    if virtual_imports in f.path:
        return f.path.split(virtual_imports)[1].split("/", 1)[1]
    elif len(f.owner.workspace_root) == 0:
        # |f| is in the main repository
        return f.short_path
    else:
        # If |f| is a generated file, it will have "bazel-out/*/genfiles" prefix
        # before "external/workspace", so we need to add the starting index of "external/workspace"
        return f.path[f.path.find(f.owner.workspace_root) + len(f.owner.workspace_root) + 1:]

def _java_rpc_library_impl(ctx):
    if len(ctx.attr.srcs) != 1:
        fail("Exactly one src value supported", "srcs")
    if ctx.attr.srcs[0].label.package != ctx.label.package:
        print(("in srcs attribute of {0}: Proto source with label {1} should be in " +
               "same package as consuming rule").format(ctx.label, ctx.attr.srcs[0].label))

    toolchain = ctx.attr._toolchain[_JavaRpcToolchainInfo]
    srcs = ctx.attr.srcs[0][ProtoInfo].direct_sources
    descriptor_set_in = ctx.attr.srcs[0][ProtoInfo].transitive_descriptor_sets

    srcjar = ctx.actions.declare_file("%s-proto-gensrc.jar" % ctx.label.name)

    args = ctx.actions.args()
    args.add(toolchain.plugin, format = "--plugin=protoc-gen-rpc-plugin=%s")
    args.add("--rpc-plugin_out={0}:{1}".format(toolchain.plugin_arg, srcjar.path))
    args.add_joined("--descriptor_set_in", descriptor_set_in, join_with = ctx.host_configuration.host_path_separator)
    args.add_all(srcs, map_each = _path_ignoring_repository)

    ctx.actions.run(
        inputs = depset([toolchain.plugin] + srcs, transitive = [descriptor_set_in]),
        outputs = [srcjar],
        executable = toolchain.protoc,
        arguments = [args],
    )

    deps_java_info = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])

    java_info = java_common.compile(
        ctx,
        java_toolchain = toolchain.java_toolchain[java_common.JavaToolchainInfo],
        host_javabase = toolchain.host_javabase[java_common.JavaRuntimeInfo],
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
            providers = [ProtoInfo],
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
