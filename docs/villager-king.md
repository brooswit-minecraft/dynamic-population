# Villager King (MINECRAFT-240, implements MINECRAFT-28, AC4)

A Villager King is a long-lived, unique "seed" for population in a level: it
prefers surface/plains territory to settle in, and raises the cell field's
target density (DPOP-4's storage API, read through the `king` package) in
cells near itself over time.

## Mechanism, not entity

The King is implemented as a NeoForge LEVEL data attachment registry
(`KingStorage`/`KingRecord`), the same storage pattern `CellFieldStorage`
already uses for the cell field — not a rendered Minecraft `Entity`. This
avoids needing new client assets/renderer/attributes, matches the ticket's
own "King entity/mechanism" phrasing, and keeps the logic unit-testable the
same way `CellGrid`/`Propagation` are: `KingTerritory`, `KingUniqueness`, and
`KingSeeding` are pure Java, taking plain values/the real `Propagation.Field`
contract; `KingManager`/`KingSimulation`/`KingCommands` are the thin NeoForge
glue that samples the real world and drives them.

Long-lived: a King never despawns on its own (no natural-expiry logic
exists), and its registry entry is a LEVEL attachment, which NeoForge
persists itself — it survives save/load. There is no numeric lifespan
tunable (see `king_seed_interval_ticks`'s config comment) because this
story's fixed decisions leave lifespan unbounded; there is nothing to bound.

This story does not auto-spawn Kings during world generation or via any
natural trigger — not specified by this ticket's fixed decisions.
`/dpop king settle` is the only way to found one (mirrors `/dpop set` being
the only way to seed population directly — see `docs/propagation.md`).

## Uniqueness ("relevant loaded area")

Defined concretely as: a second King may not spawn/persist within
`king_uniqueness_radius_cells` cells (horizontal, Chebyshev distance, at the
live `cell_size`) of an existing King in the same level. Vertical layer is
ignored — a King's territory is a horizontal surface region, not a column.
`KingUniqueness.allowed` is the pure check; `KingManager.wouldBeUnique` feeds
it every existing King's cell coordinate from the level's real registry.

## Territory preference

A location is suitable surface/plains territory (`KingTerritory.suitable`)
when all of:

- biome is `minecraft:plains` or `minecraft:sunflower_plains` specifically
  — not `minecraft:snowy_plains`. Vanilla ships no `BiomeTags.IS_PLAINS`-style
  tag to check against (verified against the real `BiomeTags` class), and a
  snowy biome doesn't match this ticket's "surface/plains-preferring"
  framing. This is a fixed tag-membership check, not a numeric tunable —
  there's no meaningful range for "how plains-y" a biome is.
- sky light (`LightLayer.SKY`) at the position is at least
  `king_min_sky_light`.
- the position is no more than `king_max_depth_below_surface_blocks` blocks
  below the heightmap surface (`Heightmap.Types.MOTION_BLOCKING`).

## Seeding

Every `king_seed_interval_ticks` server ticks, every King in every simulated
(Overworld, V1) level raises the real cell field's target density by
`king_seed_rate_ppt` (capped at `Propagation.CAPACITY`) in every traversable
cell within `king_seed_radius_cells` (Chebyshev, the King's own vertical
layer only) of itself (`KingSeeding.seed`, against the exact
`Propagation.Field`/`LevelField` glue `Propagation` itself steps against —
one storage path, never two). Seeded cells are marked active in
`PopulationSimulation`'s own active-set so propagation's existing tick picks
them up from there; the King does not step propagation itself.

## Config keys (widened AC8)

All read live via the existing volatile-snapshot pattern (see
`docs/cell-field-and-config.md`'s "Config scaffold" section), never
`static final` captured at class load.

| key | default | range | `RestartType` |
|---|---|---|---|
| `king_uniqueness_radius_cells` | 8 | 1..64 | `NONE` |
| `king_min_sky_light` | 9 | 0..15 | `NONE` |
| `king_max_depth_below_surface_blocks` | 4 | 0..64 | `NONE` |
| `king_seed_radius_cells` | 3 | 1..64 | `NONE` |
| `king_seed_rate_ppt` | 5 | 1..1000 | `NONE` |
| `king_seed_interval_ticks` | 20 | 1..1200 | `NONE` |

`RestartType.NONE` for all six: none of these values are part of the saved
cell-field or config-file *format* (unlike `cell_size`, which is
`RestartType.WORLD` because it changes the footprint the saved data is keyed
against) — they only tune King behavior at runtime, so a live change is safe
to pick up without a world restart. **This wasn't explicitly ruled on by
Brooswit for King-specific keys** — flagged on MINECRAFT-28 for an explicit
answer; treat this as the recommended default, not a settled decision.

No key here needs a `## Migration` note (per `docs/release-note-template.md`)
if its default changes later — none of them affect `cell_size` or
`configVersion`'s stored format, only in-memory behavior.

## Commands

`/dpop king settle` (op level 2): attempts to found a King at the command
source's position, checking territory suitability then uniqueness, in that
order; reports which check failed if it didn't settle.
`/dpop king list`: lists every King in the level (id + position).
`/dpop king remove`: removes the nearest King within 64 blocks of the
command source, if any.
