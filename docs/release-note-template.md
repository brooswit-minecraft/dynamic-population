# Release note template

Follow this when writing the `## Migration` section (see README.md's
"CHANGELOG conventions") of a `CHANGELOG.md` entry that touches the server
config scaffold (`DynamicPopulationConfig`) or the cell field storage format
(`CellFieldCodec`/`CellFieldStorage`). These are disclosure requirements, not
migration guarantees — the mod does not silently fix either case for you.

## If the change moves `cell_size`'s shipped default

State explicitly:

> Changing `cell_size` resets the population field: on load, previously
> saved cell data whose header `cell_size` no longer matches the running
> config is discarded, and that chunk regrows from the Villager King.

## If the change bumps `configVersion`

State explicitly:

> `configVersion` was bumped because `<name the specific key(s) whose
> default moved>`. On next load, an existing `dynamicpopulation-server.toml`
> with the old `configVersion` is renamed aside to
> `dynamicpopulation-server.toml.bak-<oldVersion>` and regenerated from
> current defaults. **Any externally synced keys (e.g. via Sickos) may need
> to be re-applied after this bump** — the rename is a diagnosis aid, not an
> automatic re-sync; re-syncing is Sickos's job, not this mod's.

Do not phrase either note as "safe" or "handled automatically" — both are
disclosures that data/config was reset or replaced, not a claim that nothing
needs attention afterward.
