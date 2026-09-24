package io.github.brooswitminecraft.dynamicpopulation.cellfield;

import java.util.SortedMap;

import net.minecraft.nbt.CompoundTag;

/**
 * In-memory value held by the {@code cell_field} chunk attachment. Two
 * variants only, deliberately no separate "empty" case: a chunk with no
 * cell values yet (never attached, or discarded as stale/size-mismatched)
 * is simply a {@link Loaded} with an empty map, so writing it back out
 * always re-encodes a fresh, current-version header — that IS how a
 * discarded chunk "regrows and can be overwritten on the next save".
 */
public sealed interface CellFieldRecord {
    /** Successfully decoded (or freshly-defaulted / discarded-to-empty) cell data. */
    record Loaded(int cellSize, int anchorChunkX, int anchorChunkZ, SortedMap<Integer, Integer> layerDensities)
        implements CellFieldRecord { }

    /**
     * Genuinely corrupt payload, held verbatim (a direct copy of the original
     * tag, never round-tripped through our own encode/decode) so it is
     * re-emitted unchanged on the next save rather than lost. Skipped for
     * ticking. Overwriting it with a fresh {@link Loaded} via setData is
     * always allowed — nothing in this type or CellFieldStorage refuses a
     * write because the previous value was Preserved, which is exactly the
     * guarantee ATMO-21 got backwards.
     */
    record Preserved(CompoundTag rawTag) implements CellFieldRecord { }
}
