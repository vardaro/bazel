// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.java.proto;

import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getDirectJars;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getJavacArguments;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExtraActionArtifactsProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaCompileAction;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider;
import com.google.devtools.build.lib.rules.java.ProguardSpecProvider;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the Starlark version of java_lite_proto_library rule. */
@RunWith(JUnit4.class)
public class StarlarkJavaLiteProtoLibraryTest extends BuildViewTestCase {
  private ActionsTestUtil actionsTestUtil;

  @Before
  public final void setUpMocks() throws Exception {
    useConfiguration(
        "--proto_compiler=//proto:compiler",
        "--proto_toolchain_for_javalite=//tools/proto/toolchains:javalite");

    scratch.file("proto/BUILD", "licenses(['notice'])", "exports_files(['compiler'])");

    mockToolchains();

    actionsTestUtil = actionsTestUtil();
  }

  @Before
  public final void setupStarlarkRule() throws Exception {
    setBuildLanguageOptions(
        "--experimental_builtins_injection_override=+java_lite_proto_library",
        "--experimental_google_legacy_api");
  }

  private void mockToolchains() throws IOException {
    mockRuntimes();

    scratch.file(
        "tools/proto/toolchains/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "proto_lang_toolchain(",
        "    name = 'javalite',",
        "    command_line = '--java_out=lite,immutable,no_enforce_api_compatibility:$(OUT)',",
        "    runtime = '//protobuf:javalite_runtime',",
        "    progress_message = 'Generating JavaLite proto_library %{label}',",
        ")");
  }

  private void mockRuntimes() throws IOException {
    mockToolsConfig.overwrite(
        "protobuf/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "java_library(name = 'javalite_runtime', srcs = ['javalite_runtime.java'])");
  }

  /** Tests that java_binaries which depend on proto_libraries depend on the right set of files. */
  @Test
  public void testBinaryDeps() throws Exception {
    scratch.file(
        "x/BUILD",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':foo'])",
        "proto_library(name = 'foo', srcs = ['foo.proto', 'bar.proto'], deps = [':baz'])",
        "proto_library(name = 'baz', srcs = ['baz.proto'])");

    ConfiguredTarget target = getConfiguredTarget("//x:lite_pb2");
    NestedSet<Artifact> filesToBuild = getFilesToBuild(target);
    Iterable<String> deps = prettyArtifactNames(actionsTestUtil.artifactClosureOf(filesToBuild));

    // Should depend on compiler and Java proto1 API.
    assertThat(deps).contains("proto/compiler");

    // Also should not depend on RPC APIs.
    assertThat(deps).doesNotContain("apps/xplat/rpc/codegen/protoc-gen-rpc");

    // Should depend on Java outputs.
    assertThat(deps).contains("x/foo-lite-src.jar");
    assertThat(deps).contains("x/baz-lite-src.jar");

    // Should depend on Java libraries.
    assertThat(deps).contains("x/libfoo-lite.jar");
    assertThat(deps).contains("x/libbaz-lite.jar");
    assertThat(deps).contains("protobuf/libjavalite_runtime-hjar.jar");
  }

