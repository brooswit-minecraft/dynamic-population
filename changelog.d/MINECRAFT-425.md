bump: minor

### Changed
- Every population-owned entity (Fillager, tagged Zombie/Skeleton filler, and all three Kings) now
  subtracts a flat `population_death_target_decrement` (default 25, on the existing 0..1000 scale,
  floored at zero) from its own faction's field at its actual death cell, exactly once per actual death,
  regardless of cause. Zombie/Skeleton fillers and Kings never had a per-death decrement before.
- Replaces Fillager's former hazard/other-cause death split
  (`fillager_unsafe_death_target_decrement` / `fillager_other_death_target_decrement`) with the single
  `population_death_target_decrement` config key -- every actual Fillager death now applies the same
  amount regardless of whether it died to a local hazard or another cause.

### Removed
- Config keys `fillager_unsafe_death_target_decrement` and `fillager_other_death_target_decrement`
  (superseded by `population_death_target_decrement` above).
