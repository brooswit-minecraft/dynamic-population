bump: patch

### Changed
- Explicit (player/command) King placement (`/dpop king settle`) no longer checks sky light, surface depth, or same-faction spacing. All three factions can now be settled underground, in the dark, in any biome, or next to another King of the same faction; the only remaining failure mode is `INSERTION_FAILED` (entity insertion itself fails). Natural founding's cadence, probability, loaded-live-King check, density gate, and physical-placement safety are unchanged.

### Removed
- The `king_min_sky_light`, `king_max_depth_below_surface_blocks`, and `king_uniqueness_radius_cells` config keys, and the `KingTerritory`/`KingUniqueness` pure-logic classes that backed them -- nothing else read them once the gate they implemented was removed.
