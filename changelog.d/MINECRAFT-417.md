bump: minor

### Added
- A compact, toggleable debug population HUD showing the Villager/Fillager, Zombie, and Skeleton
  densities for the population cell the player currently stands in. Toggled with a keybind
  (`key.dynamicpopulation.toggle_hud`, default G), rendered in the top-right corner, deliberately
  separate from any mission HUD.
- The HUD reads the exact same typed, per-faction field `PopulationCombatTargetGoal` already uses for
  spawn/combat decisions (`PopulationSimulation.field(level, faction)` + `field.cellAt(pos)`), never a
  separate mob-count or a derived value -- and distinguishes a genuinely unreadable/unknown cell (unloaded
  anchor chunk, corrupt data, or a non-simulated dimension) from a real loaded zero, both in the network
  payload and on screen.
- Server-side updates are bounded and targeted: only players who toggled the HUD on are ever checked, at
  most once every `hud_update_interval_ticks` (new config key, default 10), and only sent when something
  actually changed (cell/layer crossing, dimension change, or a density/readability change while
  standing still) -- never a broadcast, never every render frame.
- The server-to-client payload carries dimension, cell/layer, and the position it was computed for, so a
  stale update (e.g. one that arrives for a dimension the player has since left) is rejected client-side
  instead of displayed. The client-side cache is cleared on disconnect, reconnect, and dimension change.
