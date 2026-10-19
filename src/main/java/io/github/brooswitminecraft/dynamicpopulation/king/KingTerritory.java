package io.github.brooswitminecraft.dynamicpopulation.king;

/**
 * Pure territory-preference logic (AC4): surface checks with configurable numeric thresholds. No Minecraft
 * classes -- takes plain values the NeoForge glue samples from the real world, so this is directly
 * unit-testable the same way {@code CellGrid}/{@code Propagation} are.
 */
public final class KingTerritory {
    private KingTerritory() { }

    /**
     * Whether a location is suitable territory for a King to settle: at least {@code minSkyLight} sky light
     * (open-sky exposure), and no more than {@code maxDepthBelowSurfaceBlocks} blocks below the heightmap
     * surface. Biome identity is NOT checked here (MINECRAFT-413): a King's territory preference is
     * surface/sky-light/depth only, for any biome.
     */
    public static boolean suitable(int skyLight, int depthBelowSurfaceBlocks,
            int minSkyLight, int maxDepthBelowSurfaceBlocks) {
        return skyLight >= minSkyLight && depthBelowSurfaceBlocks <= maxDepthBelowSurfaceBlocks;
    }
}
