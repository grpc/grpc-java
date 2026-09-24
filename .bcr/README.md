# Bazel Central Registry (BCR) Publishing for grpc-java

This document explains how grpc-java releases are published to the [Bazel Central Registry (BCR)](https://github.com/bazelbuild/bazel-central-registry).

## Overview

The BCR is the official registry for Bazel modules, allowing users to depend on grpc-java using Bazel's module system (bzlmod). When a new version of grpc-java is released, it is automatically published to the BCR via a GitHub Actions workflow.

## How It Works

### Automated Publishing Workflow

The publishing process is automated through the GitHub Actions workflow located at `.github/workflows/publish-to-bcr.yml`. This workflow:

1. **Triggers automatically** when a new release is published on GitHub
2. **Can be manually triggered** via workflow_dispatch for re-runs or troubleshooting
3. **Uses the official publish-to-bcr action** from bazel-contrib
4. **Generates attestations** for release provenance and security
5. **Creates a pull request** directly to the official Bazel Central Registry (https://github.com/bazelbuild/bazel-central-registry) with the new version

### BCR Configuration Files

The BCR configuration is stored in the `.bcr/` directory:

- **`metadata.template.json`**: Contains module metadata including homepage, maintainers, and repository information
- **`source.template.json`**: Specifies how to download the source archive (uses GitHub release tags)
- **`presubmit.yml`**: Defines CI tests that run in the BCR before accepting the new version

### Presubmit Testing

Before a new version is accepted into the BCR, it must pass presubmit tests defined in `.bcr/presubmit.yml`. These tests:

- **Build key targets** from the grpc-java module to ensure basic functionality
- **Test the examples module** which demonstrates using grpc-java as a dependency
- **Run on multiple platforms**: Debian, macOS, Ubuntu, Windows
- **Test with multiple Bazel versions**: 7.x and 8.x

The examples directory serves as a test module that uses `local_path_override` to depend on the main grpc-java module, providing comprehensive integration testing.

## Setup and Configuration

### For Repository Maintainers

To enable BCR publishing for grpc-java, follow these steps:

#### 1. Create a Personal Access Token (PAT)

A GitHub Personal Access Token is required to create pull requests to the Bazel Central Registry.

**Creating a Classic PAT:**

1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Give it a descriptive name like "grpc-java BCR Publishing"
4. Select the following scopes:
   - `repo` (Full control of private repositories)
   - `workflow` (Update GitHub Action workflows)
5. Set an appropriate expiration (consider using no expiration for automation)
6. Click "Generate token" and **save the token securely**

**Note about Fine-grained PATs:**
Fine-grained tokens are not fully supported yet because they cannot open pull requests against public repositories (see [GitHub roadmap issue #600](https://github.com/github/roadmap/issues/600)).

#### 2. Configure Repository Secrets

1. Go to the grpc-java repository Settings → Secrets and variables → Actions
2. Click "New repository secret"
3. Name: `BCR_PUBLISH_TOKEN`
4. Value: Paste the PAT you created
5. Click "Add secret"

#### 3. Verify Workflow Permissions

Ensure the workflow has the necessary permissions in the repository settings:

- Go to Settings → Actions → General → Workflow permissions
- Ensure "Read and write permissions" is selected
- Ensure "Allow GitHub Actions to create and approve pull requests" is checked

## Publishing a New Release

### Automatic Publishing

When you create a new release on GitHub:

1. Go to the grpc-java repository
2. Create a new release with a tag (e.g., `v1.76.0`)
3. The `publish-to-bcr.yml` workflow will automatically trigger
4. Monitor the workflow run in the Actions tab
5. If successful, a PR will be created in the BCR repository

### Manual Publishing

If you need to manually trigger the publishing workflow:

1. Go to Actions → Publish to BCR
2. Click "Run workflow"
3. Enter the tag name (e.g., `v1.76.0`)
4. Click "Run workflow"

### Monitoring the Workflow

1. Go to the Actions tab in the grpc-java repository
2. Look for the "Publish to BCR" workflow run
3. Check the logs for any errors or issues
4. If successful, you'll see a link to the created BCR pull request

## BCR Pull Request Review

After the workflow creates a PR in the BCR:

1. The PR will be automatically assigned to the module maintainers
2. BCR CI will run presubmit tests to validate the new version
3. Maintainers should review and approve the PR
4. Once approved and tests pass, the BCR team will merge the PR
5. The new version becomes available in the BCR

## Troubleshooting

### Common Issues

**Workflow fails with authentication error:**
- Verify the `BCR_PUBLISH_TOKEN` secret is correctly set
- Ensure the PAT has not expired
- Check that the PAT has the required `repo` and `workflow` scopes

**Presubmit tests fail in BCR:**
- Review the test logs in the BCR PR
- Ensure the MODULE.bazel file is correctly formatted
- Verify all dependencies are available in BCR
- Test locally using the BCR's local testing instructions

**PR creation fails:**
- Verify the registry fork exists and is accessible
- Ensure the PAT has permissions for the fork repository
- Check that the workflow has correct permissions

**Version already exists:**
- BCR is add-only; you cannot replace existing versions
- If a fix is needed, it should be fixed upstream and a new version submitted
- If the fix is only in BCR patches (nothing to fix upstream), use the `.bcr.N` suffix format (e.g., `1.75.0.bcr.1`)

### Getting Help

- Check the [BCR documentation](https://github.com/bazelbuild/bazel-central-registry/blob/main/docs/README.md)
- Review the [publish-to-bcr documentation](https://github.com/bazel-contrib/publish-to-bcr)
- Contact BCR maintainers at bcr-maintainers@bazel.build
- File issues in the appropriate repository

## Maintainers

The following people are listed as maintainers for grpc-java in the BCR and will be notified of new PRs:

- Eric Anderson (@ejona86)
- Abhishek Agrawal (@AgraVator)
- Kannan J (@kannanjgithub)
- MV Shiva (@shivaspeaks)

Maintainers have approval rights for BCR PRs related to grpc-java, even without direct write access to the BCR repository.

## Patches

The BCR may include patches to the grpc-java module to ensure compatibility with the rest of the BCR ecosystem. These patches are typically applied by BCR maintainers and may include:

- Updating dependency versions to use BCR-compatible versions
- Adding missing dependencies required for Bazel module builds
- Adjusting build configurations for BCR compatibility

**Note:** If you need to include custom patches with your release, you can add them to the `.bcr/patches/` directory in the grpc-java repository. All patches must:
- Have the `.patch` extension
- Be in the `-p1` format (apply from the repository root)
- Be listed in the BCR entry's `source.json` file (automatically handled by the publish-to-bcr workflow)

The publish-to-bcr workflow will automatically include any patches found in `.bcr/patches/` when creating the BCR entry.

## Module Structure

grpc-java is published as a single module to the BCR:

- **Main module**: The root `MODULE.bazel` defines the grpc-java module
- **Test module**: The `examples/` directory serves as a test module during presubmit

If additional modules need to be published from this repository in the future, they can be configured using the `moduleRoots` field in `.bcr/config.yml`.

## References

- [Bazel Central Registry](https://github.com/bazelbuild/bazel-central-registry)
- [BCR Contribution Guidelines](https://github.com/bazelbuild/bazel-central-registry/blob/main/docs/README.md)
- [publish-to-bcr GitHub Action](https://github.com/bazel-contrib/publish-to-bcr)
- [Bzlmod User Guide](https://bazel.build/docs/bzlmod)
- [grpc-java in BCR](https://github.com/bazelbuild/bazel-central-registry/tree/main/modules/grpc-java)

## License

This documentation and the BCR configuration files are part of grpc-java and are subject to the same Apache 2.0 license.
