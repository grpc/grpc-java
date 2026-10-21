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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.Category;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

/**
 * Verifies that Maven would select the same versions as Gradle selected. This is essentially the
 * same as if we used Maven Enforcer's requireUpperBoundDeps in a Maven build. Gradle selects the
 * upperBound, so if Maven selects a different version there is a downgrade.
 */
public abstract class RequireUpperBoundDepsMatchTask extends DefaultTask {
  private final String projectPath;
  private final String projectVersion;

  public RequireUpperBoundDepsMatchTask() {
    projectPath = getProject().getPath();
    projectVersion = getProject().getVersion().toString();

    // Fake output for UP-TO-DATE checking
    getOutputs().file(getProject().getLayout().getBuildDirectory().file("tmp/" + getName()));
  }

  public void setConfiguration(Configuration conf) {
    getConfigurationName().set(conf.getName());
    getRoot().set(conf.getIncoming().getResolutionResult().getRootComponent());
  }

  @Input
  abstract Property<String> getConfigurationName();

  @Input
  abstract Property<ResolvedComponentResult> getRoot();

  @TaskAction
  public void checkDeps() {
    // Category.CATEGORY_ATTRIBUTE is the inappropriate Attribute because it is "desugared".
    // https://github.com/gradle/gradle/issues/8854
    Attribute<String> category = Attribute.of("org.gradle.category", String.class);

    // Breadth-first search like Maven for dependency resolution. Check and Maven and Gradle would
    // select the same version.
    Queue<DepAndParents> queue = new ArrayDeque<>();
    for (DependencyResult dep : getRoot().get().getDependencies()) {
      queue.add(new DepAndParents(dep, null, projectPath));
    }
    Set<String> found = new HashSet<>();
    while (!queue.isEmpty()) {
      DepAndParents depAndParents = queue.remove();
      ResolvedDependencyResult result = (ResolvedDependencyResult) depAndParents.dep;
      // Only libraries are "deps" in the typical sense, and non-libraries might not have versions.
      if (result.getResolvedVariant().getAttributes().getAttribute(category)
          != Category.LIBRARY)
        return;

      ModuleVersionIdentifier id = result.getSelected().getModuleVersion();
      String artifact = id.getGroup() + ":" + id.getName();
      if (found.contains(artifact))
        continue;
      found.add(artifact);
      String mavenVersion;
      if (result.getRequested() instanceof ProjectComponentSelector) {
        // Assume all projects use the same version.
        mavenVersion = projectVersion;
      } else {
        mavenVersion = ((ModuleComponentSelector) result.getRequested()).getVersion();
      }
      String gradleVersion = id.getVersion();
      if (!mavenVersion.equals(gradleVersion) && !mavenVersion.equals("[" + gradleVersion + "]")) {
        throw new RuntimeException(String.format(
            "Maven version skew: %s (%s != %s) Bad version dependency path: %s"
            + " Run './gradlew %s:dependencies --configuration %s' to diagnose",
            artifact, mavenVersion, gradleVersion, depAndParents.getParents(),
            projectPath, getConfigurationName().get()));
      }
      for (DependencyResult dep : result.getSelected().getDependencies()) {
        queue.add(new DepAndParents(dep, depAndParents, artifact + ":" + mavenVersion));
      }
    }
  }

  static final class DepAndParents {
    final DependencyResult dep;
    final DepAndParents parent;
    final String parentName;

    public DepAndParents(DependencyResult dep, DepAndParents parent, String parentName) {
      this.dep = dep;
      this.parent = parent;
      this.parentName = parentName;
    }

    public List<String> getParents() {
      List<String> parents = new ArrayList<>();
      DepAndParents element = this;
      while (element != null) {
        parents.add(element.parentName);
        element = element.parent;
      }
      Collections.reverse(parents);
      return parents;
    }
  }
}
