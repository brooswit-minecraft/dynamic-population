bump: minor

### Added
- Villager, Zombie, and Skeleton Kings are now mutually hostile across all six directed cross-type
  pairings (e.g. Villager King vs Zombie King, Zombie King vs Skeleton King, and so on) -- each proactively
  acquires and fights an opposing-faction King within normal combat range, with no population-density
  prerequisite. Same-type Kings never fight each other, and this does not make any King hostile to players
  or ordinary mobs/fillers. The Villager King gained its first-ever attack goal and attack-damage attribute
  to make this actual combat, not just a target predicate; the Zombie and Skeleton Kings reuse their
  existing vanilla attack AI unchanged.
