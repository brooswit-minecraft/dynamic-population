package io.github.brooswitminecraft.dynamicpopulation.cellfield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class CellFieldCodecTest {
    private static final int CELL_SIZE = 64;
    private static final int ANCHOR_X = -4;
    private static final int ANCHOR_Z = 0;
    private static final int LAYER_COUNT = 6;

    private static CellFieldCodec.DecodeResult decode(
        Optional<Integer> version, Optional<Integer> cellSize,
        Optional<Integer> anchorX, Optional<Integer> anchorZ, Optional<int[]> entries) {
        return CellFieldCodec.decode(version, cellSize, anchorX, anchorZ, entries,
            CELL_SIZE, ANCHOR_X, ANCHOR_Z, LAYER_COUNT);
    }

    private static Optional<Integer> some(int v) {
        return Optional.of(v);
    }

    private static Optional<int[]> entries(int... values) {
        return Optional.of(values);
    }

    @Test
    void wellFormedPayloadDecodesToLoaded() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, 500, 3, 250));

        var loaded = assertInstanceOf(CellFieldCodec.DecodeResult.Loaded.class, result);
        SortedMap<Integer, Integer> expected = new TreeMap<>();
        expected.put(0, 500);
        expected.put(3, 250);
        assertEquals(expected, loaded.layerDensities());
    }

    @Test
    void emptyEntriesDecodesToLoadedWithEmptyMap() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z), entries());
        var loaded = assertInstanceOf(CellFieldCodec.DecodeResult.Loaded.class, result);
        assertTrue(loaded.layerDensities().isEmpty());
    }

    @Test
    void missingVersionIsCorruptHeaderUnparseable() {
        var result = decode(
            Optional.empty(), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z), entries(0, 500));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void missingCellSizeIsCorruptHeaderUnparseable() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), Optional.empty(), some(ANCHOR_X), some(ANCHOR_Z), entries(0, 500));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    /** Required case: a stale header with an otherwise well-formed payload must still be discarded, not preserved. */
    @Test
    void staleVersionWithWellFormedPayloadIsStaleNotCorruptNotLoaded() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION + 1), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, 500));
        var stale = assertInstanceOf(CellFieldCodec.DecodeResult.Stale.class, result);
        assertEquals(CellFieldCodec.CURRENT_VERSION + 1, stale.foundVersion());
        assertEquals(CellFieldCodec.CURRENT_VERSION, stale.expectedVersion());
    }

    /**
     * Distinct from the stale-version case: version matches, only cellSize
     * differs. Removing only the version check (leaving this one) would
     * still fail this test's expected type if the two checks were merged.
     */
    @Test
    void cellSizeMismatchWithCurrentVersionIsSizeMismatchNotStale() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE + 16), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, 500));
        var mismatch = assertInstanceOf(CellFieldCodec.DecodeResult.SizeMismatch.class, result);
        assertEquals(CELL_SIZE + 16, mismatch.foundCellSize());
        assertEquals(CELL_SIZE, mismatch.expectedCellSize());
    }

    @Test
    void anchorMismatchIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X + 4), some(ANCHOR_Z),
            entries(0, 500));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void oddLengthEntriesIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, 500, 1));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void negativeLayerIndexIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(-1, 500));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void layerIndexAtOrAboveLayerCountIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(LAYER_COUNT, 500));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void negativeDensityIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, -1));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void densityAboveMaxPptIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, CellFieldCodec.MAX_DENSITY_PPT + 1));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void densityAtMaxPptIsAccepted() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, CellFieldCodec.MAX_DENSITY_PPT));
        assertInstanceOf(CellFieldCodec.DecodeResult.Loaded.class, result);
    }

    @Test
    void duplicateLayerIndexIsCorrupt() {
        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            entries(0, 100, 0, 200));
        assertInstanceOf(CellFieldCodec.DecodeResult.Corrupt.class, result);
    }

    @Test
    void encodeEntriesRoundTripsThroughDecode() {
        SortedMap<Integer, Integer> densities = new TreeMap<>();
        densities.put(0, 100);
        densities.put(2, 900);
        densities.put(5, 1000);
        int[] encoded = CellFieldCodec.encodeEntries(densities);

        var result = decode(
            some(CellFieldCodec.CURRENT_VERSION), some(CELL_SIZE), some(ANCHOR_X), some(ANCHOR_Z),
            Optional.of(encoded));
        var loaded = assertInstanceOf(CellFieldCodec.DecodeResult.Loaded.class, result);
        assertEquals(densities, loaded.layerDensities());
    }
}
