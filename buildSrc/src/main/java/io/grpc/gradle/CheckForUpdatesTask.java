/*
 * Copyright 2023 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;

/**
 * Checks every dependency in the version catalog to see if there is a newer version available. The
 * passed configuration restricts the versions considered.
 */
public abstract class CheckForUpdatesTask extends DefaultTask {
  private final Set<Library> libraries;

  @SuppressWarnings("this-escape", "deprecation")
  @Inject
  public CheckForUpdatesTask(Configuration updateConf, String catalog, RegularFile commentFile)
      throws IOException {
    // Check for overrides to the default version selection ('+'), using comments of the form:
    // # checkForUpdates: library-name:1.2.+
    List<String> fileComments = Files.lines(commentFile.getAsFile().toPath())
        .filter(l -> l.matches("# *checkForUpdates:.*"))
        .map(l -> l.replaceFirst("# *checkForUpdates:", "").trim())
        .collect(Collectors.toList());
    Map<String, String> aliasToVersionSelector = new HashMap<>(2*fileComments.size());
    for (String comment : fileComments) {
      String[] parts = comment.split(":", 2);
      String name = parts[0].replaceAll("[_-]", ".");
      if (aliasToVersionSelector.put(name, parts[1]) != null) {
        throw new RuntimeException("Duplicate checkForUpdates comment for library: " + name);
      }
    }

    updateConf.setVisible(false);
    updateConf.setTransitive(false);
    VersionCatalog versionCatalog = getProject().getExtensions().getByType(VersionCatalogsExtension.class).named(catalog);
    Set<Library> libraries = new LinkedHashSet<Library>();
    for (String name : versionCatalog.getLibraryAliases()) {
      org.gradle.api.artifacts.MinimalExternalModuleDependency dep = versionCatalog.findLibrary(name).get().get();

      Configuration oldConf = updateConf.copy();
      Dependency oldDep = getProject().getDependencies().create(
          depMap(dep.getGroup(), dep.getName(), dep.getVersionConstraint().toString(), "pom"));
      oldConf.getDependencies().add(oldDep);

      Configuration newConf = updateConf.copy();
      String versionSelector = aliasToVersionSelector.remove(name);
      if (versionSelector == null) {
        versionSelector = "+";
      }
      Dependency newDep = getProject().getDependencies().create(
          depMap(dep.getGroup(), dep.getName(), versionSelector, "pom"));
      newConf.getDependencies().add(newDep);

      libraries.add(new Library(
          name,
          oldConf.getIncoming().getResolutionResult().getRootComponent(),
          newConf.getIncoming().getResolutionResult().getRootComponent()));
    }
    if (!aliasToVersionSelector.isEmpty()) {
      throw new RuntimeException(
          "Unused checkForUpdates comments: " + aliasToVersionSelector.keySet());
    }
    this.libraries = Collections.unmodifiableSet(libraries);
  }

  private static Map<String, String> depMap(
      String group, String name, String version, String classifier) {
    Map<String, String> map = new HashMap<>();
    map.put("group", group);
    map.put("name", name);
    map.put("version", version);
    map.put("classifier", classifier);
    return map;
  }

  @Nested
  protected Set<Library> getLibraries() {
    return libraries;
  }

  @TaskAction
  public void checkForUpdates() {
    for (Library lib : libraries) {
      String name = lib.getName();
      DependencyResult oldResult = lib.getOldResult().get().getDependencies().iterator().next();
      if (oldResult instanceof UnresolvedDependencyResult) {
        System.out.println(String.format(
            "- Current version of libs.%s not resolved", name));
        continue;
      }
      DependencyResult newResult = lib.getNewResult().get().getDependencies().iterator().next();
      if (newResult instanceof UnresolvedDependencyResult) {
        System.out.println(String.format(
            "- New version of libs.%s not resolved", name));
        continue;
      }
      ModuleVersionIdentifier oldId =
          ((ResolvedDependencyResult) oldResult).getSelected().getModuleVersion();
      ModuleVersionIdentifier newId =
          ((ResolvedDependencyResult) newResult).getSelected().getModuleVersion();
      if (oldId != newId) {
        System.out.println(String.format(
            "libs.%s = %s %s -> %s",
            name, newId.getModule(), oldId.getVersion(), newId.getVersion()));
      }
    }
  }

  public static final class Library {
    private final String name;
    private final Provider<ResolvedComponentResult> oldResult;
    private final Provider<ResolvedComponentResult> newResult;

    public Library(
        String name, Provider<ResolvedComponentResult> oldResult,
        Provider<ResolvedComponentResult> newResult) {
      this.name = name;
      this.oldResult = oldResult;
      this.newResult = newResult;
    }

    @Input
    public String getName() {
      return name;
    }

    @Input
    public Provider<ResolvedComponentResult> getOldResult() {
      return oldResult;
    }

    @Input
    public Provider<ResolvedComponentResult> getNewResult() {
      return newResult;
    }
  }
}
