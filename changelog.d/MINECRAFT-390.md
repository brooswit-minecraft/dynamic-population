bump: minor

### Added
- Villager, Zombie, and Skeleton Kings are now real, visible, persistent entities (`villager_king`,
  `zombie_king`, `skeleton_king`) instead of a Villager-only level attachment; they never naturally despawn
  and survive save/load, chunk unload/reload, and server restart.
- Zombie and Skeleton now have their own non-persistent "filler" populations (ordinary vanilla
  Zombie/Skeleton entities, bounded reconciliation same as Fillager) alongside Fillagers, each with its own
  independent per-cell density field.
- Threshold-driven population battles: at local faction density >= 500 (existing 0..1000 per-cell scale),
  that faction's non-persistent members attack opposing non-persistent factions' members. A narrowly-scoped
  exception to MINECRAFT-389's no-retaliation rule lets this apply to Fillagers too.
- Local faction density > 250 lifts the spawn-light-level restriction and suppresses sun-exposure burning
  for that faction's ordinary Zombie/Skeleton fillers (independent of the combat threshold).
- `/dpop king settle [villager|zombie|skeleton]` founds a King of the given faction (bare `settle` still
  founds a Villager King, unchanged).
- A per-faction day/night spawn-rate multiplier for reconciliation (never despawn/density/Kings): Fillager
  uses an independent 12h/12h day/night split (100%/25%); Zombie and Skeleton share three 8h phases
  (Day/Early-night/Late-night) with a 0.25/1.00/0.50 and 0.25/0.50/1.00 matrix respectively, each faction
  peaking in its own phase. Composed independently of, never gating, the >250 light/sun override.

### Changed
- King territory/uniqueness is now evaluated per-faction: two Kings of the SAME faction still can't settle
  within the configured radius of each other, but Kings of different factions can coexist at/near the same
  location (see docs/villager-king.md for the rationale).

### Not implemented (explicit follow-up)
- Natural King spawning/founding remains unimplemented -- `/dpop king settle` is still the only way to found
  one, for every faction.
