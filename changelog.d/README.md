# changelog.d/ — per-PR changelog fragments

Every PR that touches a gated path (`src/` or `version.txt`) must add exactly
one new fragment here: `changelog.d/<TICKET>.md`. Do not append to a shared
unreleased section.

The release workflow consumes fragments at merge time, updates `version.txt`
and `CHANGELOG.md`, then removes the fragments. Do not bump the version or add
a dated changelog heading in a feature PR.

## Format

```md
bump: minor

### Added
- a thing this PR adds
```

Use `major`, `minor`, or `patch`; permitted sections are `BREAKING`, `Added`,
`Changed`, `Fixed`, and `Removed`. A breaking section requires a major bump.
