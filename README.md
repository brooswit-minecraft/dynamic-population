# dynamic-population

Dynamic Population — NeoForge 1.21.1 mod ("Fillagers"): a persistent
population field that reconciles lightweight background villagers toward a
target density. See epic DPOP-1 / story DPOP-2 for the full design.

**Current state: scaffold only.** This repo currently ships an *empty* mod —
no population/cell/entity logic — plus the Gradle project, CI, server-boot
smoke test, and release pipeline that every later Dynamic Population story
builds on. modid: `dynamicpopulation`. Base package:
`io.github.brooswitminecraft.dynamicpopulation`.

## Building

Requires a JDK 21 **with `javac`** on `JAVA_HOME` (a JRE-only install fails
the `createMinecraftArtifacts` step with `release version 21 not supported`
even though the runtime is Java 21 — the NeoForge decompile/recompile step
needs a full JDK's `ct.sym`/`jmods`, not just a runtime).

```sh
./gradlew build
```

The built mod jar is written to `build/libs/dynamicpopulation-<version>.jar`.
The shipped version comes from `version.txt` (the single source of truth);
override it for a specific build with `-Pmod_version=<version>`, which is
what the release workflow does.

## Running the server-boot smoke test locally

`scripts/smoke-server.py` installs the built jar into a throwaway dedicated
NeoForge 1.21.1 server under a fresh temp directory, boots it, and verifies
two things from the server log: the mod's own startup line
(`Dynamic Population scaffold loaded`) appears, and the server reaches
`Done (...)! For help, type "help"` — then it sends `stop` and requires a
clean (exit code 0) shutdown. It never touches any persistent world or real
server; everything happens in a directory that is deleted on exit.

```sh
./gradlew build
python3 scripts/smoke-server.py build/libs/dynamicpopulation-<version>.jar
```

It fails loudly (non-zero exit, `RuntimeError`) if: the server process exits
before reaching ready, the mod's startup marker never appears in the log (a
mod-load crash — verified by temporarily throwing from the mod constructor
and re-running the script, which raises `RuntimeError: Server exited before
reaching ready (mod_loaded_seen=False)` with exit code 1), or the server
doesn't shut down cleanly within the timeout. CI runs the same script in the
release workflow (see below).

## CI

`.github/workflows/ci.yml` builds the mod on every pull request and every
push to `main` (JDK 21 via `actions/setup-java`, Gradle dependency caching,
least-privilege `contents: read`), and uploads the built jar as a workflow
artifact.

## Release

`.github/workflows/release.yml` is **path-gated**: it only runs on a push to
`main` that touches `version.txt` — the single source of truth for the
shipped version. Every other push to `main` (docs, CI tweaks, a source
change that hasn't been version-bumped yet) still runs CI, but does not cut
a release. This mirrors the pattern used across the org's other repos
(dynamic-atmosphere, sickos, schematic): a deliberate version bump is the
"ship this" signal, not "any push".

On a triggering push, the workflow builds and tests the mod at the resolved
version, runs the server-boot smoke test against the built jar, and then
publishes an **immutable GitHub release** (tag `v<version>`, jar attached)
using only the automatic `GITHUB_TOKEN` with least-privilege
`contents: write` — this repo has no Actions secrets or variables, and none
are wired here (in particular, **no Modrinth publishing**). It is idempotent
on retry: if a release for the resolved version already exists, the workflow
skips re-publishing rather than rebuilding or re-creating it.

Because it triggers on `push: branches: [main]`, this workflow cannot
execute end-to-end from a pull request — see the PR description for exactly
what was verified before merge versus what remains unevidenced until it
first runs on `main`.

## CHANGELOG conventions

`CHANGELOG.md` entries are headed by the version and a `Category:
patch|minor|breaking` marker. A `## Migration` section is added under an
entry only when there is something an operator/consumer must actually do
across that upgrade (config resets, save-data changes, etc.); a plain patch
or minor entry with nothing to migrate omits it. This is the same convention
dynamic-atmosphere uses; DPOP does **not** use dynamic-atmosphere's
`repository_dispatch` multi-key dispatch mechanism (documented but unshipped
there, and out of scope here).
