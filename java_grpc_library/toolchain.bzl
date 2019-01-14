_toolchain_attrs = {
    "deps": attr.label_list(default = [], providers = [JavaInfo]),
    "runtime_deps": attr.label_list(default = [], providers = [JavaInfo]),
    "exports": attr.label_list(default = [], providers = [JavaInfo]),
    "output_prefix": attr.string(default = "lib{name}"),
    "single_jar": attr.bool(default = False),
    "java_plugin_opts": attr.string_list(default = []),
    "java_plugin": attr.label(
        executable = True,
        cfg = "host",
        default = None,
    ),
    "grpc_plugin_opts": attr.string_list(default = []),
    "grpc_plugin": attr.label(
        executable = True,
        cfg = "host",
        default = Label("//compiler:grpc_java_plugin"),
    ),
    "protoc": attr.label(
        executable = True,
        cfg = "host",
        default = Label("@com_google_protobuf//:protoc"),
    ),
    "_java_toolchain": attr.label(
        default = Label("@bazel_tools//tools/jdk:toolchain"),
        cfg = "host",
    ),
    "_host_javabase": attr.label(
        default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
        cfg = "host",
    ),
}

GrpcProtoInfo = provider(
    fields = {
        "source_jars": "Depset[File] of source jars",
        "importmap": "(preorder)Depset[Tuple[Importpath,File]] of transitive protos",
        "imports": "Depset[File] of transitive protos",
    },
)

def _get_toolchain(ctx):
    return ctx.attr._toolchain[platform_common.ToolchainInfo]

def _path_ignoring_repository(f):
    if (len(f.owner.workspace_root) == 0):
        return f.short_path
    return f.path[f.path.find(f.owner.workspace_root) + len(f.owner.workspace_root) + 1:]

def _importmap_args(import_file_tuple):
    i, f = import_file_tuple
    return "-I%s=%s" % (i, f.path)

def _compile_proto(
        ctx,
        output_grpc_jar = None,
        output_java_jar = None,
        proto_info = None,
        deps = []):
    """
    Args:
      proto_info: (ProtoInfo)
      deps: (list[GrpcProtoInfo])
      grpc_output_jar: (File)
      java_output_jar: (File)

    Returns:
      GrpcProtoInfo
    """
    tc = _get_toolchain(ctx)

    # Tuples of [ImportedName => File]
    direct_importmap = []
    proto_files = []

    # TODO: update this logic once '{strip_,}import_prefix' attrs are real
    prefix = proto_info.proto_source_root + "/"
    for f in proto_info.direct_sources:
        imported_name = _path_ignoring_repository(f)
        if f.short_path.startswith(prefix):
            imported_name = imported_name[len(prefix):]
        direct_importmap += [(imported_name, f)]
        proto_files += [f]

    # create depsets for use in compilation action & to output via provider
    importmap = depset(direct = direct_importmap, transitive = [
        dep.importmap
        for dep in deps
    ], order = "preorder")
    imports = depset(direct = proto_files, transitive = [
        dep.imports
        for dep in deps
    ])

    protoc = tc.protoc.files_to_run.executable
    grpc_plugin = tc.grpc_plugin.files_to_run.executable
    java_plugin = tc.java_plugin.files_to_run.executable if tc.java_plugin else None

    # generate java & grpc srcs
    args = ctx.actions.args()
    args.add_all(importmap, map_each = _importmap_args)
    args.add("--plugin=protoc-gen-grpc-java=%s" % grpc_plugin.path)
    args.add("--grpc-java_out={opts}:{file}".format(
        opts = ",".join(tc.grpc_plugin_opts),
        file = output_grpc_jar.path,
    ))
    if java_plugin:
        args.add("--plugin=protoc-gen-javaplugin=%s" % java_plugin.path)
        args.add("--javaplugin_out={opts}:{file}".format(
            opts = ",".join(tc.java_plugin_opts),
            file = output_java_jar.path,
        ))
    else:
        args.add("--java_out={opts}:{file}".format(
            opts = ",".join(tc.java_plugin_opts),
            file = output_java_jar.path,
        ))
    args.add_all(proto_files)

    ctx.actions.run(
        inputs = imports,
        outputs = [output_grpc_jar, output_java_jar],
        executable = protoc,
        arguments = [args],
        tools = [
            grpc_plugin,
        ] + ([java_plugin] if java_plugin else []),
    )
    return GrpcProtoInfo(
        imports = imports,
        importmap = importmap,
        source_jars = depset(
            direct = [output_java_jar, output_grpc_jar],
            transitive = [dep.source_jars for dep in deps],
        ),
    )

def _declare_file(ctx, suffix, **kwargs):
    tc = _get_toolchain(ctx)

    # so this can work from an aspect _or_ rule
    name = ctx.attr.name if hasattr(ctx.attr, "name") else ctx.rule.attr.name
    filename = tc.output_prefix.format(name = name) + suffix
    return ctx.actions.declare_file(filename, **kwargs)

def _merge(grpc_proto_infos):
    """Description
    Returns:
      GrpcProtoInfo

    Args:
      grpc_proto_infos: (list[GrpcProtoInfo])
    """
    return GrpcProtoInfo(
        source_jars = depset(transitive = [i.source_jars for i in grpc_proto_infos]),
        importmap = depset(transitive = [i.importmap for i in grpc_proto_infos]),
        imports = depset(transitive = [i.imports for i in grpc_proto_infos]),
    )

def _compile_srcjars(ctx, source_jars = [], output_jar = None, deps = [], exports = []):
    tc = _get_toolchain(ctx)
    compiled_jar = output_jar
    sources_jar = java_common.pack_sources(
        ctx.actions,
        output_jar = compiled_jar,
        source_jars = source_jars,
        java_toolchain = tc._java_toolchain,
        host_javabase = tc._host_javabase,
    )
    compile_deps = deps + [
        dep[JavaInfo]
        for dep in tc.deps + tc.exports
    ]
    java_common.compile(
        ctx,
        source_jars = source_jars,
        deps = compile_deps,
        output = compiled_jar,
        java_toolchain = tc._java_toolchain,
        host_javabase = tc._host_javabase,
    )
    ijar = java_common.run_ijar(
        ctx.actions,
        jar = compiled_jar,
        java_toolchain = tc._java_toolchain,
    )
    java_info = JavaInfo(
        output_jar = compiled_jar,
        compile_jar = ijar,
        source_jar = sources_jar,
        deps = compile_deps,
        exports = exports,
        runtime_deps = [t[JavaInfo] for t in tc.runtime_deps],
    )
    return java_info

def _toolchain_impl(ctx):
    # Pass configured attrs through via provider
    props = {k: getattr(ctx.attr, k) for k in _toolchain_attrs.keys()}
    props["declare_file"] = _declare_file
    props["compile_srcjars"] = _compile_srcjars
    props["merge"] = _merge
    props["compile_proto"] = _compile_proto

    # I think the android dexer requires this?
    props["runtime"] = props["deps"] + props["exports"]
    return [platform_common.ToolchainInfo(**props)]

grpc_proto_toolchain = rule(
    _toolchain_impl,
    attrs = _toolchain_attrs,
    provides = [platform_common.ToolchainInfo],
)