  /** Tests that we pass the correct arguments to the protocol compiler. */
  @Test
  public void testJavaProto2CompilerArgs() throws Exception {
    scratch.file(
        "x/BUILD",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':protolib'])",
        "proto_library(name = 'protolib', srcs = ['file.proto'])");

    String genfilesDir = targetConfig.getGenfilesFragment(RepositoryName.MAIN).getPathString();

    List<String> args =
        getGeneratingSpawnAction(getConfiguredTarget("//x:lite_pb2"), "x/protolib-lite-src.jar")
            .getRemainingArguments();

    assertThat(args)
        .containsAtLeast(
            "--java_out=lite,immutable,no_enforce_api_compatibility:"
                + genfilesDir
                + "/x/protolib-lite-src.jar",
            "-Ix/file.proto=x/file.proto",
            "x/file.proto")
        .inOrder();
  }

  @Test
  public void testProtoLibraryBuildsCompiledJar() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "java",
            "lite_pb2",
            "java_lite_proto_library(name = 'lite_pb2', deps = [':compiled'])",
            "proto_library(name = 'compiled',",
            "              srcs = [ 'ok.proto' ])");

    Artifact compiledJar =
        ActionsTestUtil.getFirstArtifactEndingWith(
            getFilesToBuild(target), "/libcompiled-lite.jar");
    assertThat(compiledJar).isNotNull();
  }

  @Test
  public void testCommandLineContainsTargetLabel() throws Exception {
    scratch.file(
        "java/lib/BUILD",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':proto'])",
        "proto_library(name = 'proto', srcs = ['dummy.proto'])");

    JavaCompileAction javacAction =
        (JavaCompileAction)
            getGeneratingAction(
                getConfiguredTarget("//java/lib:lite_pb2"), "java/lib/libproto-lite.jar");

    List<String> commandLine =
        ImmutableList.copyOf((Iterable<String>) getJavacArguments(javacAction));
    MoreAsserts.assertContainsSublist(commandLine, "--target_label", "//java/lib:proto");
  }

  @Test
  public void testEmptySrcsForJavaApi() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "notbad",
            "lite_pb2",
            "java_lite_proto_library(name = 'lite_pb2', deps = [':null_lib'])",
            "proto_library(name = 'null_lib')");
    JavaCompilationArgsProvider compilationArgsProvider =
        getProvider(JavaCompilationArgsProvider.class, target);
    assertThat(compilationArgsProvider).isNotNull();
    assertThat(compilationArgsProvider.getDirectCompileTimeJars()).isNotNull();
    JavaSourceJarsProvider sourceJarsProvider = getProvider(JavaSourceJarsProvider.class, target);
    assertThat(sourceJarsProvider).isNotNull();
    assertThat(sourceJarsProvider.getSourceJars()).isNotNull();
  }

  @Test
  public void testSameVersionCompilerArguments() throws Exception {
    scratch.file(
        "cross/BUILD",
        "java_lite_proto_library(name = 'lite_pb2', deps = ['bravo'])",
        "proto_library(name = 'bravo', srcs = ['bravo.proto'], deps = [':alpha'])",
        "proto_library(name = 'alpha')");

    String genfilesDir = targetConfig.getGenfilesFragment(RepositoryName.MAIN).getPathString();

    ConfiguredTarget litepb2 = getConfiguredTarget("//cross:lite_pb2");

    List<String> args =
        getGeneratingSpawnAction(litepb2, "cross/bravo-lite-src.jar").getRemainingArguments();
    assertThat(args)
        .containsAtLeast(
            "--java_out=lite,immutable,no_enforce_api_compatibility:"
                + genfilesDir
                + "/cross/bravo-lite-src.jar",
            "-Icross/bravo.proto=cross/bravo.proto",
            "cross/bravo.proto")
        .inOrder();

    List<String> directJars =
        prettyArtifactNames(
            getProvider(JavaCompilationArgsProvider.class, litepb2).getRuntimeJars());
    assertThat(directJars)
        .containsExactly("cross/libbravo-lite.jar", "protobuf/libjavalite_runtime.jar");
  }

  @Test
  @Ignore
  // TODO(elenairina): Enable this test when proguard specs are supported in the Starlark version of
  // java_lite_proto_library OR delete this if Proguard support will be removed from Java rules.
  public void testExportsProguardSpecsForSupportLibraries() throws Exception {
    scratch.overwriteFile(
        "protobuf/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "java_library(name = 'javalite_runtime', srcs = ['javalite_runtime.java'], "
            + "proguard_specs = ['javalite_runtime.pro'])");

    scratch.file(
        "x/BUILD",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':foo'])",
        "proto_library(name = 'foo', deps = [':bar'])",
        "proto_library(name = 'bar')");
    NestedSet<Artifact> providedSpecs =
        getConfiguredTarget("//x:lite_pb2")
            .get(ProguardSpecProvider.PROVIDER)
            .getTransitiveProguardSpecs();

    assertThat(ActionsTestUtil.baseArtifactNames(providedSpecs))
        .containsExactly("javalite_runtime.pro_valid");
  }

  @Test
  public void testExperimentalProtoExtraActions() throws Exception {
    scratch.file(
        "x/BUILD",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':foo'])",
        "proto_library(name = 'foo', srcs = ['foo.proto'])");

    scratch.file(
        "xa/BUILD",
        "extra_action(",
        "    name = 'xa',",
        "    cmd = 'echo $(EXTRA_ACTION_FILE)')",
        "action_listener(",
        "    name = 'al',",
        "    mnemonics = ['Javac'],",
        "    extra_actions = [':xa'])");

    useConfiguration(
        "--experimental_action_listener=//xa:al",
        "--proto_compiler=//proto:compiler",
        "--proto_toolchain_for_javalite=//tools/proto/toolchains:javalite");
    ConfiguredTarget ct = getConfiguredTarget("//x:lite_pb2");
    NestedSet<DerivedArtifact> artifacts =
        ct.getProvider(ExtraActionArtifactsProvider.class).getTransitiveExtraActionArtifacts();

    Iterable<String> extraActionOwnerLabels =
        transform(
            artifacts.toList(),
            (artifact) -> artifact == null ? null : artifact.getOwnerLabel().toString());

    assertThat(extraActionOwnerLabels).contains("//x:foo");
  }

  /**
   * Verify that a java_lite_proto_library exposes Starlark providers for the Java code it
   * generates.
   */
  @Test
  public void testJavaProtosExposeStarlarkProviders() throws Exception {
    scratch.file(
        "proto/extensions.bzl",
        "def _impl(ctx):",
        "  print (ctx.attr.dep[JavaInfo])",
        "custom_rule = rule(",
        "  implementation=_impl,",
        "  attrs={",
        "    'dep': attr.label()",
        "  },",
        ")");
    scratch.file(
        "protolib/BUILD",
        "load('//proto:extensions.bzl', 'custom_rule')",
        "proto_library(",
        "    name = 'proto',",
        "    srcs = [ 'file.proto' ],",
        ")",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':proto'])",
        "custom_rule(name = 'custom', dep = ':lite_pb2')");
    update(
        ImmutableList.of("//protolib:custom"),
        /* keepGoing= */ false,
        /* loadingPhaseThreads= */ 1,
        /* doAnalysis= */ true,
        new EventBus());
    // Implicitly check that `update()` above didn't throw an exception. This implicitly checks that
    // ctx.attr.dep.java.{transitive_deps, outputs}, above, is defined.
  }

  @Test
  public void testProtoLibraryInterop() throws Exception {
    scratch.file(
        "protolib/BUILD",
        "proto_library(",
        "    name = 'proto',",
        "    srcs = [ 'file.proto' ],",
        ")",
        "java_lite_proto_library(name = 'lite_pb2', deps = [':proto'])");
    update(
        ImmutableList.of("//protolib:lite_pb2"),
        /* keepGoing= */ false,
        /* loadingPhaseThreads= */ 1,
        /* doAnalysis= */ true,
        new EventBus());
  }

  /**
   * Tests that a java_proto_library only provides direct jars corresponding on the proto_library
   * rules it directly depends on, excluding anything that the proto_library rules depends on
   * themselves. This does not concern strict-deps in the compilation of the generated Java code
   * itself, only compilation of regular code in java_library/java_binary and similar rules.
   *
   * <p>Here, a java_lite_proto_library dependes on an alias proto. We make sure that the system
   * behaves as if we depend directly on the aliased proto_library.
   */
  @Test
  public void jplCorrectlyDefinesDirectJars_strictDepsEnabled_aliasProto() throws Exception {
    scratch.file(
        "x/BUILD",
        "java_lite_proto_library(name = 'foo_java_proto_lite', deps = [':foo_proto'])",
        "proto_library(",
        "    name = 'foo_proto',",
        "    deps = [ ':bar_proto' ],",
        ")",
        "proto_library(",
        "    name = 'bar_proto',",
        "    srcs = [ 'bar.proto' ],",
        ")");

    JavaCompilationArgsProvider compilationArgsProvider =
        getProvider(
            JavaCompilationArgsProvider.class, getConfiguredTarget("//x:foo_java_proto_lite"));

    Iterable<String> directJars =
        prettyArtifactNames(compilationArgsProvider.getDirectCompileTimeJars());

    assertThat(directJars).containsExactly("x/libbar_proto-lite-hjar.jar");
  }

  /**
   * Tests that when strict-deps is disabled, java_lite_proto_library provides (in its "direct"
   * jars) all transitive classes, not only direct ones. This does not concern strict-deps in the
   * compilation of the generated Java code itself, only compilation of regular code in
   * java_library/java_binary and similar rules.
   */
  @Test
  @Ignore("TODO(b/216484418): Systematize this test with its new version.")
  public void jplCorrectlyDefinesDirectJars_strictDepsDisabled() throws Exception {
    scratch.file(
        "x/BUILD",
        "java_lite_proto_library(name = 'foo_lite_pb', deps = [':foo'])",
        "proto_library(",
        "    name = 'foo',",
        "    srcs = [ 'foo.proto' ],",
        "    deps = [ ':bar' ],",
        ")",
        "java_lite_proto_library(name = 'bar_lite_pb', deps = [':bar'])",
        "proto_library(",
        "    name = 'bar',",
        "    srcs = [ 'bar.proto' ],",
        "    deps = [ ':baz' ],",
        ")",
        "proto_library(",
        "    name = 'baz',",
        "    srcs = [ 'baz.proto' ],",
        ")");

    {
      JavaCompileAction action =
          (JavaCompileAction)
              getGeneratingAction(getConfiguredTarget("//x:foo_lite_pb"), "x/libfoo-lite.jar");
      assertThat(prettyArtifactNames(getInputs(action, getDirectJars(action)))).isEmpty();
    }

    {
      JavaCompileAction action =
          (JavaCompileAction)
              getGeneratingAction(getConfiguredTarget("//x:bar_lite_pb"), "x/libbar-lite.jar");
      assertThat(prettyArtifactNames(getInputs(action, getDirectJars(action)))).isEmpty();
    }
  }

  /** Tests that java_lite_proto_library's aspect exposes a Starlark provider named 'proto_java'. */
  @Test
  @Ignore
  // TODO(elenairina): Enable this test when proto_java is returned from the aspect in Starlark
  // version of java_lite_proto_library.
  public void testJavaLiteProtoLibraryAspectProviders() throws Exception {
    scratch.file(
        "x/aspect.bzl",
        "MyInfo = provider()",
        "def _foo_aspect_impl(target,ctx):",
        "  proto_found = hasattr(target, 'proto_java')",
        "  if hasattr(ctx.rule.attr, 'deps'):",
        "    for dep in ctx.rule.attr.deps:",
        "      proto_found = proto_found or dep.proto_found",
        "  return MyInfo(proto_found = proto_found)",
        "foo_aspect = aspect(_foo_aspect_impl, attr_aspects = ['deps'])",
        "def _foo_rule_impl(ctx):",
        "  return MyInfo(result = ctx.attr.dep.proto_found)",
        "foo_rule = rule(_foo_rule_impl, attrs = { 'dep' : attr.label(aspects = [foo_aspect])})");
    scratch.file(
        "x/BUILD",
        "load(':aspect.bzl', 'foo_rule')",
        "java_lite_proto_library(name = 'foo_java_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'], java_lib = ':lib')",
        "foo_rule(name = 'foo_rule', dep = 'foo_java_proto')");
    ConfiguredTarget target = getConfiguredTarget("//x:foo_rule");
    Provider.Key key =
        new StarlarkProvider.Key(
            Label.parseAbsolute("//x:aspect.bzl", ImmutableMap.of()), "MyInfo");
    StructImpl myInfo = (StructImpl) target.get(key);
    Boolean result = (Boolean) myInfo.getValue("result");

    // "yes" means that "proto_java" was found on the proto_library + java_proto_library aspect.
    assertThat(result).isTrue();
  }

  private static <P extends TransitiveInfoProvider> P getProvider(
      Class<P> providerClass, ConfiguredTarget target) {
    JavaInfo javaInfo = target.get(JavaInfo.PROVIDER);
    return javaInfo.getProvider(providerClass);
  }
}
