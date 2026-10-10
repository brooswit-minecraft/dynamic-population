package io.github.brooswitminecraft.dynamicpopulation.king;

/**
 * Pure territory-preference logic (AC4): surface/plains checks with configurable numeric thresholds. No
 * Minecraft classes -- takes plain values the NeoForge glue samples from the real world, so this is directly
 * unit-testable the same way {@code CellGrid}/{@code Propagation} are.
 */
public final class KingTerritory {
    private KingTerritory() { }

    /**
     * Whether a location is suitable territory for the Villager King to settle: a plains-family biome, with at
     * least {@code minSkyLight} sky light (open-sky exposure), and no more than
     * {@code maxDepthBelowSurfaceBlocks} blocks below the heightmap surface. Biome preference itself is a fixed
     * tag-membership check (not a numeric tunable -- there is no meaningful range for "how plains-y" a biome
     * is), so only the sky-light and surface-depth checks are config-driven.
     */
    public static boolean suitable(boolean plainsBiome, int skyLight, int depthBelowSurfaceBlocks,
            int minSkyLight, int maxDepthBelowSurfaceBlocks) {
        return plainsBiome && skyLight >= minSkyLight && depthBelowSurfaceBlocks <= maxDepthBelowSurfaceBlocks;
    }
}
