# Population propagation (MINECRAFT-27)

Conservative transport between the six face neighbours of a cell. Each step a populated cell sends
`propagation_spread_fraction` of the value it had at the start of the step to its traversable neighbours in one
weighted pass; a neighbour's share is `direction weight x free capacity`. Down is weighted heaviest, sides less,
up least, every direction with room always gets some, and a unit moves one hop per step. Values only move between
cells (the total is conserved); nothing is averaged or aggregated into storage.

**Decision:** conservative transport was chosen over locally generated targets. The worry that justified a hold
(a permanent underground sink) depended on an amplified underground hostile-spawn mechanism that dynamic-atmosphere
no longer has, so a downward gradient is a tuning problem for the three weights, not a structural drain. No floor or
surface protection was added. `PropagationTest#surfaceKeepsAShareUnderPersistentBottomDrain` is the tripwire: if it
fails at the shipped weights, reopen the design question instead of tuning until it passes.

**Traversable:** a cell is traversable if it holds at least one open or liquid block (sampled on a lattice,
`traversable_sample_spacing`). Liquid counts, unlike dynamic-atmosphere's gas, deliberately. Cells whose anchor chunk
is not loaded, or whose stored data is preserved as corrupt, do not take part.

**Config** (all `RestartType.NONE`, read live): `propagation_weight_down/sides/up` (relative shares, range floor 0.01,
never zero), `propagation_spread_fraction`, `propagation_step_interval_ticks`, `traversable_sample_spacing`.

**Debug commands** (op level 2): `/dpop show` (the cell you stand in and its six neighbours, with why one is blocked),
`/dpop set <ppt>`, `/dpop step [n]`, `/dpop field` (populated cells and the total), `/dpop clear`. Overworld only (V1).
Nothing seeds population yet (the Villager King is a later story), so use `/dpop set`.
