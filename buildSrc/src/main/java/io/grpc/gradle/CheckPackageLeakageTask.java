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

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskExecutionException;

/** Verifies all class files within jar files are in a specified Java package. */
public abstract class CheckPackageLeakageTask extends DefaultTask {
  public CheckPackageLeakageTask() {
    // Fake output for UP-TO-DATE checking
    getOutputs().file(getProject().getLayout().getBuildDirectory().file("tmp/" + getName()));
  }

  @Classpath
  abstract ConfigurableFileCollection getFiles();

  @Input
  abstract Property<String> getPrefix();

  @TaskAction
  public void checkLeakage() throws IOException {
    String jarEntryPrefixName = getPrefix().get().replace('.', '/');
    boolean packageLeakDetected = false;
    for (File jar : getFiles()) {
      try (JarFile jf = new JarFile(jar)) {
        for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
          JarEntry entry = e.nextElement();
          if (entry.getName().endsWith(".class")
              && !entry.getName().startsWith(jarEntryPrefixName)) {
            packageLeakDetected = true;
            System.out.println("WARNING: package leaked, may need relocation: " + entry.getName());
          }
        }
      }
    }
    if (packageLeakDetected) {
      throw new TaskExecutionException(this,
          new IllegalStateException("Resource leakage detected!"));
    }
  }
}
