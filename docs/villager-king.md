# Kings (MINECRAFT-240/MINECRAFT-28 AC4, generalized to three factions by MINECRAFT-390)

A King is a long-lived, unique "seed" for its faction's population in a
level: it prefers surface territory to settle in (no biome restriction, see
"Territory preference" below), and raises that
faction's cell field's target density (DPOP-4's storage API, read through
the `king` package) in cells near itself over time. As of MINECRAFT-390
there are three: the original Villager King, plus a Zombie King and a
Skeleton King, all following the same rules unless this doc says otherwise.

## A real, visible entity (MINECRAFT-390)

Before MINECRAFT-390 the King was a NeoForge LEVEL data attachment registry
only (`KingStorage`/`KingRecord`) — no rendered Minecraft `Entity` at all.
MINECRAFT-390 makes every King a real, visible entity:

- `VillagerKingEntity` — a sibling of `Fillager` (not a subclass: a King is
  never promotable and never a population-combat participant, and inheriting
  `Fillager` would require re-overriding both of those back out).
- `ZombieKingEntity` extends vanilla `Zombie`; `SkeletonKingEntity` extends
  vanilla `Skeleton` — directly, to inherit vanilla combat AI for free.

All three are ~1.5x the size of their ordinary counterpart (hitbox via
`EntityType.Builder#sized`, visual via the vanilla `generic.scale` attribute
— see `DynamicPopulationEntities.KING_VISUAL_SCALE`), have a larger health
pool than their faction's ordinary member, and are `persistenceRequired`
unconditionally (`isPersistenceRequired()` is hard-overridden to `true`, not
left to the mutable flag alone) so they never naturally despawn. No specific
size/health/damage/lifespan numbers were requested by the ticket; see each
King entity class's own javadoc for the exact values chosen and why.

**Sun immunity is unconditional for Zombie/Skeleton Kings**, not
threshold-gated like the ordinary filler's `>250`-density override below —
a King must never die to environmental sun exposure (a hard persistence
requirement), and the King's own territory preference (high sky light) would
otherwise put it directly in the sun. `isSunBurnTick()` is overridden to
always return `false` on both King entity classes; no other fire/lava
immunity is granted.

**The `KingRecord` attachment is still the pure territory/uniqueness/seeding
bookkeeping** — it is not a second, independent "King", it is position/
faction bookkeeping correlated to the ONE real entity by a shared id: the
record's `id` IS the spawned entity's UUID. `KingEntitySync` keeps the two in
sync and is how a pre-390 record (which has no entity yet) gets one spawned
exactly once — see "Persistence, migration, and no duplicates" below.

