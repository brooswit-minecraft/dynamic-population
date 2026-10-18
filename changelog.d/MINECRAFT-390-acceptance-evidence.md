bump: patch

### Added
- `/dpop fillager promote <tag>` -- a debug-only (op level 2) command that performs the exact same
  Fillager-to-Villager conversion `Fillager#mobInteract`'s successful emerald-cost path already uses, without
  requiring a connected player or a real item stack. Added solely so `scripts/smoke-server.py` can live-test
  promoting a Fillager while it is actively mid-panic; no production gameplay path calls it.
- `scripts/smoke-server.py` gained live, dedicated-server acceptance coverage for MINECRAFT-390's already-
  merged feature set: a real server process stop/relaunch verifying King identity/uniqueness survives a
  true restart (not just chunk reload), below-threshold Fillager hurt-triggered flee/recovery, and
  promotion while a Fillager is actively panicking. Live 499/500/501 density-crossing and a decisive (non-
  inconclusive) multi-entity combat outcome were implemented but not locally re-verified end to end before
  this fragment was written -- see the PR description for exact status.

### Fixed
- `Fillager#mobInteract`'s promotion path (and the new debug command above) now carries over the
  entity's scoreboard tags to the converted Villager; `convertTo` does not do this on its own, which
  previously made a promoted, still-tagged Fillager unfindable by the same tag-based selector that
  identified it before promotion.
