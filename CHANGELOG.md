# 0.1.0-alpha.1

Category: patch

- Scaffold the NeoForge 1.21.1 mod project (DPOP-11, implements DPOP-2): Gradle project skeleton with an empty mod entry point (no population/cell/entity logic), CI build on PR and push to `main`, a server-boot smoke test runnable in CI and locally, and a path-gated release workflow that publishes an immutable GitHub release from `GITHUB_TOKEN` alone. No Modrinth publishing is wired.
