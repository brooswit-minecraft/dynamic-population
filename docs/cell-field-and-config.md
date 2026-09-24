# Cell field storage and config scaffold (DPOP-12, implements DPOP-4)

This documents what DPOP-12 shipped: a per-cell value field (a target
density in parts-per-thousand) that persists across save/load, and the
server config scaffold it reads its `cell_size` from. Neither ships any
propagation, Villager King, Fillager entity, spawn, or reconcile logic —
those are later stories. This is the canonical copy of the doc content;
the intended Confluence page (see caveat at the bottom) mirrors it.

## Storage: anchor-chunk attachment

A cell's horizontal footprint is `cell_size / 16` chunks on a side
(`CellGrid.footprintChunks`, default 64 -> 4). Each cell's data is attached
only to the **min-x/min-z chunk of that footprint** (`CellGrid.anchorChunkCoordinate`,
computed with `Math.floorDiv` so negative chunk coordinates map to the
correct, more-negative footprint — see `CellGridTest#anchorChunkCoordinateNegative`).
Every other chunk in the footprint never receives the attachment at all, so
only anchor chunks are ever touched.

Storage uses NeoForge's chunk data-attachment mechanism
(`CellFieldStorage`, `AttachmentType<CellFieldRecord>` registered via
`DeferredRegister<AttachmentType<?>>`), not a level-wide `SavedData` —
the attachment's NBT round-trips through the same per-chunk save data
`ChunkSerializer` already writes, so deleting a chunk's region file deletes
its cell data with it.

Vertical layering: `CellGrid.layerCount(minBuildHeight, maxBuildHeight, cellSize)`
derives the number of cell layers from the world's actual height and the
configured `cell_size` (ceiling division, so a non-exact-multiple height
still gets a partial top layer rather than losing it) — for the Overworld's
384-block height at the default `cell_size` 64, that's exactly 6, but the
number is always derived, never hard-coded.

### Stored shape (one anchor chunk's attachment)

| field | type | meaning |
|---|---|---|
| `version` | int | world-save format version (`CellFieldCodec.CURRENT_VERSION`, currently `1`) — distinct from the config file's `configVersion` below |
| `cellSize` | int | the `cell_size` this payload was written under |
| `anchorX`, `anchorZ` | int | the anchor chunk this payload belongs to (self-consistency check) |
| `entries` | int[] | flattened sparse `(layerIndex, densityPpt)` pairs, one pair per layer that has a value; density is `0..1000` inclusive |

## Stale vs. mismatch vs. corrupt

`CellFieldCodec.decode` (pure Java, no Minecraft classes, unit-tested
directly) classifies every read into exactly one of four outcomes:

1. **`version` present but `!= CURRENT_VERSION` -> `Stale` -> DISCARD.**
   Applies even when the rest of the payload is well-formed
   (`CellFieldCodecTest#staleVersionWithWellFormedPayloadIsStaleNotCorruptNotLoaded`).
   The chunk is treated as empty (regrows from the King) and gets a fresh,
   current-version header the next time it's saved.
2. **`version` current but `cellSize != expectedCellSize` -> `SizeMismatch` -> DISCARD.**
   Distinctly tested from (1)
   (`CellFieldCodecTest#cellSizeMismatchWithCurrentVersionIsSizeMismatchNotStale`) —
   removing either check alone still fails the other's test.
