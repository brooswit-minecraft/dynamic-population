package io.github.brooswitminecraft.dynamicpopulation.cellfield;

/**
 * Pure chunk/cell coordinate math. No Minecraft classes: takes and returns
 * plain chunk coordinates and block-Y values so it is directly unit-testable.
 */
public final class CellGrid {
    private CellGrid() { }

    /**
     * Edge length, in chunks, of a cell's horizontal footprint. {@code cellSize}
     * must already be a validated multiple of 16 (enforced by the config's own
     * validator) — this only derives the chunk count, it does not re-validate.
     */
    public static int footprintChunks(int cellSize) {
        if (cellSize <= 0 || cellSize % 16 != 0) {
            throw new IllegalArgumentException("cellSize must be a positive multiple of 16, got " + cellSize);
        }
        return cellSize / 16;
    }

    /**
     * The min-x (or min-z) chunk coordinate of the footprint containing
     * {@code chunkCoordinate}. Uses floor division so negative chunk
     * coordinates map to the correct (more negative) footprint.
     */
    public static int anchorChunkCoordinate(int chunkCoordinate, int cellSize) {
        int footprint = footprintChunks(cellSize);
        return Math.floorDiv(chunkCoordinate, footprint) * footprint;
    }

    /** Whether (chunkX, chunkZ) is itself the anchor chunk of its footprint. */
    public static boolean isAnchorChunk(int chunkX, int chunkZ, int cellSize) {
        return chunkX == anchorChunkCoordinate(chunkX, cellSize)
            && chunkZ == anchorChunkCoordinate(chunkZ, cellSize);
    }

    /**
     * Number of vertical cell layers spanning [minBuildHeight, maxBuildHeight).
     * Ceiling division so a world height that isn't an exact multiple of
     * cellSize still gets a (partial) top layer rather than losing it.
     */
    public static int layerCount(int minBuildHeight, int maxBuildHeight, int cellSize) {
        int height = maxBuildHeight - minBuildHeight;
        if (height <= 0) {
            throw new IllegalArgumentException(
                "maxBuildHeight (" + maxBuildHeight + ") must exceed minBuildHeight (" + minBuildHeight + ")");
        }
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive, got " + cellSize);
        }
        return -Math.floorDiv(-height, cellSize);
    }

    /** Which cell layer a world block-Y falls into, given the world's min build height. */
    public static int layerIndex(int blockY, int minBuildHeight, int cellSize) {
        return Math.floorDiv(blockY - minBuildHeight, cellSize);
    }
}