Long-lived: a King never despawns on its own (no natural-expiry logic
exists), and its registry entry is a LEVEL attachment, which NeoForge
persists itself — it survives save/load, and the real entity (being
`persistenceRequired`) is reloaded by vanilla's own entity persistence along
with its chunk. There is no numeric lifespan tunable (see
`king_seed_interval_ticks`'s config comment) because this story's fixed
decisions leave lifespan unbounded; there is nothing to bound.

This story does not auto-spawn Kings during world generation or via any
natural trigger — not specified by this ticket's fixed decisions, for ANY
faction. `/dpop king settle [faction]` is the only way to found one (mirrors
`/dpop set` being the only way to seed population directly — see
`docs/propagation.md`). **Natural King founding remains an explicit,
unimplemented follow-up** — MINECRAFT-390 scope item 7 is explicit that this
is out of scope; no spawn mechanism or rate was invented.

## Persistence, migration, and no duplicates (MINECRAFT-390)

- **Fresh settle** (`KingManager.settle`): spawns the entity FIRST (with a
  freshly-generated UUID), then writes a `KingRecord.Entry` using that same
  UUID as its id. One call, one entity, one record — never two independent
  sources of truth.
- **Reload/migration self-heal** (`KingEntitySync.sync`, called every King
  seeding tick): for every `KingRecord.Entry` whose King's CHUNK is currently
  loaded, if `level.getEntity(id)` finds nothing, spawn exactly one entity
  with that id. The chunk-loaded guard is deliberate: `getEntity` only finds
  entities belonging to loaded chunks, so without it an unloaded-chunk record
  would look "missing" purely because nothing has loaded it back in yet —
  spawning a second entity for it would be exactly the duplicate-entity bug
  this ticket forbids. Once a King's chunk IS loaded, "no entity found" really
  does mean "a pre-390 record that never had an entity" — vanilla's own
  entity persistence reloads a `persistenceRequired` entity together with its
  chunk with no lazy delay, so there is no race to account for.
- `KingEntitySync.missingEntities` is the pure decision this reduces to
  (`KingEntitySyncTest` covers it directly).

## Cross-faction uniqueness policy (MINECRAFT-390, decided and documented here)

**A King's "relevant loaded area" (uniqueness radius) is evaluated PER
FACTION ONLY.** `KingManager.wouldBeUnique` filters `KingManager.kings(level)`
down to the SAME faction before the pure `KingUniqueness.allowed` check ever
runs. Concretely: **a Zombie King and a Villager King CAN coexist at or near
the same location** — only two Kings of the *same* faction are mutually
exclusive within `king_uniqueness_radius_cells`.

Rationale: `king_uniqueness_radius_cells` represents "don't oversaturate one
faction's own seed network in one area" — a resource-competition concept
that is meaningless ACROSS factions, since each faction's density lives in
its own independent cell-field attachment (`cell_field` / `zombie_cell_field`
/ `skeleton_cell_field`) and seeding one faction's field never touches
another's. Allowing cross-faction coexistence is also exactly what makes
"battle lines" (see the threshold-combat doc below) possible at all: two
different factions' Kings seeding overlapping territory IS the intended way
a contested front forms.

## Territory preference applies unchanged to every faction (MINECRAFT-390)

No documented reason exists to prefer different terrain for a Zombie/
Skeleton King over a Villager King, so `KingManager.suitableTerritory`
applies the exact same rule (below) to every faction, unmodified. This is a
deliberate choice per ticket scope item 2 ("preserve existing...constraints
across factions unless you have a specific, documented reason to adapt"),
not an oversight — and it is also WHY a Zombie/Skeleton King settling on the
Villager King's own territory is expected, not a bug (see
the cross-faction uniqueness policy above).

## Uniqueness ("relevant loaded area")

Defined concretely as: a second King OF THE SAME FACTION may not
spawn/persist within `king_uniqueness_radius_cells` cells (horizontal,
Chebyshev distance, at the live `cell_size`) of an existing King of that
faction in the same level (see "Cross-faction uniqueness policy" above for
why this is same-faction only). Vertical layer is ignored — a King's
territory is a horizontal surface region, not a column. `KingUniqueness.allowed`
is the pure check; `KingManager.wouldBeUnique` feeds it every existing King
OF THE SAME FACTION's cell coordinate from the level's real registry.

## Territory preference

A location is suitable surface territory (`KingTerritory.suitable`) when all
of:

- sky light (`LightLayer.SKY`) at the position is at least
  `king_min_sky_light`.
- the position is no more than `king_max_depth_below_surface_blocks` blocks
  below the heightmap surface (`Heightmap.Types.MOTION_BLOCKING`).

**No biome restriction applies (MINECRAFT-413):** biome identity is not
checked at all, for any faction, in any vanilla or modded biome — a prior
plains-family-only restriction (`minecraft:plains`/`minecraft:sunflower_plains`,
deliberately excluding `minecraft:snowy_plains`) was removed by that ticket.
Only sky light and surface depth gate territory now.

## Seeding

Every `king_seed_interval_ticks` server ticks, every King in every simulated
(Overworld, V1) level raises **its own faction's** cell field's target
density by `king_seed_rate_ppt` (capped at `Propagation.CAPACITY`) in every
traversable cell within `king_seed_radius_cells` (Chebyshev, the King's own
vertical layer only) of itself (`KingSeeding.seed`, against the exact
`Propagation.Field`/`LevelField` glue `Propagation` itself steps against —
one storage path per faction, never two, and never shared across factions).
Seeded cells are marked active in `PopulationSimulation`'s own
per-faction active-set so propagation's existing tick picks them up from
there; the King does not step propagation itself. A Zombie King seeds ONLY
the zombie field, a Skeleton King ONLY the skeleton field, and a Villager
King ONLY the original `cell_field` — `KingSimulation.seedAll` looks up
`PopulationSimulation.field(level, entry.faction())` per King, so there is
no code path by which one faction's King could raise another's numbers.

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

`/dpop king settle [villager|zombie|skeleton]` (op level 2): attempts to
found a King of the given faction (defaults to `villager` — bare
`/dpop king settle` is unchanged from before this story) at the command
source's position, checking territory suitability then same-faction
uniqueness, in that order; reports which check failed if it didn't settle;
on success, spawns the real King entity.
`/dpop king list`: lists every King in the level (faction, id, position).
`/dpop king remove`: removes the nearest King within 64 blocks of the
command source (any faction), if any — removes both its record and its
entity.

## Threshold-driven population battles ("battle lines") (MINECRAFT-390)

At local faction density >= `PopulationCombatEligibility.THRESHOLD_PPT`
(fixed at **500** on the EXISTING 0..1000 per-cell target-density scale —
this is a correction of the original ticket text's "10000", which would
have needed a scale expansion; 500 does not, since it reads the same scale
`fillagers_per_full_cell`/`Propagation.CAPACITY` already use), that
faction's non-persistent (filler) members start attacking the other
non-persistent factions' members. Continuous/reactive, no hysteresis: this
is re-checked every time the AI goal evaluates eligibility, so a density
crossing back below 500 clears any in-progress target the same cycle — see
`PopulationCombatTargetGoal`'s own javadoc. Each faction becomes
attacker-eligible purely from its OWN local density; the opposing faction
does NOT need to also be at/above threshold.

**Targeting filter** (`PopulationCombatTargeting`): a population-combat
target must be an opposing-faction NON-PERSISTENT filler. Excluded: same
faction, players, persistent/promoted villagers, all three Kings (never
tagged as fillers, so invisible to this filter by construction, plus an
explicit `instanceof` guard for documentation/defense-in-depth), and any
other persistent entity. This exclusion is scoped ONLY to the new
population-combat goal — it does not suppress `FillagerTargetCompat`'s
existing vanilla-attacker-vs-Fillager mirroring, or any mob's ordinary
vanilla hostility (a filler Zombie still vanilla-attacks a player normally).

**Review fix (P1-3):** the persistence check previously ALSO required the
candidate's faction to be empty -- but every candidate reaching that check
already has a non-empty faction (an earlier guard rejects empty-faction
candidates first), so the persistence check was dead code: a tagged filler
that later became persistent (named, leashed, picked up and re-placed, or
any other vanilla persistence-granting action) was NEVER actually excluded.
Fixed to check the entity's real, live `isPersistenceRequired()` state
alone. Because `PopulationCombatTargetGoal#canContinueToUse` calls this
exact check every evaluation cycle, a filler that transitions to persistent
mid-chase loses its eligibility the very next check, not just at the next
reconciliation pass.

**Review fix (P1, round 2 -- the ATTACKER side):** the round-1 fix above only
covered a persistent filler as a TARGET; a filler that becomes persistent
while it is itself ACTIVELY ATTACKING (not just a potential target) could
still keep initiating/continuing population-combat. Fixed in two places:
(1) `PopulationCombatTargetGoal#canUse`/`canContinueToUse` now also check
the ATTACKER's own current `isPersistenceRequired()` every evaluation cycle
(`attackerEligible()`), not just at acquisition time; (2)
`FillerFactionSimulation.isFiller` now also excludes `isPersistenceRequired()`
mobs, so a now-persistent filler is removed from reconciliation's counting
AND bounded-despawn candidate pool entirely, not just from being a combat
participant. Verified live (`scripts/smoke-server.py`): marking an actively
engaged attacker persistent mid-chase stops it from dealing further damage.

**The MINECRAFT-389 exception**: MINECRAFT-389 gave Fillager exactly one
goal (`PanicGoal`, flee-on-hurt) and ZERO `targetSelector` goals, so a
Fillager "never retaliates or acquires a combat target". MINECRAFT-390 adds
ONE narrowly-scoped exception: `targetSelector.addGoal(1, new
PopulationCombatTargetGoal(this, Faction.VILLAGER))`. Below the 500
threshold, that goal's `canUse()` always returns `false` — a Fillager that
never crosses it behaves EXACTLY as MINECRAFT-389 specified. A
`MeleeAttackGoal` (`goalSelector` priority 2) is also added, since a
`targetSelector` goal only sets `getTarget()` — nothing walks toward or
swings at that target without an attack-execution goal reading it, and
Fillager never had one before. **Goal-priority documentation**: `PanicGoal`
(flee, `goalSelector` priority 1) and the new `MeleeAttackGoal` (attack,
priority 2) share the `MOVE` flag, so they can never run concurrently —
fleeing ALWAYS preempts attacking whenever both could apply, for as long as
`PanicGoal` says it's still fleeing. Once that window ends, the Fillager
acts on whatever target the (still-active) `targetSelector` goal set. On a
filler Zombie/Skeleton (literal vanilla entities, not a subclass this mod
owns) the same `PopulationCombatTargetGoal` is instead installed at
join-time (`FillerTargetingInstaller`, the same pattern
`FillagerTargetCompat` already uses) — their existing vanilla attack goals
already act on whatever target is set, so no separate attack-execution goal
is needed there.

## Zombie/Skeleton fillers (MINECRAFT-390)

Zombie and Skeleton are now ordinary, non-persistent "filler" populations
alongside Fillagers: literal vanilla `EntityType.ZOMBIE`/`EntityType.SKELETON`
instances (never a new entity subclass — only the Kings get one), tracked by
`FillerFactionSimulation` using the exact same bounded reconciliation
decision (`FillagerReconciliation.decide`) Fillager already uses, against
each faction's own independent cell field. A scoreboard tag
(`FillerMarkers`, persisted automatically in the entity's own save NBT) is
the ENTIRE distinguishing mark between "a filler this mod spawned and
manages" and any other Zombie/Skeleton in the world (natural spawn, spawn
egg, named, leashed, another mod) — only tagged instances are ever counted,
spawned, despawned, or population-combat-eligible. No promotion economy and
no custom hazard-death feedback for either faction (not requested; vanilla's
own fire/lava/drowning damage already applies to any entity regardless).

## Independent light/sun-burn override at density > 250 (MINECRAFT-390)

A SECOND, fully independent threshold on the same 0..1000 density scale:
when a faction's OWN local density is **strictly greater than 250**
(`EnvironmentalOverride`), that faction's ordinary Zombie/Skeleton fillers
ignore the spawn-time light-level restriction (`FillerSpawnRules`, which
otherwise mirrors vanilla's own overworld hostile-spawn light cap of <= 7 —
remember this mod spawns fillers itself, never through vanilla's natural
spawner, so this restriction is this mod's own, not a bypass of vanilla's)
and have sun-exposure burning suppressed (`SunImmunityTicking`).

**Review fix (P1-2), final design (two prior attempts were each caught --
one by live testing, one by re-review -- see `SunImmunityTicking`'s own
javadoc for the full blow-by-blow):** fire/soul-fire block contact and lava
contact use the IDENTICAL `igniteForSeconds(8.0F)` call vanilla's own
sun-burn path uses, so duration/state alone can never disambiguate "on fire
from the sun" from "on fire from a fire block burning in an open, sunlit
field" -- both can be simultaneously true. The fix only ever acts on the
EXACT TICK a FRESH ignition happens (a jump in remaining fire ticks, or a
transition from not-on-fire to on-fire; a continuing, already-decaying fire
is left completely alone regardless of its true origin), and at that exact
moment positively rules out every KNOWN non-sun cause before treating it as
sun-caused: standing in/touching fire, soul fire, or lava THIS tick, or
having just been struck by a projectile that was itself on fire (exactly
how a Flame-enchanted arrow signals its own ignition intent -- see
`AbstractArrow#onHitEntity`). Only when none of those apply, the fresh
ignition coincides with vanilla's own deterministic sun-tick conditions
(daytime, bright, open sky, dry -- everything `Mob#isSunBurnTick()` checks
except its final per-tick random roll, which an earlier attempt tried to
re-invoke directly and found unreliable), and the density override is
active, is the fire cleared. `SunBurnSuppression.shouldSuppress` is the
pure, unit-tested decision (including the reviewer's exact test case:
sun-eligible conditions AND a known non-sun cause both true at once must
never suppress); see `SunImmunityTicking`'s own javadoc for the one
honestly-flagged remaining gap this cannot fully close without a Mixin/ASM
transformer (a modded ignition source this mod doesn't know to check for).

Does NOT grant general fire/lava immunity — only the sun-exposure burning
check specifically; ordinary fire/lava/Flame damage from any other source
is untouched. One faction's density grants only that faction's override
(Zombie density never suppresses Skeleton burning, or vice versa). Fully
independent of, and composable with, the >=500 combat threshold — a cell
can be below 250 (normal rules), between 250 and 500 (environmental
override active, combat not yet active), or at/above 500 (both active).

## Day/night spawn-rate multiplier (MINECRAFT-390, settled per Jira comment id 48143)

Reconciliation's per-interval spawn RATE (never despawn, never density/target
accounting, never Kings) is scaled by a per-faction time-of-day multiplier,
read from world/game ticks only (`ServerLevel#getDayTime() % 24000`) —
**never real wall-clock time**. `DayNightSpawnRate` is the pure logic.

**Review fix (P1-1):** a naive `floor(configuredCap * multiplier)` PER
INTERVAL silently floors every sub-1.0 result to a literal ZERO whenever
`configuredCap` is small relative to `1/multiplier` — at the DEFAULT
`fillager_refill_per_interval = 1`, `floor(1 * 0.25) == 0` and
`floor(1 * 0.5) == 0`, so Fillagers would spawn at a flat zero rate all
night, and Zombie/Skeleton fillers at a flat zero rate in both their
non-peak phases, never the requested 25%/50%. `DayNightSpawnRate.accumulate`
instead carries the undistributed fraction forward across intervals (e.g.
four intervals at 0.25x with cap 1: 0, 0, 0, 1 — one real spawn every four
intervals, not zero forever); `SpawnRateBudget` is the one place per
(level, faction) that fraction is actually threaded across real
reconciliation ticks. ONLY `fillager_refill_per_interval` is scaled this
way — `fillager_spawn_cap_per_interval` stays UNSCALED, since it governs a
different thing (a per-cell burst cap, not the overall rate) and was never
the source of the zeroing bug.

`FillagerPopulationSimulation.reconcile`/`FillerFactionSimulation.reconcile`
are the two call sites, each scaling ONLY `fillager_refill_per_interval`
(via `SpawnRateBudget`'s fractional-carry accumulator) and NEVER
`fillager_spawn_cap_per_interval` or `fillager_despawn_cap_per_interval`,
before building the same `FillagerReconciliation.Params` as before.

**Fillager: an independent 12h/12h day/night split** (NOT part of the
8-hour-phase matrix below) — 100% of the normal configured rate for
`[0, 12000)` (dawn through sunset), 25% for `[12000, 24000)` (sunset through
the next dawn).

**Zombie/Skeleton: three successive, equal, non-overlapping 8000-tick
phases tiling the full cycle**, cut at ticks 4000, 12000, and 20000 (mod
24000), in the fixed order Day / Early-night / Late-night:

| Phase | Tick range | Clock-equivalent | Alignment with real darkness |
|---|---|---|---|
| Day | `[4000, 12000)` | 10:00-18:00 | solidly daytime; ends exactly at vanilla's own sunset tick (12000) |
| Early-night (Zombie's own peak) | `[12000, 20000)` | 18:00-2:00 | starts EXACTLY at vanilla's sunset tick (12000) |
| Late-night (Skeleton's own peak) | `[20000, 24000) ∪ [0, 4000)` | 2:00-10:00 | covers deep night through vanilla's own sunrise ticks (23000-24000) and just past dawn |

Cutting at 4000/12000/20000 (rather than naively at 0/8000/16000) is the one
deliberate choice that makes Early-night start exactly at real sunset rather
than drift 4000 ticks ahead of it into genuine daylight.

Multiplier matrix (relative to each faction's OWN normal configured rate):

| Faction | Day | Early-night | Late-night |
|---|---|---|---|
| Zombie | 0.25 | **1.00** (own peak) | 0.50 |
| Skeleton | 0.25 | 0.50 | **1.00** (own peak) |

Fully independent of, and composed with (never gating), the `>250`
light/sun override above: both are computed from entirely separate inputs
(game tick vs. local density) and neither call site lets one short-circuit
the other.

This requirement's exact shape changed three times in rapid succession
while this PR was in progress before settling on the above (confirmed via
Jira comment id 48143 on this ticket) — flagged here for anyone reading the
PR history who sees an earlier "deliberately not implemented" note
superseded by this section.
