package io.github.brooswitminecraft.dynamicpopulation.cellfield;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Pure-Java classify/decode/encode logic for the per-anchor-chunk cell field
 * payload. No Minecraft or NBT classes anywhere in this file — the NeoForge
 * glue (CellFieldStorage) extracts plain ints/arrays out of a real
 * CompoundTag and hands them to {@link #decode}, so this class is directly
 * unit-testable with JUnit alone.
 *
 * <p>Stored shape (each anchor chunk's attachment): a header of
 * {@code version} (world-save format version, distinct from the config
 * file's {@code configVersion}), {@code cellSize}, {@code anchorX/anchorZ}
 * (self-consistency check against the chunk the data is attached to), and a
 * sparse {@code entries} int array of flattened (layerIndex, densityPpt)
 * pairs — one pair per cell layer that actually has a value.
 */
public final class CellFieldCodec {
    /** World-save format version. Distinct from the config file's configVersion. */
    public static final int CURRENT_VERSION = 1;

    /** Density is a target in parts-per-thousand: 0..1000 inclusive. */
    public static final int MAX_DENSITY_PPT = 1000;

    private CellFieldCodec() { }

    public sealed interface DecodeResult {
        record Loaded(SortedMap<Integer, Integer> layerDensities) implements DecodeResult { }

        /** header parses, version != current -> STALE: DISCARD, even with an otherwise well-formed payload. */
        record Stale(int foundVersion, int expectedVersion) implements DecodeResult { }

        /** header parses, version current, cellSize != configured -> DISCARD, distinct from Stale. */
        record SizeMismatch(int foundCellSize, int expectedCellSize) implements DecodeResult { }

        /** header missing/unparseable, or payload structurally broken -> CORRUPT: PRESERVE-AND-SKIP. */
        record Corrupt(String reason) implements DecodeResult { }
    }

    /**
     * Classifies and, if valid, decodes a cell field payload already extracted
     * from NBT into plain values. {@code version}/{@code cellSize}/{@code anchorX}/
     * {@code anchorZ}/{@code entries} are {@link Optional#empty()} exactly when
     * the source tag was missing that key or had it under the wrong tag type —
     * callers must not attempt to coerce a wrong-typed value, that IS the
     * "header missing/unparseable" / "wrong types" case this method classifies
     * as corrupt.
     *
     * @param expectedCellSize the running config's cell_size
     * @param expectedAnchorX  the anchor chunk this payload is being read from (chunk x)
     * @param expectedAnchorZ  the anchor chunk this payload is being read from (chunk z)
     * @param layerCount       number of valid cell layers for this world/cellSize
     */
    public static DecodeResult decode(
        Optional<Integer> version,
        Optional<Integer> cellSize,
        Optional<Integer> anchorX,
        Optional<Integer> anchorZ,
        Optional<int[]> entries,
        int expectedCellSize,
        int expectedAnchorX,
        int expectedAnchorZ,
        int layerCount
    ) {
        if (version.isEmpty() || cellSize.isEmpty()) {
            return new DecodeResult.Corrupt("header missing or unparseable: version/cellSize not both present as ints");
        }
        if (version.get() != CURRENT_VERSION) {
            return new DecodeResult.Stale(version.get(), CURRENT_VERSION);
        }
        if (cellSize.get() != expectedCellSize) {
            return new DecodeResult.SizeMismatch(cellSize.get(), expectedCellSize);
        }
        if (anchorX.isEmpty() || anchorZ.isEmpty() || entries.isEmpty()) {
            return new DecodeResult.Corrupt("payload missing or unparseable: anchorX/anchorZ/entries not all present");
        }
        if (anchorX.get() != expectedAnchorX || anchorZ.get() != expectedAnchorZ) {
            return new DecodeResult.Corrupt("stored anchor (%d, %d) does not belong to this anchor chunk (%d, %d)"
                .formatted(anchorX.get(), anchorZ.get(), expectedAnchorX, expectedAnchorZ));
        }
        int[] values = entries.get();
        if (values.length % 2 != 0) {
            return new DecodeResult.Corrupt("entries array has odd length " + values.length);
        }
        SortedMap<Integer, Integer> layerDensities = new TreeMap<>();
        for (int i = 0; i < values.length; i += 2) {
            int layer = values[i];
            int density = values[i + 1];
            if (layer < 0 || layer >= layerCount) {
                return new DecodeResult.Corrupt("layer index " + layer + " outside valid range [0, " + layerCount + ")");
            }
            if (density < 0 || density > MAX_DENSITY_PPT) {
                return new DecodeResult.Corrupt("density " + density + " outside valid range [0, " + MAX_DENSITY_PPT + "]");
            }
            if (layerDensities.put(layer, density) != null) {
                return new DecodeResult.Corrupt("duplicate layer index " + layer);
            }
        }
        return new DecodeResult.Loaded(layerDensities);
    }

    /** Flattens a layer->density map into the sparse (layerIndex, densityPpt) int-array payload shape. */
    public static int[] encodeEntries(SortedMap<Integer, Integer> layerDensities) {
        int[] values = new int[layerDensities.size() * 2];
        int i = 0;
        for (var entry : layerDensities.entrySet()) {
            values[i++] = entry.getKey();
            values[i++] = entry.getValue();
        }
        return values;
    }
}
