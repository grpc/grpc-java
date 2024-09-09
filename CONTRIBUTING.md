# How to contribute

We definitely welcome your patches and contributions to gRPC! Please read the gRPC
organization's [governance rules](https://github.com/grpc/grpc-community/blob/master/governance.md)
and [contribution guidelines](https://github.com/grpc/grpc-community/blob/master/CONTRIBUTING.md) before proceeding.


If you are new to github, please start by reading [Pull Request howto](https://help.github.com/articles/about-pull-requests/)

## Legal requirements

In order to protect both you and ourselves, you will need to sign the
[Contributor License Agreement](https://easycla.lfx.linuxfoundation.org/). When
you make a PR, a CLA bot will provide a link for the process.

## Compiling

See [COMPILING.md](COMPILING.md). Specifically, you'll generally want to set
`skipCodegen=true` so you don't need to deal with the C++ compilation.

## Code style

We follow the [Google Java Style
Guide](https://google.github.io/styleguide/javaguide.html). Our
build automatically will provide warnings for style issues.
[Eclipse](https://raw.githubusercontent.com/google/styleguide/gh-pages/eclipse-java-google-style.xml)
and
[IntelliJ](https://raw.githubusercontent.com/google/styleguide/gh-pages/intellij-java-google-style.xml)
style configurations are commonly useful. For IntelliJ 14, copy the style to
`~/.IdeaIC14/config/codestyles/`, start IntelliJ, go to File > Settings > Code
Style, and set the Scheme to `GoogleStyle`.

## Guidelines for Pull Requests
How to get your contributions merged smoothly and quickly.
 
- Create **small PRs** that are narrowly focused on **addressing a single concern**. We often times receive PRs that are trying to fix several things at a time, but only one fix is considered acceptable, nothing gets merged and both author's & review's time is wasted. Create more PRs to address different concerns and everyone will be happy.
 
- For speculative changes, consider opening an issue and discussing it to avoid
  wasting time on an inappropriate approach. If you are suggesting a behavioral
  or API change, consider starting with a [gRFC
  proposal](https://github.com/grpc/proposal).

- Follow [typical Git commit message](https://cbea.ms/git-commit/#seven-rules)
  structure. Have a good **commit description** as a record of **what** and
  **why** the change is being made. Link to a GitHub issue if it exists. The
  commit description makes a good PR description and is auto-copied by GitHub if
  you have a single commit when creating the PR.

  If your change is mostly for a single module (e.g., other module changes are
  trivial), prefix your commit summary with the module name changed. Instead of
  "Add HTTP/2 faster-than-light support to gRPC Netty" it is more terse as
  "netty: Add faster-than-light support".

- Don't fix code style and formatting unless you are already changing that line
  to address an issue. If you do want to fix formatting or style, do that in a
  separate PR.

- Unless your PR is trivial, you should expect there will be reviewer comments
  that you'll need to address before merging. Address comments with additional
  commits so the reviewer can review just the changes; do not squash reviewed
  commits unless the reviewer agrees. PRs are squashed when merging.

- Keep your PR up to date with upstream/master (if there are merge conflicts, we can't really merge your change).

- **All tests need to be passing** before your change can be merged. We recommend you **run tests locally** before creating your PR to catch breakages early on. Also, `./gradlew build` (`gradlew build` on Windows) **must not introduce any new warnings**.
 
- Exceptions to the rules can be made if there's a compelling reason for doing so.