3. **Header missing/unparseable, or payload structurally broken** (wrong
   NBT types, odd-length `entries`, an anchor that doesn't belong to this
   chunk, a layer index outside `[0, layerCount)`, a density outside
   `[0, 1000]`, a duplicate layer index) **-> `Corrupt` -> PRESERVE-AND-SKIP.**
   `CellFieldStorage` keeps the raw `CompoundTag` verbatim
   (`CellFieldRecord.Preserved`) and re-emits it unchanged on the next save,
   skipping it for ticking (ticking isn't implemented yet regardless).
4. **A downgrade** (`version` newer than current) is not settled by DPOP-4's
   ticket text; it is treated the same as case 1 (`!= CURRENT_VERSION` ->
   DISCARD). Flagging this explicitly per the ticket's own instruction, in
   case a reviewer wants to overrule it.

### The ATMO-21 guarantee

ATMO-21 was preserving-stale-then-refusing-to-overwrite: once a chunk was
marked "preserved", fresh valid data for that chunk was never written back.
That cannot happen here because `CellFieldRecord` is a two-case sealed
interface (`Loaded` / `Preserved`) with **no state on either case that
blocks a write** — `CellFieldStorage.write` handles both variants
unconditionally, and nothing in `CellFieldCodec` or `CellFieldStorage`
inspects the attachment's previous value before accepting a new `Loaded`.
A `Preserved` chunk is only ever superseded by whatever the caller sets
next; there is no "already preserved, refuse" branch to construct.

## Config scaffold

`DynamicPopulationConfig` (`ModConfig.Type.SERVER`, file
`dynamicpopulation-server.toml`) is read live: a single `volatile Snapshot
snapshot` field (an immutable record: `cellSize`, `configVersion`) is
rebuilt in `onLoading`/`onReloading`/`onUnloading` and every call site reads
`DynamicPopulationConfig.get()` fresh — there is no `static final` seeded
once at class load (that was dynamic-atmosphere's `smokeOpticalDensity`
bug: it froze the value at class-init time and never saw a reload). No
custom `/reload` command or hook was added; FML's own nightconfig file
watcher is relied on for live reload of `RestartType.NONE` keys (today,
there are none — see below).

- `cell_size`: default 64, `.worldRestart()` (`RestartType.WORLD`, via the
  builder — machine-readable, not prose), range validated as `16..1024 AND
  a multiple of 16` (`isValidCellSize`) — a bare numeric range alone would
  let e.g. 70 through, which wouldn't yield a whole-chunk footprint. The
  comment on the key, and this doc, both say that changing it resets the
  population field.
- `configVersion`: bumped only when an existing key's shipped default
  moves — not on every release, and not for a new key (nightconfig already
  fills new keys from their defaults and leaves existing keys untouched).

### How to add a later `RestartType.NONE` key

1. Add a `BUILDER.comment(...).define(...)` (or `.defineInRange(...)`) call
   in `DynamicPopulationConfig`'s static initializer, producing a new
   `ConfigValue` field. Omit `.worldRestart()`/`.gameRestart()` — that's
   what makes it `RestartType.NONE`.
2. Add a field for it to the `Snapshot` record.
3. Read it in `readSnapshot()`.

No other restructuring needed — this is the scaffold's whole point.

### `configVersion` rename-aside sequence

`ConfigVersionMigration.renameAsideIfMismatched` (pure `Path` operations,
`@TempDir`-testable) does the actual rename: if `storedVersion !=
currentVersion` and the file exists, it moves it to
`<name>.toml.bak-<storedVersion>` (never deletes; a no-op if the file is
missing or versions already match). Required tests, all present in
`ConfigVersionMigrationTest`: a mismatch renames and produces a
byte-identical backup; an unchanged `configVersion` touches nothing; a
missing file is a no-op.

Ordering — **verified by disassembling `ServerLifecycleHooks` in the
resolved `neoforge-21.1.251` jar**, not assumed: on server start, NeoForge's
`ConfigTracker.loadConfigs` reads the on-disk file and fires
`ModConfigEvent.Loading` synchronously, before `ServerAboutToStartEvent` —
there is no earlier, mod-accessible hook. So a mismatch cannot be caught
before FML's own parse; instead, `DynamicPopulationConfig.onLoading` detects
it immediately after that parse (`handleConfigVersionMismatch`), renames the
just-read file aside, forces `CELL_SIZE`/`CONFIG_VERSION` back to defaults
via `.set(...)`, and calls `SPEC.save()` (which regenerates the file on
disk) — all before the mod constructor finishes registering anything else,
and long before any chunk/world code can observe a value. That is what
makes "the regenerated file is what the game runs on" true for that same
session.

**Observed live** (not just by unit test): a manual server boot with
`configVersion` hand-edited to a mismatched value produced this exact log
line and file layout —

```
[Server thread/WARN] [io.gi.br.dy.DynamicPopulationMod/]: dynamicpopulation
configVersion mismatch (found 99, expected 1): renamed
.../config/dynamicpopulation-server.toml aside to
.../config/dynamicpopulation-server.toml.bak-99 and regenerated it from
current defaults -- any externally synced keys may need to be re-applied.
```

— and the regenerated `config/dynamicpopulation-server.toml` had
`cell_size = 64` / `configVersion = 1` (fresh defaults) while
`dynamicpopulation-server.toml.bak-99` held the original, byte-for-byte,
hand-edited `cell_size = 128` / `configVersion = 99`. The rename-at-boot
ordering claim above is therefore observed end-to-end for this change, not
just asserted from the bytecode read plus a unit test.

The rename's log line and the CHANGELOG `## Migration` template
(`docs/release-note-template.md`) both say explicitly that `configVersion`
bumps and synced keys may need re-applying. This is a **diagnosis aid
only** — the actual re-sync guarantee lives on the consuming side (Sickos)
and is not built here.

## Operational gotchas

- **Server SERVER-type configs live in the per-instance `config/`
  directory, not `<world>/serverconfig/`.** This corrects an assumption in
  DPOP-4's own steering. Observed directly across two manual server boots
  (see below): `dynamicpopulation-server.toml` was written to and read
  from `<instance>/config/dynamicpopulation-server.toml`.
  `<world>/serverconfig/` is a *separate, optional override* — its own
  generated `readme.txt` says a file placed there by hand overrides the
  corresponding `config/` file for that world; nothing is written there
  automatically, and it was empty (aside from that readme) in both boots.
- `DISABLE_CONFIG_WATCHER` in FML's `fml.toml` can silently disable live
  config reload.
- A server-side config sync (e.g. Sickos) can only reach server config,
  never client config — DPOP has no client config today, and none should
  be added without revisiting this.

## What was actually observed vs. unit-test-only

- **Observed, live, two separate manual server boots** (not the CI/release
  smoke test, which deletes its temp directory on exit): the mod loads
  without crashing under the real config/attachment registration path; the
  server config is written to and read from `config/`, not
  `<world>/serverconfig/`; a `configVersion` mismatch triggers the rename-
  aside + regenerate-from-defaults sequence, with a byte-identical backup,
  exactly as designed.
- **Not observed**: FML's live-reload file watcher actually firing on an
  on-disk edit while a server is running (confirmed present in the FML
  binary only); an actual chunk save/load round trip of real cell field
  data through a generated world region file (the anchor-chunk mapping,
  the codec's classify/decode/encode logic, and the ATMO-21 non-refusal
  guarantee are all covered by direct unit tests instead — `CellGridTest`,
  `CellFieldCodecTest` — but `CellFieldStorage`'s NeoForge glue itself was
  only exercised via a clean server boot with no chunk generation deep
  enough to force an anchor-chunk attachment read/write, since ticking a
  cell isn't implemented by this story).

## Confluence doc caveat

This ticket's Confluence doc was reported (by the DPOP-4 story agent, on
this ticket) as failing to create for project DPOP: `ensureDoc: project
entity property "butchr" is unreadable — 404`. Per that guidance, this file
plus the PR description are the primary copies of this content; `get_doc`/
`set_doc` were still attempted for the real page — check the PR description
or this ticket's comments for whether that attempt succeeded.
