bump: minor

### Added
- Rare natural King founding: on each 200-tick boundary (`king_natural_founding_interval_ticks`),
  independently for each faction (Villager/Fillager, Zombie, Skeleton), a 1-in-10,000
  (`king_natural_founding_chance_denominator`) random trial attempts to found a new King if no live King
  entity of that faction currently exists in any loaded chunk -- never one trial per loaded chunk. A
  winning trial picks one random loaded chunk and column, derives its surface Y, and attempts exactly once:
  a dry, sturdy, collision-free surface with that faction's local target density genuinely readable as zero.
  No biome/sky-light/surface-depth/registry-distance gate is applied (unlike `/dpop king settle`) and no
  chunk is ever generated or force-loaded to make the attempt. On success, broadcasts a location-free
  server chat message naming only the King's kind (e.g. "A Zombie King has spawned.").
- A King's actual death (natural OR command-settled) now broadcasts a matching location-free chat message
  (e.g. "A Skeleton King has died.") and retires that King's registry entry so it can never be resurrected
  by registry sync -- the faction's population density field itself is untouched.
