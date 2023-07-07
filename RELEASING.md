How to Create a Release of GRPC Java (for Maintainers Only)
===============================================================

Build Environments
------------------
We deploy GRPC to Maven Central under the following systems:
- Ubuntu 14.04 with Docker 13.03.0 that runs CentOS 7
- Windows 7 64-bit with Visual Studio
- Mac OS X 10.14.6

Other systems may also work, but we haven't verified them.

Common Variables
----------------
Many of the following commands expect release-specific variables to be set. Set
them before continuing, and set them again when resuming.

```bash
$ MAJOR=1 MINOR=7 PATCH=0 # Set appropriately for new release
$ VERSION_FILES=(
  build.gradle
  core/src/main/java/io/grpc/internal/GrpcUtil.java
  examples/build.gradle
  examples/pom.xml
  examples/android/clientcache/app/build.gradle
  examples/android/helloworld/app/build.gradle
  examples/android/routeguide/app/build.gradle
  examples/android/strictmode/app/build.gradle
  examples/example-*/build.gradle
  examples/example-*/pom.xml
  )
```


Branching the Release
---------------------
The first step in the release process is to create a release branch and bump
the SNAPSHOT version. Our release branches follow the naming
convention of `v<major>.<minor>.x`, while the tags include the patch version
`v<major>.<minor>.<patch>`. For example, the same branch `v1.7.x`
would be used to create all `v1.7` tags (e.g. `v1.7.0`, `v1.7.1`).

