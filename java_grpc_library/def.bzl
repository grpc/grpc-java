load(":toolchain.bzl", "GrpcProtoInfo")

_GRPC_TOOLCHAIN = "//java_grpc_library:toolchain"

_AspectInfo = provider()

def _aspect_impl(target, ctx):
    # proto_info = target[ProtoInfo] # TODO: update provider when ProtoInfo is a real thing
    proto_info = target.proto
    tc = ctx.attr._toolchain[platform_common.ToolchainInfo]
    transitive_compiled_jars = [t[_AspectInfo].compiled_jars for t in ctx.rule.attr.deps]

    # return (and merge) transitive deps if there are no sources to compile
    if len(proto_info.direct_sources) == 0:
        java_info = java_common.merge([dep[JavaInfo] for dep in ctx.rule.attr.deps])
        grpc_info = tc.merge([dep[GrpcProtoInfo] for dep in ctx.rule.attr.deps])
        compiled_jars = depset(transitive = transitive_compiled_jars)
        return struct(
            proto_java = java_info,
            providers = [
                java_info,
                grpc_info,
                _AspectInfo(compiled_jars = depset(transitive = transitive_compiled_jars)),
            ],
        )

    # Compile protos to srcs
    compiled_jar = tc.declare_file(ctx, ".jar")
    java_srcs = tc.declare_file(ctx, "-java-sources.jar")
    grpc_srcs = tc.declare_file(ctx, "-grpc-sources.jar")
    source_jars = [grpc_srcs, java_srcs]
    grpc_info = tc.compile_proto(
        ctx,
        output_grpc_jar = grpc_srcs,
        output_java_jar = java_srcs,
        proto_info = proto_info,
        deps = [t[GrpcProtoInfo] for t in ctx.rule.attr.deps],
    )

    # Compile srcs to jars
    java_info = tc.compile_srcjars(
        ctx,
        source_jars = source_jars,
        output_jar = compiled_jar,
        deps = [t[JavaInfo] for t in ctx.rule.attr.deps],
    )
    return struct(
        proto_java = java_info,
        providers = [
            grpc_info,
            java_info,
            _AspectInfo(compiled_jars = depset(
                direct = [compiled_jar],
                transitive = transitive_compiled_jars,
            )),
        ],
    )

_protocompiler_aspect = aspect(
    _aspect_impl,
    # provide 'proto_java' legacy provider so IntelliJ plugin is happy :-\
    provides = ["proto_java", JavaInfo, GrpcProtoInfo],
    attr_aspects = ["deps"],
    fragments = ["java", "android"],
    attrs = {
        "_toolchain": attr.label(
            providers = [platform_common.ToolchainInfo],
            default = Label(_GRPC_TOOLCHAIN),
        ),
    },
)

def _rule_impl(ctx):
    tc = ctx.attr._toolchain[platform_common.ToolchainInfo]
    if ctx.attr.single_jar == "auto":
        single_jar = tc.single_jar
    elif ctx.attr.single_jar == "yes":
        single_jar = True
    elif ctx.attr.single_jar == "no":
        single_jar = False
    else:
        fail("Unreachable.")

    if single_jar:
        # Aggregate all sources and compile a new JavaInfo. This is really
        # not ideal, but is here for compatibility reasons (Android dex'ing
        # aspect, I'm looking at you..)
        compiled_jar = tc.declare_file(ctx, ".jar")
        grpc_info = tc.merge([t[GrpcProtoInfo] for t in ctx.attr.deps])
        java_info = tc.compile_srcjars(
            ctx,
            source_jars = grpc_info.source_jars.to_list(),
            output_jar = compiled_jar,
            exports = [dep[JavaInfo] for dep in tc.exports],
        )
        runfiles = ctx.runfiles(files = [compiled_jar])
        for dep in tc.deps + tc.runtime_deps:
            runfiles = dep.default_runfiles.merge(runfiles)
        return [
            java_info,
            DefaultInfo(
                files = depset(direct = [compiled_jar]),
                runfiles = runfiles,
            ),
        ]
    else:
        # merge & return the aspect-generated JavaInfos
        java_info = java_common.merge([
            dep[JavaInfo]
            for dep in ctx.attr.deps + tc.exports
        ])
        compiled_jars = depset(transitive = [t[_AspectInfo].compiled_jars for t in ctx.attr.deps])
        runfiles = ctx.runfiles(transitive_files = compiled_jars)
        for dep in tc.deps + tc.runtime_deps:
            runfiles = dep.default_runfiles.merge(runfiles)
        return [
            java_info,
            DefaultInfo(
                files = compiled_jars,
                runfiles = runfiles,
            ),
        ]
    fail("Unreachable.")

java_grpc_library = rule(
    _rule_impl,
    provides = [JavaInfo],
    fragments = ["android", "java"],
    attrs = {
        "deps": attr.label_list(
            providers = [
                ["proto"],
                # TODO: enable when ProtoInfo is real
                #[ProtoInfo],
            ],
            aspects = [_protocompiler_aspect],
            mandatory = True,
        ),
        "single_jar": attr.string(
            values = ["yes", "no", "auto"],
            default = "auto",
            doc = "Combine generated code from all transitive deps into a single compiled jar instead of individual jars per transitive dependency. This is less efficient, but may be necessary for some rules whose aspects don't properly propagate along this rule's transitive dependencies.",
        ),
        "_toolchain": attr.label(
            providers = [platform_common.ToolchainInfo],
            default = Label(_GRPC_TOOLCHAIN),
        ),
    },
)
