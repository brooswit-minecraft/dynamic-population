# changelog.d/ — per-PR changelog fragments

Every PR that touches a gated path (`src/` or `version.txt`) must add exactly
one new fragment here: `changelog.d/<TICKET>.md`. Do not append to a shared
unreleased section.

**Not automated yet**: the release workflow (`.github/workflows/release.yml`)
only triggers on a push to `version.txt` and does not read these fragments,
update `CHANGELOG.md`, or remove fragments itself (see MINECRAFT-29 PR #9
review). Until that automation exists, a fragment here is a record of what a
future manual `version.txt` bump + `CHANGELOG.md` entry should say -- it does
not by itself publish anything. Do not bump the version or add a dated
changelog heading in a feature PR; that stays a separate, deliberate step.

## Format

```md
bump: minor

### Added
- a thing this PR adds
```

Use `major`, `minor`, or `patch`; permitted sections are `BREAKING`, `Added`,
`Changed`, `Fixed`, and `Removed`. A breaking section requires a major bump.