1. Review the issues in the current release [milestone](https://github.com/grpc/grpc-java/milestones)
   for issues that won't make the cut. Check if any of them can be
   closed. Be aware of the issues with the [TODO:release blocker][] label.
   Consider reaching out to the assignee for the status update.
2. For `master`, change root build files to the next minor snapshot (e.g.
   ``1.8.0-SNAPSHOT``).

   ```bash
   $ git checkout -b bump-version master
   # Change version to next minor (and keep -SNAPSHOT)
   $ sed -i 's/[0-9]\+\.[0-9]\+\.[0-9]\+\(.*CURRENT_GRPC_VERSION\)/'$MAJOR.$((MINOR+1)).0'\1/' \
     "${VERSION_FILES[@]}"
   $ sed -i s/$MAJOR.$MINOR.$PATCH/$MAJOR.$((MINOR+1)).0/ \
     compiler/src/test{,Lite}/golden/Test{,Deprecated}Service.java.txt
   $ ./gradlew build
   $ git commit -a -m "Start $MAJOR.$((MINOR+1)).0 development cycle"
   ```
3. Go through PR review and submit.
4. Create the release branch starting just before your commit and push it to GitHub:

   ```bash
   $ git fetch upstream
   $ git checkout -b v$MAJOR.$MINOR.x \
     $(git log --pretty=format:%H --grep "^Start $MAJOR.$((MINOR+1)).0 development cycle$" upstream/master)^
   $ git push upstream v$MAJOR.$MINOR.x
   ```
5. Continue with Google-internal steps at go/grpc/java/releasing, but stop
   before `Auto releasing using kokoro`.
6. Create a milestone for the next release.
7. Move items out of the release milestone that didn't make the cut. Issues that
   may be backported should stay in the release milestone. Treat issues with the
   'release blocker' label with special care.
8. Begin compiling release notes. This produces a starting point:

   ```bash
   $ echo "## gRPC Java $MAJOR.$MINOR.0 Release Notes" && echo && \
     git shortlog "$(git merge-base upstream/v$MAJOR.$((MINOR-1)).x upstream/v$MAJOR.$MINOR.x)"..upstream/v$MAJOR.$MINOR.x | cat && \
     echo && echo && echo "Backported commits in previous release:" && \
     git log --oneline "$(git merge-base v$MAJOR.$((MINOR-1)).0 upstream/v$MAJOR.$MINOR.x)"..v$MAJOR.$((MINOR-1)).0^
   ```

[TODO:release blocker]: https://github.com/grpc/grpc-java/issues?q=label%3A%22TODO%3Arelease+blocker%22
[TODO:backport]: https://github.com/grpc/grpc-java/issues?q=label%3ATODO%3Abackport

Tagging the Release
-------------------

1. Verify there are no open issues in the release milestone. Open issues should
   either be deferred or resolved and the fix backported. Verify there are no
   [TODO:release blocker][] nor [TODO:backport][] issues (open or closed), or
   that they are tracking an issue for a different branch.
2. Ensure that Google-internal steps completed at go/grpc/java/releasing#before-tagging-a-release.
3. For vMajor.Minor.x branch, change `README.md` to refer to the next release
   version. _Also_ update the version numbers for protoc if the protobuf library
   version was updated since the last release.

   ```bash
   $ git checkout v$MAJOR.$MINOR.x
   $ git pull upstream v$MAJOR.$MINOR.x
   $ git checkout -b release-v$MAJOR.$MINOR.$PATCH
   
   # Bump documented gRPC versions.
   # Also update protoc version to match protobuf version in gradle/libs.versions.toml.
   $ ${EDITOR:-nano -w} README.md
   
   $ git commit -a -m "Update README etc to reference $MAJOR.$MINOR.$PATCH"
   ```
4. Change root build files to remove "-SNAPSHOT" for the next release version
   (e.g. `0.7.0`). Commit the result and make a tag:

   ```bash
   # Change version to remove -SNAPSHOT
   $ sed -i 's/-SNAPSHOT\(.*CURRENT_GRPC_VERSION\)/\1/' "${VERSION_FILES[@]}"
   $ sed -i s/-SNAPSHOT// compiler/src/test{,Lite}/golden/Test{,Deprecated}Service.java.txt
   $ ./gradlew build
   $ git commit -a -m "Bump version to $MAJOR.$MINOR.$PATCH"
   $ git tag -a v$MAJOR.$MINOR.$PATCH -m "Version $MAJOR.$MINOR.$PATCH"
   ```
5. Change root build files to the next snapshot version (e.g. `0.7.1-SNAPSHOT`).
   Commit the result:

   ```bash
   # Change version to next patch and add -SNAPSHOT
   $ sed -i 's/[0-9]\+\.[0-9]\+\.[0-9]\+\(.*CURRENT_GRPC_VERSION\)/'$MAJOR.$MINOR.$((PATCH+1))-SNAPSHOT'\1/' \
     "${VERSION_FILES[@]}"
   $ sed -i s/$MAJOR.$MINOR.$PATCH/$MAJOR.$MINOR.$((PATCH+1))-SNAPSHOT/ \
     compiler/src/test{,Lite}/golden/Test{,Deprecated}Service.java.txt
   $ ./gradlew build
   $ git commit -a -m "Bump version to $MAJOR.$MINOR.$((PATCH+1))-SNAPSHOT"
   ```
6. Go through PR review and push the release tag and updated release branch to
   GitHub (DO NOT click the merge button on the GitHub page):

   ```bash
   $ git checkout v$MAJOR.$MINOR.x
   $ git merge --ff-only release-v$MAJOR.$MINOR.$PATCH
   $ git push upstream v$MAJOR.$MINOR.x
   $ git push upstream v$MAJOR.$MINOR.$PATCH
   ```
7. Close the release milestone.

8. Trigger build as described in "Auto releasing using kokoro" at
   go/grpc/java/releasing.

    It runs three jobs on Kokoro, one on each platform. See their scripts:
    `linux_artifacts.sh`, `windows.bat`, and `macos.sh`. The mvn-artifacts/
    outputs of each script is combined into a single folder and then processed
    by `upload_artifacts.sh`, which signs the files and uploads to Sonatype.

9. Once all of the artifacts have been pushed to the staging repository, the
   repository should have been closed by `upload_artifacts.sh`. Closing triggers
   several sanity checks on the repository. If this completes successfully, the
   repository can then be `released`, which will begin the process of pushing
   the new artifacts to Maven Central (the staging repository will be destroyed
   in the process). You can see the complete process for releasing to Maven
   Central on the [OSSRH site](https://central.sonatype.org/pages/releasing-the-deployment.html).

10. We have containers for each release to detect compatibility regressions with
    old releases. Generate one for the new release by following the [GCR image
    generation instructions][gcr-image]. Summary:
    ```bash
    # If you haven't previously configured docker:
    gcloud auth configure-docker

    # In main grpc repo, add the new version to matrix
    ${EDITOR:-nano -w} tools/interop_matrix/client_matrix.py
    tools/interop_matrix/create_matrix_images.py --git_checkout --release=v$MAJOR.$MINOR.$PATCH \
        --upload_images --language java
    docker pull gcr.io/grpc-testing/grpc_interop_java:v$MAJOR.$MINOR.$PATCH
    docker_image=gcr.io/grpc-testing/grpc_interop_java:v$MAJOR.$MINOR.$PATCH \
        tools/interop_matrix/testcases/java__master

    # Commit the changes
    git commit --all -m "Add grpc-java $MAJOR.$MINOR.$PATCH to client_matrix.py"

    # Create a PR and run ad-hoc test against your PR
    ```
[gcr-image]: https://github.com/grpc/grpc/blob/master/tools/interop_matrix/README.md#step-by-step-instructions-for-adding-a-gcr-image-for-a-new-release-for-compatibility-test

11. Update gh-pages with the new Javadoc. Generally the file is on repo1
    15 minutes after publishing:

    ```bash
    git checkout gh-pages
    git pull --ff-only upstream gh-pages
    rm -r javadoc/
    wget -O grpc-all-javadoc.jar "https://repo1.maven.org/maven2/io/grpc/grpc-all/$MAJOR.$MINOR.$PATCH/grpc-all-$MAJOR.$MINOR.$PATCH-javadoc.jar"
    unzip -d javadoc grpc-all-javadoc.jar
    patch -p1 < ga.patch
    rm grpc-all-javadoc.jar
    rm -r javadoc/META-INF/
    git add -A javadoc
    git commit -m "Javadoc for $MAJOR.$MINOR.$PATCH"
    ```

    Push gh-pages to the main repository and verify the current version is
    [live on grpc.io](https://grpc.io/grpc-java/javadoc/).

12. Add [Release Notes](https://github.com/grpc/grpc-java/releases) for the new tag.
    *Make sure that any backports are reflected in the release notes.*


Update README.md
----------------
After waiting ~1 day and verifying that the release is indexed on [Maven
Central](https://search.maven.org/search?q=g:io.grpc), cherry-pick the commit
that updated the README into the master branch.

```bash
$ git checkout -b bump-readme master
$ git cherry-pick v$MAJOR.$MINOR.$PATCH^
$ git push --set-upstream origin bump-readme
```

Create a PR and go through the review process

Update version referenced by tutorials
--------------------------------------

Update `params.grpc_vers.java` in
[config.yaml](https://github.com/grpc/grpc.io/blob/master/config.yaml)
of the grpc.io repository.

Notify the Community
--------------------
Post a release announcement to [grpc-io](https://groups.google.com/forum/#!forum/grpc-io)
(`grpc-io@googlegroups.com`) with the title `gRPC-Java v$MAJOR.$MINOR.$PATCH
Released`. The email content should link to the GitHub release notes and include
a copy of them.
